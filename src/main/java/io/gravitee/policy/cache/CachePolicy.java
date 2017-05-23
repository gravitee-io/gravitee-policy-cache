/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.cache;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.gateway.api.*;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.stream.ReadStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.util.CacheControlUtil;
import io.gravitee.policy.cache.util.ExpiresUtil;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.Cache;
import io.gravitee.resource.cache.CacheResource;
import io.gravitee.resource.cache.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 * @author GraviteeSource Team
 */
public class CachePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachePolicy.class);

    /**
     * Cache policy configuration
     */
    private final CachePolicyConfiguration cachePolicyConfiguration;

    private final static char KEY_SEPARATOR = '_';

    // Policy cache action
    private final static String CACHE_ACTION_QUERY_PARAMETER = "cache";
    private final static String X_GRAVITEE_CACHE_ACTION = "X-Gravitee-Cache";

    private Cache cache;
    private CacheAction action;

    public CachePolicy(final CachePolicyConfiguration cachePolicyConfiguration) {
        this.cachePolicyConfiguration = cachePolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        action = lookForAction(request);

        if (action != CacheAction.BY_PASS) {
            if (request.method() == HttpMethod.GET ||
                    request.method() == HttpMethod.OPTIONS ||
                    request.method() == HttpMethod.HEAD) {

                // It's safe to do so because a new instance of policy is created for each request.
                String cacheName = cachePolicyConfiguration.getCacheName();
                CacheResource cacheResource = executionContext.getComponent(ResourceManager.class)
                        .getResource(cacheName, CacheResource.class);
                if (cacheResource == null) {
                    policyChain.failWith(PolicyResult.failure("No cache has been defined with name " + cacheName));
                    return;
                }

                cache = cacheResource.getCache();
                if (cache == null) {
                    policyChain.failWith(PolicyResult.failure("No cache named [ " + cacheName + " ] has been found."));
                    return;
                }

                // Override the invoker for safe request to cache content (if required)
                Invoker defaultInvoker = (Invoker) executionContext.getAttribute(ExecutionContext.ATTR_INVOKER);
                executionContext.setAttribute(ExecutionContext.ATTR_INVOKER, new CacheInvoker(defaultInvoker));
            } else {
                LOGGER.debug("Request {} is not a safe request, disable caching for it.", request.id());
            }
        }

        policyChain.doNext(request, response);
    }

    class CacheInvoker implements Invoker {

        private final Invoker invoker;

        CacheInvoker(final Invoker invoker) {
            this.invoker = invoker;
        }

        @Override
        public ClientRequest invoke(ExecutionContext executionContext, Request serverRequest, Handler<ClientResponse> handler) {
            // Here we have to check if there is a value in cache
            String cacheId = hash(serverRequest, executionContext);
            LOGGER.debug("Looking for element in cache with the key {}", cacheId);

            Element elt = cache.get(cacheId);

            if (elt != null && action != CacheAction.REFRESH) {
                LOGGER.debug("An element has been found for key {}, returning the cached response to the initial client", cacheId);

                // Ok, there is a value for this request in cache
                ClientRequest noOpClientRequest = new ClientRequest() {
                    @Override
                    public ClientRequest connectTimeoutHandler(Handler<Throwable> handler) {
                        return this;
                    }

                    @Override
                    public ClientRequest write(Buffer buffer) {
                        return this;
                    }

                    @Override
                    public void end() {
                        CacheResponse cacheResponse = (CacheResponse) elt.value();
                        CacheClientResponse response = new CacheClientResponse(cacheResponse);

                        boolean hasContent = (cacheResponse.getContent() != null && cacheResponse.getContent().length() > 0);

                        handler.handle(response);

                        if (hasContent) {
                            response.bodyHandler.handle(cacheResponse.getContent());
                        }

                        response.endHandler.handle(null);
                    }
                };

                serverRequest
                        .bodyHandler(noOpClientRequest::write)
                        .endHandler(endResult -> noOpClientRequest.end());

                return noOpClientRequest;
            } else {
                if (action == CacheAction.REFRESH) {
                    LOGGER.info("A refresh action has been received for key {}, invoke backend with invoker", cacheId, invoker.getClass().getName());
                } else {
                    LOGGER.debug("No element for key {}, invoke backend with invoker {}", cacheId, invoker.getClass().getName());
                }

                // No value, let's do the default invocation and cache result in response
                return invoker.invoke(executionContext, serverRequest, clientResponse -> {
                    CacheResponse response = new CacheResponse();

                    handler.handle(new ClientResponse() {
                        final Buffer content = Buffer.buffer();

                        @Override
                        public int status() {
                            response.setStatus(clientResponse.status());
                            return clientResponse.status();
                        }

                        @Override
                        public HttpHeaders headers() {
                            response.setHeaders(clientResponse.headers());
                            return clientResponse.headers();
                        }

                        @Override
                        public ReadStream<Buffer> bodyHandler(Handler<Buffer> handler1) {
                            return clientResponse.bodyHandler(chunk -> {
                                content.appendBuffer(chunk);
                                handler1.handle(chunk);
                            });
                        }

                        @Override
                        public ReadStream<Buffer> endHandler(Handler<Void> handler1) {

                            return clientResponse.endHandler(result -> {
                                // Do not put content if not a success status code
                                if (response.getStatus() >= 200 && response.getStatus() < 300) {
                                    response.setContent(content);

                                    long timeToLive = -1;
                                    if (cachePolicyConfiguration.isUseResponseCacheHeaders()) {
                                        timeToLive = resolveTimeToLive(clientResponse);
                                    }
                                    if (timeToLive == -1 || cachePolicyConfiguration.getTimeToLiveSeconds() < timeToLive) {
                                        timeToLive = cachePolicyConfiguration.getTimeToLiveSeconds();
                                    }

                                    final long timeToLiveCache = timeToLive;

                                    LOGGER.debug("Put response in cache for key {} and request {}", cacheId, serverRequest.id());
                                    CacheElement element = new CacheElement(cacheId, response);
                                    element.setTimeToLive((int) timeToLiveCache);
                                    cache.put(element);
                                } else {
                                    LOGGER.debug("Response for key {} not put in cache because of the status code {}",
                                            cacheId, response.getStatus());
                                }
                                handler1.handle(result);
                            });
                        }
                    });
                });
            }
        }
    }

    class CacheClientResponse implements ClientResponse {
        private Handler<Buffer> bodyHandler;
        private Handler<Void> endHandler;

        private final CacheResponse cacheResponse;

        CacheClientResponse(final CacheResponse cacheResponse) {
            this.cacheResponse = cacheResponse;
        }

        @Override
        public int status() {
            return cacheResponse.getStatus();
        }

        @Override
        public HttpHeaders headers() {
            return cacheResponse.getHeaders();
        }

        @Override
        public ClientResponse bodyHandler(Handler<Buffer> bodyPartHandler) {
            this.bodyHandler = bodyPartHandler;
            return this;
        }

        @Override
        public ClientResponse endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }
    }

    /**
     * Generate a unique identifier for the cache key.
     *
     * @param request
     * @param executionContext
     * @return
     */
    public String hash(Request request, ExecutionContext executionContext) {
        StringBuilder sb = new StringBuilder();
        switch (cachePolicyConfiguration.getScope()) {
            case APPLICATION:
                sb.append(executionContext.getAttribute(ExecutionContext.ATTR_API)).append(KEY_SEPARATOR);
                sb.append(executionContext.getAttribute(ExecutionContext.ATTR_APPLICATION)).append(KEY_SEPARATOR);
                break;
            case API:
                sb.append(executionContext.getAttribute(ExecutionContext.ATTR_API)).append(KEY_SEPARATOR);
                break;
        }

        sb.append(request.path().hashCode()).append(KEY_SEPARATOR);

        String key = cachePolicyConfiguration.getKey();
        if (key != null && ! key.isEmpty()) {
            key = executionContext.getTemplateEngine().convert(key);
            sb.append(key);
        } else {
            // Remove latest separator
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }

    public long resolveTimeToLive(ClientResponse response) {
        long timeToLive = -1;
        if (cachePolicyConfiguration.isUseResponseCacheHeaders()) {
            timeToLive = timeToLiveFromResponse(response);
        }

        if (timeToLive != -1 && cachePolicyConfiguration.getTimeToLiveSeconds() < timeToLive) {
            timeToLive = cachePolicyConfiguration.getTimeToLiveSeconds();
        }

        return timeToLive;
    }

    public static long timeToLiveFromResponse(ClientResponse response) {
        long timeToLive = -1;
            CacheControl cacheControl = CacheControlUtil.parseCacheControl(response.headers().getFirst(HttpHeaders.CACHE_CONTROL));

            if (cacheControl != null && cacheControl.getSMaxAge() != -1) {
                timeToLive = cacheControl.getSMaxAge();
            } else if (cacheControl != null && cacheControl.getMaxAge() != -1) {
                timeToLive = cacheControl.getMaxAge();
            } else {
                Instant expiresAt = ExpiresUtil.parseExpires(response.headers().getFirst(HttpHeaders.EXPIRES));
                if (expiresAt != null) {
                    long expiresInSeconds = (expiresAt.toEpochMilli() - System.currentTimeMillis()) / 1000;
                    timeToLive = (expiresInSeconds < 0) ? -1 : expiresInSeconds;
                }
            }

        return timeToLive;
    }

    private CacheAction lookForAction(Request request) {
        // 1_ First, search in HTTP headers
        String cacheAction = request.headers().getFirst(X_GRAVITEE_CACHE_ACTION);

        if (cacheAction == null || cacheAction.isEmpty()) {
            // 2_ If not found, search in query parameters
            cacheAction = request.parameters().get(CACHE_ACTION_QUERY_PARAMETER);

            // Do not propagate specific query parameter
            request.parameters().remove(CACHE_ACTION_QUERY_PARAMETER);
        } else {
            // Do not propagate specific header
            request.headers().remove(X_GRAVITEE_CACHE_ACTION);
        }

        try {
            return (cacheAction != null) ? CacheAction.valueOf(cacheAction.toUpperCase()) : null;
        } catch (IllegalArgumentException iae) {
            return null;
        }
    }

    private enum CacheAction {
        REFRESH,
        BY_PASS
    }
}
