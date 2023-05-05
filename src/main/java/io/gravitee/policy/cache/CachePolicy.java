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

import com.fasterxml.jackson.core.JsonProcessingException;
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
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.configuration.SerializationMode;
import io.gravitee.policy.cache.mapper.CacheResponseMapper;
import io.gravitee.policy.cache.proxy.CacheProxyConnection;
import io.gravitee.policy.cache.proxy.EvaluableProxyResponse;
import io.gravitee.policy.cache.resource.CacheElement;
import io.gravitee.policy.cache.util.CacheControlUtil;
import io.gravitee.policy.cache.util.ExpiresUtil;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.gravitee.resource.cache.api.Element;
import io.vertx.core.AsyncResult;
import io.vertx.core.Vertx;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RequireResource
public class CachePolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachePolicy.class);

    private static final String CACHE_SERIALIZATION_MODE_KEY = "policy.cache.serialization";

    /**
     * Cache policy configuration
     */
    private final CachePolicyConfiguration cachePolicyConfiguration;

    public static final String UPSTREAM_RESPONSE = "upstreamResponse";
    // Policy cache action
    private static final String CACHE_ACTION_QUERY_PARAMETER = "cache";
    private static final String X_GRAVITEE_CACHE_ACTION = "X-Gravitee-Cache";

    private Cache cache;
    private CacheAction action;

    private CacheResponseMapper mapper = new CacheResponseMapper();

    public CachePolicy(final CachePolicyConfiguration cachePolicyConfiguration) {
        this.cachePolicyConfiguration = cachePolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        setMapperSerializationMode(executionContext);

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
                LOGGER.debug("Request {} is not a cached request, disable caching for it.", request.id());
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
            // Here we have to check if there is a value in cache
            String cacheId = hash(executionContext);
            LOGGER.debug("Looking for element in cache with the key {}", cacheId);

            Vertx vertx = executionContext.getComponent(Vertx.class);
            vertx.executeBlocking(
                promise -> {
                    Element elt = cache.get(cacheId);
                    promise.complete(elt);
                },
                new io.vertx.core.Handler<AsyncResult<Element>>() {
                    @Override
                    public void handle(AsyncResult<Element> elementAsyncResult) {
                        Element elt = elementAsyncResult.result();
                        if (elt != null && action != CacheAction.REFRESH) {
                            LOGGER.debug(
                                "An element has been found for key {}, returning the cached response to the initial client",
                                cacheId
                            );

                            try {
                                final ProxyConnection proxyConnection = new CacheProxyConnection(
                                    mapper.readValue(elt.value().toString(), CacheResponse.class)
                                );

                                // Ok, there is a value for this request in cache so send it through proxy connection
                                connectionHandler.handle(proxyConnection);

                                // Plug underlying stream to connection stream
                                stream.bodyHandler(proxyConnection::write).endHandler(aVoid -> proxyConnection.end());
                            } catch (JsonProcessingException e) {
                                LOGGER.error(
                                    "Cannot deserialize element with key {}, invoke backend with invoker {}",
                                    cacheId,
                                    invoker.getClass().getName()
                                );
                            }

                            // Resume the incoming request to handle content and end
                            executionContext.request().resume();
                        } else {
                            if (action == CacheAction.REFRESH) {
                                LOGGER.info(
                                    "A refresh action has been received for key {}, invoke backend with invoker {}",
                                    cacheId,
                                    invoker.getClass().getName()
                                );
                            } else {
                                LOGGER.debug(
                                    "No element for key {}, invoke backend with invoker {}",
                                    cacheId,
                                    invoker.getClass().getName()
                                );
                            }

                            // No value, let's do the default invocation and cache result in response
                            invoker.invoke(
                                executionContext,
                                stream,
                                proxyConnection -> {
                                    LOGGER.debug(
                                        "Put response in cache for key {} and request {}",
                                        cacheId,
                                        executionContext.request().id()
                                    );

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
                                }
                            );
                        }
                    }
                }
            );
        }
    }

    class CacheResponseHandler implements Handler<ProxyResponse> {

        private final String cacheId;
        private final Handler<ProxyResponse> responseHandler;
        private final CacheResponse response = new CacheResponse();
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
                LOGGER.debug(
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
                this.proxyResponse.bodyHandler(
                        chunk -> {
                            bodyHandler.handle(chunk);
                            content.appendBuffer(chunk);
                        }
                    );

                return this;
            }

            @Override
            public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
                this.proxyResponse.endHandler(
                        result -> {
                            endHandler.handle(result);
                            response.setStatus(proxyResponse.status());

                            io.gravitee.common.http.HttpHeaders headers = new io.gravitee.common.http.HttpHeaders();
                            proxyResponse.headers().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));
                            response.setHeaders(headers);
                            response.setContent(content);
                            Vertx vertx = executionContext.getComponent(Vertx.class);
                            vertx.executeBlocking(
                                promise -> {
                                    long timeToLive = -1;
                                    if (cachePolicyConfiguration.isUseResponseCacheHeaders()) {
                                        timeToLive = resolveTimeToLive(proxyResponse);
                                    }
                                    if (timeToLive == -1 || cachePolicyConfiguration.getTimeToLiveSeconds() < timeToLive) {
                                        timeToLive = cachePolicyConfiguration.getTimeToLiveSeconds();
                                    }

                                    try {
                                        CacheElement element = new CacheElement(cacheId, mapper.writeValueAsString(response));
                                        element.setTimeToLive((int) timeToLive);
                                        cache.put(element);
                                    } catch (JsonProcessingException e) {
                                        LOGGER.error("Cannot serialize element with key {}", cacheId);
                                    }
                                    promise.complete();
                                },
                                objectAsyncResult -> {}
                            );
                        }
                    );

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

    /**
     * Generate a unique identifier for the cache key.
     *
     * @param executionContext
     * @return
     */
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
            // Remove latest separator
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

    private enum CacheAction {
        REFRESH,
        BY_PASS,
    }

    private boolean isCachedMethod(HttpMethod method) {
        if (cachePolicyConfiguration.getMethods() == null || cachePolicyConfiguration.getMethods().isEmpty()) {
            //use Safe Methods
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
                LOGGER.error("Unable to evaluate the condition {}", e.getMessage(), e);
                return false;
            }
        }
        return true;
    }

    private void setMapperSerializationMode(ExecutionContext context) {
        if (mapper.isSerializationModeDefined()) {
            return;
        }

        Environment environment = context.getComponent(Environment.class);
        String serializationModeAsString = environment.getProperty(CACHE_SERIALIZATION_MODE_KEY, SerializationMode.TEXT.name());
        mapper.setSerializationMode(SerializationMode.valueOf(serializationModeAsString.toUpperCase()));
    }
}
