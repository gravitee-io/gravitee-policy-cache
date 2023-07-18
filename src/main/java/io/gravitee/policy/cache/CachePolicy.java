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
package io.gravitee.policy.cache;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.HttpRequest;
import io.gravitee.gateway.reactive.api.context.InternalContextAttributes;
import io.gravitee.gateway.reactive.api.context.MessageExecutionContext;
import io.gravitee.gateway.reactive.api.invoker.Invoker;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.gateway.reactive.core.context.interruption.InterruptionFailureException;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.configuration.SerializationMode;
import io.gravitee.policy.cache.invoker.CacheInvoker;
import io.gravitee.policy.v3.cache.CachePolicyV3;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.CacheResource;
import io.reactivex.rxjava3.core.Completable;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

@Slf4j
public class CachePolicy extends CachePolicyV3 implements Policy {

    public static final String PLUGIN_ID = "cache";

    public CachePolicy(CachePolicyConfiguration cachePolicyConfiguration) {
        super(cachePolicyConfiguration);
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public Completable onRequest(HttpExecutionContext ctx) {
        setMapperSerializationMode(ctx);

        action = lookForAction(ctx.request());

        if (action != CacheAction.BY_PASS) {
            if (isCachedMethod(ctx.request().method())) {
                String cacheName = cachePolicyConfiguration.getCacheName();
                CacheResource<?> cacheResource = ctx.getComponent(ResourceManager.class).getResource(cacheName, CacheResource.class);

                if (cacheResource == null) {
                    return ctx.interruptWith(
                        new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500)
                            .message("No cache has been defined with name " + cacheName)
                    );
                }

                cache = cacheResource.getCache(ctx);
                if (cache == null) {
                    return ctx.interruptWith(
                        new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500)
                            .message("No cache named [ " + cacheName + " ] has been found.")
                    );
                }

                // Override the invoker
                Invoker defaultInvoker = ctx.getInternalAttribute(InternalContextAttributes.ATTR_INTERNAL_INVOKER);
                ctx.setInternalAttribute(
                    InternalContextAttributes.ATTR_INTERNAL_INVOKER,
                    new CacheInvoker(defaultInvoker, cache, action, cachePolicyConfiguration, mapper)
                );
            } else {
                log.debug("Request {} is not a cached request, disable caching for it.", ctx.request().id());
            }
        }

        return Completable.complete();
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return Completable.error(new UnsupportedOperationException("onResponse method is not supported by cache policy"));
    }

    @Override
    public Completable onMessageRequest(MessageExecutionContext ctx) {
        return Completable.error(new UnsupportedOperationException("onMessageRequest method is not supported by cache policy"));
    }

    @Override
    public Completable onMessageResponse(MessageExecutionContext ctx) {
        return Completable.error(new UnsupportedOperationException("onMessageResponse method is not supported by cache policy"));
    }

    private void setMapperSerializationMode(HttpExecutionContext context) {
        if (mapper.isSerializationModeDefined()) {
            return;
        }

        Environment environment = context.getComponent(Environment.class);
        String serializationModeAsString = environment.getProperty(CACHE_SERIALIZATION_MODE_KEY, SerializationMode.TEXT.name());
        mapper.setSerializationMode(SerializationMode.valueOf(serializationModeAsString.toUpperCase()));
    }

    protected CacheAction lookForAction(HttpRequest request) {
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
}
