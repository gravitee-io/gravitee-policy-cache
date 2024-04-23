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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.util.LinkedMultiValueMap;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.HttpRequest;
import io.gravitee.gateway.reactive.api.context.InternalContextAttributes;
import io.gravitee.gateway.reactive.api.context.MessageExecutionContext;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.invoker.CacheInvoker;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@RunWith(MockitoJUnitRunner.class)
public class CachePolicyTest {

    @Mock
    private HttpExecutionContext httpExecutionContext;

    @Mock
    private CachePolicyConfiguration cachePolicyConfiguration;

    @Mock
    protected HttpRequest request;

    @Before
    public void init() {
        MockitoAnnotations.openMocks(this);
        when(httpExecutionContext.getComponent(Environment.class)).thenReturn(new MockEnvironment());
    }

    @Test
    public void shouldFailIfNoCacheResource() {
        when(request.headers()).thenReturn(io.gravitee.gateway.api.http.HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(httpExecutionContext.request()).thenReturn(request);
        when(cachePolicyConfiguration.getCacheName()).thenReturn("cache");
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        when(httpExecutionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource("cache", CacheResource.class)).thenReturn(cr);
        when(cr.getCache(httpExecutionContext)).thenReturn(null);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(httpExecutionContext);

        verify(httpExecutionContext, times(1)).interruptWith(any());
    }

    @Test
    public void shouldUseCacheOnGETByDefault() {
        when(request.headers()).thenReturn(io.gravitee.gateway.api.http.HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(httpExecutionContext.request()).thenReturn(request);
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        Cache cache = mock(Cache.class);
        when(httpExecutionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource(any(), eq(CacheResource.class))).thenReturn(cr);
        when(cr.getCache(httpExecutionContext)).thenReturn(cache);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(httpExecutionContext);

        verify(httpExecutionContext, never()).interruptWith(any());
        verify(httpExecutionContext, times(1))
            .setInternalAttribute(eq(InternalContextAttributes.ATTR_INTERNAL_INVOKER), any(CacheInvoker.class));
    }

    @Test
    public void shouldNotUseCacheOnPOSTByDefault() {
        when(request.headers()).thenReturn(io.gravitee.gateway.api.http.HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.POST);
        when(httpExecutionContext.request()).thenReturn(request);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(httpExecutionContext);

        verify(httpExecutionContext, never()).interruptWith(any());
        verify(httpExecutionContext, never())
            .setInternalAttribute(eq(InternalContextAttributes.ATTR_INTERNAL_INVOKER), any(CacheInvoker.class));
    }

    @Test
    public void shouldUseCacheOnPOST() {
        when(request.headers()).thenReturn(io.gravitee.gateway.api.http.HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.POST);
        when(httpExecutionContext.request()).thenReturn(request);

        when(cachePolicyConfiguration.getMethods()).thenReturn(Collections.singletonList(HttpMethod.POST));
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        Cache cache = mock(Cache.class);
        when(httpExecutionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource(any(), eq(CacheResource.class))).thenReturn(cr);
        when(cr.getCache(httpExecutionContext)).thenReturn(cache);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(httpExecutionContext);

        verify(httpExecutionContext, never()).interruptWith(any());
        verify(httpExecutionContext, times(1))
            .setInternalAttribute(eq(InternalContextAttributes.ATTR_INTERNAL_INVOKER), any(CacheInvoker.class));
    }

    @Test
    public void shouldNotUseCacheOnGET() {
        when(request.headers()).thenReturn(io.gravitee.gateway.api.http.HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(httpExecutionContext.request()).thenReturn(request);
        when(cachePolicyConfiguration.getMethods()).thenReturn(Collections.singletonList(HttpMethod.POST));

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(httpExecutionContext);

        verify(httpExecutionContext, never()).interruptWith(any());
        verify(httpExecutionContext, never())
            .setInternalAttribute(eq(InternalContextAttributes.ATTR_INTERNAL_INVOKER), any(CacheInvoker.class));
    }

    @Test
    public void shouldRejectResponsePhase() throws InterruptedException {
        CachePolicy cachePolicy = new CachePolicy(new CachePolicyConfiguration());
        cachePolicy.onResponse(mock(HttpExecutionContext.class)).test().await().assertError(UnsupportedOperationException.class);
    }

    @Test
    public void shouldRejectMessageRequestPhase() throws InterruptedException {
        CachePolicy cachePolicy = new CachePolicy(new CachePolicyConfiguration());
        cachePolicy.onMessageRequest(mock(MessageExecutionContext.class)).test().await().assertFailure(UnsupportedOperationException.class);
    }

    @Test
    public void shouldRejectMessageResponsePhase() throws InterruptedException {
        CachePolicy cachePolicy = new CachePolicy(new CachePolicyConfiguration());
        cachePolicy
            .onMessageResponse(mock(MessageExecutionContext.class))
            .test()
            .await()
            .assertFailure(UnsupportedOperationException.class);
    }
}
