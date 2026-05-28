/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.v3.cache;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Invoker;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.gateway.api.stream.ReadStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.RequireResource;
import io.gravitee.policy.cache.CacheAction;
import io.gravitee.policy.cache.CacheControl;
import io.gravitee.policy.cache.CachedResponse;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.frame.CacheFrame;
import io.gravitee.policy.cache.resource.CacheElement;
import io.gravitee.policy.cache.util.CacheControlUtil;
import io.gravitee.policy.cache.util.ExpiresUtil;
import io.gravitee.policy.v3.cache.proxy.CacheProxyConnection;
import io.gravitee.policy.v3.cache.proxy.EvaluableProxyResponse;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.gravitee.resource.cache.api.Element;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RequireResource
@Slf4j
public class CachePolicyV3 {

    protected final CachePolicyConfiguration cachePolicyConfiguration;

    public static final String UPSTREAM_RESPONSE = "upstreamResponse";
    // Policy cache action
    public static final String CACHE_ACTION_QUERY_PARAMETER = "cache";
    public static final String X_GRAVITEE_CACHE_ACTION = "X-Gravitee-Cache";

    protected Cache cache;
    protected CacheAction action;

    public CachePolicyV3(final CachePolicyConfiguration cachePolicyConfiguration) {
        this.cachePolicyConfiguration = cachePolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        action = lookForAction(request);

        if (action != CacheAction.BY_PASS) {
            if (isCachedMethod(request.method())) {
                // It's safe to do so because a new instance of policy is created for each request.
                String cacheName = cachePolicyConfiguration.getCacheName();
                CacheResource<?> cacheResource = executionContext
                    .getComponent(ResourceManager.class)
                    .getResource(cacheName, CacheResource.class);
                if (cacheResource == null) {
                    policyChain.failWith(PolicyResult.failure("No cache has been defined with name " + cacheName));
                    return;
                }

                cache = cacheResource.getCache(executionContext);
                if (cache == null) {
                    policyChain.failWith(PolicyResult.failure("No cache named [ " + cacheName + " ] has been found."));
                    return;
                }

                // Override the invoker
                Invoker defaultInvoker = (Invoker) executionContext.getAttribute(ExecutionContext.ATTR_INVOKER);
                executionContext.setAttribute(ExecutionContext.ATTR_INVOKER, new CacheInvoker(defaultInvoker));
            } else {
                log.debug("Request {} is not a cached request, disable caching for it.", request.id());
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
        public void invoke(ExecutionContext executionContext, ReadStream<Buffer> stream, Handler<ProxyConnection> connectionHandler) {
            String cacheId = hash(executionContext);
            log.debug("Looking for element in cache with the key {}", cacheId);

            cache
                .getBinaryAsync(cacheId)
                .onComplete(elementAsyncResult -> {
                    Element elt = elementAsyncResult.result();
                    byte[] frame = elt == null ? null : CacheFrame.asFrame(elt.value());

                    if (frame != null && action != CacheAction.REFRESH) {
                        // Try serving from cache. Legacy entries (JSON from policy <= 4.0.0-alpha.2)
                        // are served read-only to avoid thundering-herd refetches during rolling
                        // upgrades on shared Redis. See APIM-13628.
                        boolean legacy = CacheFrame.isLegacyFormat(frame);
                        try {
                            CachedResponse cached = legacy ? CacheFrame.decodeLegacy(frame) : CacheFrame.decode(frame);
                            ProxyConnection proxyConnection = new CacheProxyConnection(cached);
                            if (legacy) {
                                log.debug("Serving legacy-format cache entry for key {} (read-only; entry will not be rewritten)", cacheId);
                            } else {
                                log.debug(
                                    "An element has been found for key {}, returning the cached response to the initial client",
                                    cacheId
                                );
                            }
                            connectionHandler.handle(proxyConnection);
                            stream.bodyHandler(proxyConnection::write).endHandler(aVoid -> proxyConnection.end());
                            executionContext.request().resume();
                            return;
                        } catch (Exception e) {
                            log.warn(
                                "Cannot decode {} cache entry for key {}, evicting and refetching",
                                legacy ? "legacy" : "frame",
                                cacheId,
                                e
                            );
                            evictFromCache(cacheId);
                        }
                    } else if (elt != null && action == CacheAction.REFRESH) {
                        log.info(
                            "A refresh action has been received for key {}, invoke backend with invoker {}",
                            cacheId,
                            invoker.getClass().getName()
                        );
                    } else if (frame == null && elt != null) {
                        log.debug("Cache entry for key {} has unrecognized value type, evicting and refetching", cacheId);
                        evictFromCache(cacheId);
                    } else {
                        log.debug("No element for key {}, invoke backend with invoker {}", cacheId, invoker.getClass().getName());
                    }

                    // No usable cached value: invoke backend and store the response in cache.
                    invoker.invoke(executionContext, stream, proxyConnection -> {
                        log.debug("Put response in cache for key {} and request {}", cacheId, executionContext.request().id());

                        ProxyConnection cacheProxyConnection = new ProxyConnection() {
                            @Override
                            public ProxyConnection write(Buffer buffer) {
                                proxyConnection.write(buffer);
                                return this;
                            }

                            @Override
                            public void end() {
                                proxyConnection.end();
                            }

                            @Override
                            public ProxyConnection responseHandler(Handler<ProxyResponse> responseHandler) {
                                return proxyConnection.responseHandler(
                                    new CacheResponseHandler(cacheId, responseHandler, executionContext)
                                );
                            }
                        };

                        connectionHandler.handle(cacheProxyConnection);
                    });
                });
        }
    }

    private void evictFromCache(String cacheId) {
        cache
            .evictAsync(cacheId)
            .onFailure(err -> log.warn("Element {} can't be evicted from the cache {}", cacheId, cache.getName(), err));
    }

    class CacheResponseHandler implements Handler<ProxyResponse> {

        private final String cacheId;
        private final Handler<ProxyResponse> responseHandler;
        private final ExecutionContext executionContext;

        CacheResponseHandler(final String cacheId, final Handler<ProxyResponse> responseHandler, ExecutionContext executionContext) {
            this.cacheId = cacheId;
            this.responseHandler = responseHandler;
            this.executionContext = executionContext;
        }

        @Override
        public void handle(ProxyResponse proxyResponse) {
            if (
                cachePolicyConfiguration.getResponseCondition() != null &&
                evaluate(executionContext, proxyResponse, cachePolicyConfiguration.getResponseCondition())
            ) {
                responseHandler.handle(new CacheProxyResponse(proxyResponse, cacheId));
            } else if (
                cachePolicyConfiguration.getResponseCondition() == null &&
                proxyResponse.status() >= HttpStatusCode.OK_200 &&
                proxyResponse.status() < HttpStatusCode.MULTIPLE_CHOICES_300
            ) {
                responseHandler.handle(new CacheProxyResponse(proxyResponse, cacheId));
            } else {
                log.debug(
                    "Response for key {} not put in cache because of the status code {} or the condition",
                    cacheId,
                    proxyResponse.status()
                );
                responseHandler.handle(proxyResponse);
            }
        }

        class CacheProxyResponse implements ProxyResponse {

            private final ProxyResponse proxyResponse;
            private final String cacheId;

            final Buffer content = Buffer.buffer();

            CacheProxyResponse(final ProxyResponse proxyResponse, final String cacheId) {
                this.proxyResponse = proxyResponse;
                this.cacheId = cacheId;
            }

            @Override
            public ReadStream<Buffer> bodyHandler(Handler<Buffer> bodyHandler) {
                this.proxyResponse.bodyHandler(chunk -> {
                    bodyHandler.handle(chunk);
                    content.appendBuffer(chunk);
                });
                return this;
            }

            @Override
            public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
                this.proxyResponse.endHandler(result -> {
                    endHandler.handle(result);

                    io.gravitee.common.http.HttpHeaders headers = new io.gravitee.common.http.HttpHeaders();
                    proxyResponse.headers().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));

                    long timeToLive = -1;
                    if (cachePolicyConfiguration.isUseResponseCacheHeaders()) {
                        timeToLive = resolveTimeToLive(proxyResponse);
                    }
                    if (timeToLive == -1 || cachePolicyConfiguration.getTimeToLiveSeconds() < timeToLive) {
                        timeToLive = cachePolicyConfiguration.getTimeToLiveSeconds();
                    }
                    final int ttl = (int) timeToLive;

                    byte[] frame = CacheFrame.encode(new CachedResponse(proxyResponse.status(), headers, content));
                    CacheElement element = new CacheElement(cacheId, frame);
                    element.setTimeToLive(ttl);

                    cache
                        .putBinaryAsync(element)
                        .onFailure(err -> log.warn("Cannot store element with key {} into the cache", cacheId, err));
                });

                return this;
            }

            @Override
            public ReadStream<Buffer> pause() {
                return proxyResponse.pause();
            }

            @Override
            public ReadStream<Buffer> resume() {
                return proxyResponse.resume();
            }

            @Override
            public int status() {
                return proxyResponse.status();
            }

            @Override
            public HttpHeaders headers() {
                return proxyResponse.headers();
            }
        }
    }

    String hash(ExecutionContext executionContext) {
        StringBuilder sb = new StringBuilder();
        String cacheName = cachePolicyConfiguration.getCacheName();
        CacheResource<?> cacheResource = executionContext.getComponent(ResourceManager.class).getResource(cacheName, CacheResource.class);
        String keySeparator = cacheResource.keySeparator();

        switch (cachePolicyConfiguration.getScope()) {
            case APPLICATION:
                sb.append(executionContext.getAttribute(ExecutionContext.ATTR_API)).append(keySeparator);
                sb.append(executionContext.getAttribute(ExecutionContext.ATTR_APPLICATION)).append(keySeparator);
                break;
            case API:
                sb.append(executionContext.getAttribute(ExecutionContext.ATTR_API)).append(keySeparator);
                break;
        }

        sb.append(executionContext.request().path().hashCode()).append(keySeparator);
        sb.append(buildParametersKeyComponent(executionContext.request())).append(keySeparator);

        String key = cachePolicyConfiguration.getKey();
        if (key != null && !key.isEmpty()) {
            key = executionContext.getTemplateEngine().convert(key);
            sb.append(key);
        } else {
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }

    private int buildParametersKeyComponent(Request request) {
        return request
            .parameters()
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .peek(entry -> Collections.sort(entry.getValue()))
            .map(Map.Entry::toString)
            .collect(Collectors.joining())
            .hashCode();
    }

    public long resolveTimeToLive(ProxyResponse response) {
        long timeToLive = -1;
        if (cachePolicyConfiguration.isUseResponseCacheHeaders()) {
            timeToLive = timeToLiveFromResponse(response);
        }

        if (timeToLive != -1 && cachePolicyConfiguration.getTimeToLiveSeconds() < timeToLive) {
            timeToLive = cachePolicyConfiguration.getTimeToLiveSeconds();
        }

        return timeToLive;
    }

    public static long timeToLiveFromResponse(ProxyResponse response) {
        long timeToLive = -1;
        CacheControl cacheControl = CacheControlUtil.parseCacheControl(response.headers().getFirst(HttpHeaderNames.CACHE_CONTROL));

        if (cacheControl != null && cacheControl.getSMaxAge() != -1) {
            timeToLive = cacheControl.getSMaxAge();
        } else if (cacheControl != null && cacheControl.getMaxAge() != -1) {
            timeToLive = cacheControl.getMaxAge();
        } else {
            Instant expiresAt = ExpiresUtil.parseExpires(response.headers().getFirst(HttpHeaderNames.EXPIRES));
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
            cacheAction = request.parameters().getFirst(CACHE_ACTION_QUERY_PARAMETER);

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

    protected boolean isCachedMethod(HttpMethod method) {
        if (cachePolicyConfiguration.getMethods() == null || cachePolicyConfiguration.getMethods().isEmpty()) {
            return (method == HttpMethod.GET || method == HttpMethod.OPTIONS || method == HttpMethod.HEAD);
        }
        return cachePolicyConfiguration.getMethods().contains(method);
    }

    private boolean evaluate(final ExecutionContext context, final ProxyResponse proxyResponse, final String condition) {
        if (condition != null && !condition.isEmpty()) {
            try {
                context.getTemplateEngine().getTemplateContext().setVariable(UPSTREAM_RESPONSE, new EvaluableProxyResponse(proxyResponse));
                return context.getTemplateEngine().getValue(condition, Boolean.class);
            } catch (Exception e) {
                log.error("Unable to evaluate the condition {}", e.getMessage(), e);
                return false;
            }
        }
        return true;
    }
}
