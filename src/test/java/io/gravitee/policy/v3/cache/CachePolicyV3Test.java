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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.util.LinkedMultiValueMap;
import io.gravitee.common.util.MultiValueMap;
import io.gravitee.el.TemplateContext;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.configuration.CacheScope;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class CachePolicyV3Test {

    @Mock
    protected ExecutionContext executionContext;

    @Mock
    protected PolicyChain policyChain;

    @Mock
    protected Response response;

    @Mock
    protected Request request;

    @Mock
    protected ProxyResponse proxyResponse;

    @Mock
    protected CachePolicyConfiguration cachePolicyConfiguration;

    @Before
    public void init() {
        MockitoAnnotations.openMocks(this);
        when(executionContext.getComponent(Environment.class)).thenReturn(new MockEnvironment());
    }

    @Test
    public void should_usecachecontrol_smaxage() {
        final HttpHeaders headers = HttpHeaders
            .create()
            .add(HttpHeaderNames.CACHE_CONTROL, "max-age=600, no-cache, no-store, smax-age=300")
            .add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        when(proxyResponse.headers()).thenReturn(headers);

        long timeToLive = CachePolicyV3.timeToLiveFromResponse(proxyResponse);
        Assert.assertEquals(300, timeToLive);
    }

    @Test
    public void should_usecachecontrol_maxage() {
        final HttpHeaders headers = HttpHeaders
            .create()
            .add(HttpHeaderNames.CACHE_CONTROL, "max-age=600, no-cache, no-store")
            .add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        when(proxyResponse.headers()).thenReturn(headers);

        long timeToLive = CachePolicyV3.timeToLiveFromResponse(proxyResponse);
        Assert.assertEquals(600, timeToLive);
    }

    @Test
    public void should_usecachecontrol_expires_past() {
        final HttpHeaders headers = HttpHeaders
            .create()
            .add(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store")
            .add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        when(proxyResponse.headers()).thenReturn(headers);

        long timeToLive = CachePolicyV3.timeToLiveFromResponse(proxyResponse);
        Assert.assertEquals(-1, timeToLive);
    }

    @Test
    public void shouldFailIfNoCacheResource() {
        when(request.headers()).thenReturn(HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(cachePolicyConfiguration.getCacheName()).thenReturn("cache");
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource("cache", CacheResource.class)).thenReturn(cr);
        when(cr.getCache(executionContext)).thenReturn(null);

        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);
        cachePolicyV3.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).failWith(any());
    }

    @Test
    public void shouldUseCacheOnGETByDefault() {
        when(request.headers()).thenReturn(HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        Cache cache = mock(Cache.class);
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource(any(), eq(CacheResource.class))).thenReturn(cr);
        when(cr.getCache(executionContext)).thenReturn(cache);

        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);
        cachePolicyV3.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, times(1)).setAttribute(eq(ExecutionContext.ATTR_INVOKER), any(CachePolicyV3.CacheInvoker.class));
    }

    @Test
    public void shouldNotUseCacheOnPOSTByDefault() {
        when(request.headers()).thenReturn(HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.POST);

        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);
        cachePolicyV3.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, never()).setAttribute(eq(ExecutionContext.ATTR_INVOKER), any(CachePolicyV3.CacheInvoker.class));
    }

    @Test
    public void shouldUseCacheOnPOST() {
        when(request.headers()).thenReturn(HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.POST);
        when(cachePolicyConfiguration.getMethods()).thenReturn(Collections.singletonList(HttpMethod.POST));
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        Cache cache = mock(Cache.class);
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource(any(), eq(CacheResource.class))).thenReturn(cr);
        when(cr.getCache(executionContext)).thenReturn(cache);

        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);
        cachePolicyV3.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, times(1)).setAttribute(eq(ExecutionContext.ATTR_INVOKER), any(CachePolicyV3.CacheInvoker.class));
    }

    @Test
    public void shouldNotUseCacheOnGET() {
        when(request.headers()).thenReturn(HttpHeaders.create());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(cachePolicyConfiguration.getMethods()).thenReturn(Collections.singletonList(HttpMethod.POST));

        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);
        cachePolicyV3.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, never()).setAttribute(eq(ExecutionContext.ATTR_INVOKER), any(CachePolicyV3.CacheInvoker.class));
    }

    @Test
    public void shouldTrueIfEvaluateWithNoCondition() {
        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(
            cachePolicyV3,
            "evaluate",
            executionContext,
            Mockito.mock(ProxyResponse.class),
            null
        );

        assertTrue(evaluate);
    }

    @Test
    public void shouldTrueIfEvaluateWithEmptyCondition() {
        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(
            cachePolicyV3,
            "evaluate",
            executionContext,
            Mockito.mock(ProxyResponse.class),
            ""
        );

        assertTrue(evaluate);
    }

    @Test
    public void shouldTrueIfEvaluationTrue() {
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        TemplateContext templateContext = mock(TemplateContext.class);
        when(executionContext.getTemplateEngine()).thenReturn(templateEngine);
        when(templateEngine.getTemplateContext()).thenReturn(templateContext);
        when(templateEngine.getValue(eq("true"), eq(Boolean.class))).thenReturn(Boolean.TRUE);
        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(
            cachePolicyV3,
            "evaluate",
            executionContext,
            Mockito.mock(ProxyResponse.class),
            "true"
        );

        assertTrue(evaluate);
    }

    @Test
    public void shouldFalseIfEvaluationFalse() {
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        TemplateContext templateContext = mock(TemplateContext.class);
        when(executionContext.getTemplateEngine()).thenReturn(templateEngine);
        when(templateEngine.getTemplateContext()).thenReturn(templateContext);
        when(templateEngine.getValue(eq("false"), eq(Boolean.class))).thenReturn(Boolean.FALSE);
        CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(
            cachePolicyV3,
            "evaluate",
            executionContext,
            Mockito.mock(ProxyResponse.class),
            "false"
        );

        assertFalse(evaluate);
    }

    @Test
    public void shouldHashKeyUsingQueryParametersInAnyOrder() {
        final CachePolicyV3 cachePolicyV3 = new CachePolicyV3(cachePolicyConfiguration);
        final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(resourceManager.getResource(any(), eq(CacheResource.class))).thenReturn(mock(CacheResource.class));
        when(cachePolicyConfiguration.getScope()).thenReturn(CacheScope.API);
        when(executionContext.request()).thenReturn(request);
        when(request.path()).thenReturn("/test");
        when(request.parameters()).thenReturn(queryParams);

        queryParams.add("foo", "a");
        queryParams.add("foo", "b");
        queryParams.add("bar", "c");
        queryParams.add("bar", "d");

        String hash1 = cachePolicyV3.hash(executionContext);

        queryParams.clear();
        queryParams.add("bar", "d");
        queryParams.add("bar", "c");
        queryParams.add("foo", "b");
        queryParams.add("foo", "a");

        String hash2 = cachePolicyV3.hash(executionContext);

        assertEquals(hash1, hash2);

        queryParams.clear();

        String hash3 = cachePolicyV3.hash(executionContext);

        assertNotEquals(hash2, hash3);
    }
}
