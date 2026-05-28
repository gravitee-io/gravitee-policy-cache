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

import static io.gravitee.policy.v3.cache.CachePolicyV3.UPSTREAM_RESPONSE;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.reactive.api.context.*;
import io.gravitee.gateway.reactive.api.invoker.Invoker;
import io.gravitee.policy.cache.CacheAction;
import io.gravitee.policy.cache.CacheControl;
import io.gravitee.policy.cache.CachedResponse;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.frame.CacheFrame;
import io.gravitee.policy.cache.resource.CacheElement;
import io.gravitee.policy.cache.util.CacheControlUtil;
import io.gravitee.policy.cache.util.ExpiresUtil;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheInvoker implements Invoker {

    public static final String CACHE_ENDPOINT_INVOKER_ID = "cache-endpoint-invoker";

    private final CachePolicyConfiguration cachePolicyConfiguration;
    private final Invoker delegateInvoker;
    private final Cache cache;
    private final CacheAction action;

    public CacheInvoker(Invoker delegateInvoker, Cache cache, CacheAction action, CachePolicyConfiguration configuration) {
        this.cachePolicyConfiguration = configuration;
        this.delegateInvoker = delegateInvoker;
        this.cache = cache;
        this.action = action;
    }

    @Override
    public String getId() {
        return CACHE_ENDPOINT_INVOKER_ID;
    }

    @Override
    public Completable invoke(ExecutionContext executionContext) {
        var cacheId = hash(executionContext);
        log.debug("Looking for element in cache with the key {}", cacheId);

        return Single.fromCompletionStage(cache.getBinaryAsync(cacheId).map(Optional::ofNullable).toCompletionStage()).flatMapCompletable(
            optElt -> {
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

                    return this.delegateInvoker.invoke(executionContext).andThen(
                        storeInCacheEvaluation(executionContext, cacheId, response)
                    );
                }

                byte[] frame = CacheFrame.asFrame(optElt.get().value());
                if (frame == null) {
                    log.debug("Cache entry for key {} has unrecognized value type, evicting and refetching", cacheId);
                    evictFromCache(cacheId);
                    return this.delegateInvoker.invoke(executionContext).andThen(
                        storeInCacheEvaluation(executionContext, cacheId, response)
                    );
                }

                if (CacheFrame.isLegacyFormat(frame)) {
                    // During a rolling upgrade from gravitee-policy-cache <= 4.0.0-alpha.2, the cache
                    // may contain legacy JSON entries written by old gateway instances. Serve them as
                    // a regular cache hit (no evict, no rewrite) to avoid thundering-herd refetches
                    // against the backend. Entries naturally migrate to the binary format on TTL
                    // expiry. See APIM-13628.
                    try {
                        CachedResponse cached = CacheFrame.decodeLegacy(frame);
                        response.status(cached.status());
                        cached.headers().forEach((key, values) -> values.forEach(value -> response.headers().add(key, value)));
                        log.debug("Serving legacy-format cache entry for key {} (read-only; entry will not be rewritten)", cacheId);
                        return response.onBody(body -> body.ignoreElement().andThen(Maybe.just(cached.body())));
                    } catch (Exception e) {
                        log.warn("Cannot decode legacy cache entry for key {}, evicting and refetching", cacheId, e);
                        evictFromCache(cacheId);
                        return this.delegateInvoker.invoke(executionContext).andThen(
                            storeInCacheEvaluation(executionContext, cacheId, response)
                        );
                    }
                }

                try {
                    CachedResponse cached = CacheFrame.decode(frame);
                    response.status(cached.status());
                    cached.headers().forEach((key, values) -> values.forEach(value -> response.headers().add(key, value)));
                    log.debug("An element has been found for key {}, returning the cached response to the initial client", cacheId);
                    return response.onBody(body -> body.ignoreElement().andThen(Maybe.just(cached.body())));
                } catch (Exception e) {
                    log.warn("Cannot decode cache frame for key {}, evicting and refetching", cacheId, e);
                    evictFromCache(cacheId);
                    return this.delegateInvoker.invoke(executionContext).andThen(
                        storeInCacheEvaluation(executionContext, cacheId, response)
                    );
                }
            }
        );
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
        Completable.fromCompletionStage(cache.evictAsync(cacheId).toCompletionStage())
            .doOnComplete(() -> log.debug("Element {} evicted from the cache {}", cacheId, cache.getName()))
            .onErrorResumeNext(err -> {
                log.warn("Element {} can't be evicted from the cache {}", cacheId, cache.getName(), err);
                return Completable.complete();
            })
            .subscribe();
    }

    private void storeInCache(String cacheId, HttpHeaders httpHeaders, int status, Buffer buffer) {
        byte[] frame = CacheFrame.encode(new CachedResponse(status, httpHeaders, buffer));
        CacheElement element = new CacheElement(cacheId, frame);
        element.setTimeToLive((int) resolveTimeToLive(httpHeaders));

        Completable.fromCompletionStage(cache.putBinaryAsync(element).toCompletionStage())
            .doOnComplete(() -> log.debug("Element {} stored into the cache {}", cacheId, cache.getName()))
            .onErrorResumeNext(err -> {
                log.warn("Element {} can't be stored into the cache {}", cacheId, cache.getName(), err);
                return Completable.complete();
            })
            .subscribe();
    }

    /**
     * Generate a unique identifier for the cache key.
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
        String cacheControlHeader = Optional.ofNullable(httpHeaders.get(HttpHeaderNames.CACHE_CONTROL))
            .map(list -> list.get(0))
            .orElse(null);
        CacheControl cacheControl = CacheControlUtil.parseCacheControl(cacheControlHeader);

        if (cacheControl != null && cacheControl.getSMaxAge() != -1) {
            timeToLive = cacheControl.getSMaxAge();
        } else if (cacheControl != null && cacheControl.getMaxAge() != -1) {
            timeToLive = cacheControl.getMaxAge();
        } else {
            String expiresHeader = Optional.ofNullable(httpHeaders.get(HttpHeaderNames.EXPIRES))
                .map(list -> list.get(0))
                .orElse(null);
            Instant expiresAt = ExpiresUtil.parseExpires(expiresHeader);
            if (expiresAt != null) {
                long expiresInSeconds = (expiresAt.toEpochMilli() - System.currentTimeMillis()) / 1000;
                timeToLive = (expiresInSeconds < 0) ? -1 : expiresInSeconds;
            }
        }

        return timeToLive;
    }
}
