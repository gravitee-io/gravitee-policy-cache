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
package io.gravitee.policy.cache.invoker;

import static io.gravitee.policy.cache.util.ContentTypeUtil.hasBinaryContentType;
import static io.gravitee.policy.v3.cache.CachePolicyV3.UPSTREAM_RESPONSE;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.reactive.api.context.ContextAttributes;
import io.gravitee.gateway.reactive.api.context.ExecutionContext;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.HttpRequest;
import io.gravitee.gateway.reactive.api.context.Response;
import io.gravitee.gateway.reactive.api.invoker.Invoker;
import io.gravitee.policy.cache.CacheAction;
import io.gravitee.policy.cache.CacheControl;
import io.gravitee.policy.cache.CacheResponse;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.mapper.CacheResponseMapper;
import io.gravitee.policy.cache.resource.CacheElement;
import io.gravitee.policy.cache.util.CacheControlUtil;
import io.gravitee.policy.cache.util.ExpiresUtil;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheInvoker implements Invoker {

    public static final String CACHE_ENDPOINT_INVOKER_ID = "cache-endpoint-invoker";

    private final CachePolicyConfiguration cachePolicyConfiguration;
    private final Invoker delegateInvoker;
    private final Cache cache;
    private final CacheResponseMapper mapper;
    private final CacheAction action;

    public CacheInvoker(
        Invoker delegateInvoker,
        Cache cache,
        CacheAction action,
        CachePolicyConfiguration configuration,
        CacheResponseMapper mapper
    ) {
        this.cachePolicyConfiguration = configuration;
        this.delegateInvoker = delegateInvoker;
        this.mapper = mapper;
        this.cache = cache;
        this.action = action;
    }

    @Override
    public String getId() {
        return CACHE_ENDPOINT_INVOKER_ID;
    }

    @Override
    public Completable invoke(ExecutionContext executionContext) {
        // Here we have to check if there is a value in cache
        var cacheId = hash(executionContext);
        log.debug("Looking for element in cache with the key {}", cacheId);

        return Single
            .fromCallable(() -> Optional.ofNullable(cache.get(cacheId)))
            .subscribeOn(Schedulers.io())
            .flatMapCompletable(optElt -> {
                Response response = executionContext.response();
                if (optElt.isEmpty() || action == CacheAction.REFRESH) {
                    if (action == CacheAction.REFRESH) {
                        log.info(
                            "A refresh action has been received for key {}, invoke backend with invoker {}",
                            cacheId,
                            this.delegateInvoker.getClass().getName()
                        );
                    } else {
                        log.debug(
                            "No element for key {}, invoke backend with invoker {}",
                            cacheId,
                            this.delegateInvoker.getClass().getName()
                        );
                    }

                    return this.delegateInvoker.invoke(executionContext)
                        .andThen(storeInCacheEvaluation(executionContext, cacheId, response));
                } else {
                    log.debug("An element has been found for key {}, returning the cached response to the initial client", cacheId);

                    var elt = optElt.get();
                    try {
                        var cacheResponse = mapper.readValue(elt.value().toString(), CacheResponse.class);
                        response.status(cacheResponse.getStatus());
                        if (cacheResponse.getHeaders() != null) {
                            cacheResponse
                                .getHeaders()
                                .forEach((key, values) -> values.forEach(value -> response.headers().add(key, value)));
                        }
                        Buffer content = hasBinaryContentType(cacheResponse.getHeaders())
                            ? Buffer.buffer(Base64.getDecoder().decode(cacheResponse.getContent().getBytes()))
                            : cacheResponse.getContent();
                        return response.onBody(body -> body.ignoreElement().andThen(Maybe.just(content)));
                    } catch (JsonProcessingException e) {
                        log.warn(
                            "Cannot deserialize element with key {}, invoke backend with invoker {}",
                            cacheId,
                            delegateInvoker.getClass().getName()
                        );
                        evictFromCache(cacheId);
                        return this.delegateInvoker.invoke(executionContext);
                    }
                }
            });
    }

    private Completable storeInCacheEvaluation(ExecutionContext executionContext, String cacheId, Response response) {
        return Completable.defer(() -> {
            if (evaluate(executionContext, response, cachePolicyConfiguration.getResponseCondition())) {
                final var httpHeaders = new HttpHeaders();
                response.headers().forEach(entry -> httpHeaders.add(entry.getKey(), entry.getValue()));
                final var status = response.status();
                return response.onBody(body -> body.doOnSuccess(buffer -> storeInCache(cacheId, httpHeaders, status, buffer)));
            } else {
                log.debug(
                    "Response for key {} not put in cache because of the status code {} or the condition",
                    cacheId,
                    response.status()
                );
                return response.onBody(body -> body);
            }
        });
    }

    private boolean evaluate(final ExecutionContext context, final Response response, final String condition) {
        if (condition != null && !condition.isEmpty()) {
            try {
                context.getTemplateEngine().getTemplateContext().setVariable(UPSTREAM_RESPONSE, response);
                return context.getTemplateEngine().getValue(condition, Boolean.class);
            } catch (Exception e) {
                log.error("Unable to evaluate the condition {}", e.getMessage(), e);
                return false;
            }
        }
        return is2xx(response);
    }

    private boolean is2xx(final Response response) {
        return response.status() >= HttpStatusCode.OK_200 && response.status() < HttpStatusCode.MULTIPLE_CHOICES_300;
    }

    private void evictFromCache(String cacheId) {
        Completable
            .fromAction(() -> cache.evict(cacheId))
            .subscribeOn(Schedulers.io())
            .doOnComplete(() -> log.debug("Element {} evicted from the cache {}", cacheId, cache.getName()))
            .onErrorResumeNext(err -> {
                log.warn("Element {} can't be evicted from the cache {}", cacheId, cache.getName(), err);
                return Completable.complete();
            })
            .subscribe();
    }

    private void storeInCache(String cacheId, HttpHeaders httpHeaders, int status, Buffer buffer) {
        Completable
            .fromAction(() -> {
                final var resp = new CacheResponse();
                Buffer content = hasBinaryContentType(httpHeaders) ? Buffer.buffer(Base64.getEncoder().encode(buffer.getBytes())) : buffer;
                resp.setContent(content);
                resp.setStatus(status);
                resp.setHeaders(httpHeaders);

                long timeToLive = resolveTimeToLive(httpHeaders);
                CacheElement element = new CacheElement(cacheId, mapper.writeValueAsString(resp));
                element.setTimeToLive((int) timeToLive);
                cache.put(element);
            })
            .subscribeOn(Schedulers.io())
            .doOnComplete(() -> log.debug("Element {} stored into the cache {}", cacheId, cache.getName()))
            .onErrorResumeNext(err -> {
                log.warn("Element {} can't be stored into the cache {}", cacheId, cache.getName(), err);
                return Completable.complete();
            })
            .subscribe();
    }

    /**
     * Generate a unique identifier for the cache key.
     *
     * @param executionContext
     * @return
     */
    String hash(HttpExecutionContext executionContext) {
        StringBuilder sb = new StringBuilder();
        String cacheName = cachePolicyConfiguration.getCacheName();
        CacheResource<?> cacheResource = executionContext.getComponent(ResourceManager.class).getResource(cacheName, CacheResource.class);
        String keySeparator = cacheResource.keySeparator();

        switch (cachePolicyConfiguration.getScope()) {
            case APPLICATION:
                sb.append((String) executionContext.getAttribute(ContextAttributes.ATTR_API)).append(keySeparator);
                sb.append((String) executionContext.getAttribute(ContextAttributes.ATTR_APPLICATION)).append(keySeparator);
                break;
            case API:
                sb.append((String) executionContext.getAttribute(ContextAttributes.ATTR_API)).append(keySeparator);
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

    private int buildParametersKeyComponent(HttpRequest request) {
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

    public long resolveTimeToLive(HttpHeaders httpHeaders) {
        long timeToLive = -1;
        if (cachePolicyConfiguration.isUseResponseCacheHeaders()) {
            timeToLive = timeToLiveFromResponse(httpHeaders);
        }

        if (timeToLive == -1 || cachePolicyConfiguration.getTimeToLiveSeconds() < timeToLive) {
            timeToLive = cachePolicyConfiguration.getTimeToLiveSeconds();
        }

        return timeToLive;
    }

    public long timeToLiveFromResponse(HttpHeaders httpHeaders) {
        long timeToLive = -1;
        String cacheControlHeader = Optional
            .ofNullable(httpHeaders.get(HttpHeaderNames.CACHE_CONTROL))
            .map(list -> list.get(0))
            .orElse(null);
        CacheControl cacheControl = CacheControlUtil.parseCacheControl(cacheControlHeader);

        if (cacheControl != null && cacheControl.getSMaxAge() != -1) {
            timeToLive = cacheControl.getSMaxAge();
        } else if (cacheControl != null && cacheControl.getMaxAge() != -1) {
            timeToLive = cacheControl.getMaxAge();
        } else {
            String expiresHeader = Optional.ofNullable(httpHeaders.get(HttpHeaderNames.EXPIRES)).map(list -> list.get(0)).orElse(null);
            Instant expiresAt = ExpiresUtil.parseExpires(expiresHeader);
            if (expiresAt != null) {
                long expiresInSeconds = (expiresAt.toEpochMilli() - System.currentTimeMillis()) / 1000;
                timeToLive = (expiresInSeconds < 0) ? -1 : expiresInSeconds;
            }
        }

        return timeToLive;
    }
}
