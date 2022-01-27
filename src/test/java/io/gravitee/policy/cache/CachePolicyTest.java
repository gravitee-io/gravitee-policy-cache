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
import io.gravitee.common.util.LinkedMultiValueMap;
import io.gravitee.el.TemplateContext;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.gateway.api.stream.ReadStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.Cache;
import io.gravitee.resource.cache.CacheResource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class CachePolicyTest {

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
        initMocks(this);
    }

    @Test
    public void should_usecachecontrol_smaxage() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(HttpHeaders.CACHE_CONTROL, "max-age=600, no-cache, no-store, smax-age=300");
                put(HttpHeaders.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");
            }
        });

        when(proxyResponse.headers()).thenReturn(headers);

        long timeToLive = CachePolicy.timeToLiveFromResponse(proxyResponse);
        Assert.assertEquals(300, timeToLive);
    }

    @Test
    public void should_usecachecontrol_maxage() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(HttpHeaders.CACHE_CONTROL, "max-age=600, no-cache, no-store");
                put(HttpHeaders.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");
            }
        });

        when(proxyResponse.headers()).thenReturn(headers);

        long timeToLive = CachePolicy.timeToLiveFromResponse(proxyResponse);
        Assert.assertEquals(600, timeToLive);
    }

    @Test
    public void should_usecachecontrol_expires_past() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
                put(HttpHeaders.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");
            }
        });

        when(proxyResponse.headers()).thenReturn(headers);

        long timeToLive = CachePolicy.timeToLiveFromResponse(proxyResponse);
        Assert.assertEquals(-1, timeToLive);
    }

    @Test
    public void shouldFailIfNoCacheResource() {
        when(request.headers()).thenReturn(new HttpHeaders());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(cachePolicyConfiguration.getCacheName()).thenReturn("cache");
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource("cache", CacheResource.class)).thenReturn(cr);
        when(cr.getCache()).thenReturn(null);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).failWith(any());
    }

    @Test
    public void shouldUseCacheOnGETByDefault() {
        when(request.headers()).thenReturn(new HttpHeaders());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        Cache cache = mock(Cache.class);
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource(any(), eq(CacheResource.class))).thenReturn(cr);
        when(cr.getCache()).thenReturn(cache);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, times(1))
                .setAttribute(eq(ExecutionContext.ATTR_INVOKER),
                        any(CachePolicy.CacheInvoker.class));
    }

    @Test
    public void shouldNotUseCacheOnPOSTByDefault() {
        when(request.headers()).thenReturn(new HttpHeaders());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.POST);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, never())
                .setAttribute(eq(ExecutionContext.ATTR_INVOKER),
                        any(CachePolicy.CacheInvoker.class));
    }

    @Test
    public void shouldUseCacheOnPOST() {
        when(request.headers()).thenReturn(new HttpHeaders());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.POST);
        when(cachePolicyConfiguration.getMethods()).thenReturn(Collections.singletonList(HttpMethod.POST));
        ResourceManager rm = mock(ResourceManager.class);
        CacheResource cr = mock(CacheResource.class);
        Cache cache = mock(Cache.class);
        when(executionContext.getComponent(ResourceManager.class)).thenReturn(rm);
        when(rm.getResource(any(), eq(CacheResource.class))).thenReturn(cr);
        when(cr.getCache()).thenReturn(cache);

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, times(1))
                .setAttribute(eq(ExecutionContext.ATTR_INVOKER),
                        any(CachePolicy.CacheInvoker.class));
    }

    @Test
    public void shouldNotUseCacheOnGET() {
        when(request.headers()).thenReturn(new HttpHeaders());
        when(request.parameters()).thenReturn(new LinkedMultiValueMap());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(cachePolicyConfiguration.getMethods()).thenReturn(Collections.singletonList(HttpMethod.POST));

        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);
        cachePolicy.onRequest(request, response, executionContext, policyChain);

        verify(policyChain, times(1)).doNext(any(), any());
        verify(policyChain, never()).failWith(any());
        verify(executionContext, never())
                .setAttribute(eq(ExecutionContext.ATTR_INVOKER),
                        any(CachePolicy.CacheInvoker.class));
    }

    @Test
    public void shouldTrueIfEvaluateWithNoCondition(){
        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(cachePolicy, "evaluate", executionContext, Mockito.mock(ProxyResponse.class), null);

        assertTrue(evaluate);
    }

    @Test
    public void shouldTrueIfEvaluateWithEmptyCondition(){
        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(cachePolicy, "evaluate", executionContext, Mockito.mock(ProxyResponse.class), "");

        assertTrue(evaluate);
    }

    @Test
    public void shouldTrueIfEvaluationTrue(){
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        TemplateContext templateContext = mock(TemplateContext.class);
        when(executionContext.getTemplateEngine()).thenReturn(templateEngine);
        when(templateEngine.getTemplateContext()).thenReturn(templateContext);
        when(templateEngine.getValue(eq("true"), eq(Boolean.class))).thenReturn(Boolean.TRUE);
        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(cachePolicy, "evaluate", executionContext, Mockito.mock(ProxyResponse.class), "true");

        assertTrue(evaluate);
    }

    @Test
    public void shouldFalseIfEvaluationFalse(){
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        TemplateContext templateContext = mock(TemplateContext.class);
        when(executionContext.getTemplateEngine()).thenReturn(templateEngine);
        when(templateEngine.getTemplateContext()).thenReturn(templateContext);
        when(templateEngine.getValue(eq("false"), eq(Boolean.class))).thenReturn(Boolean.FALSE);
        CachePolicy cachePolicy = new CachePolicy(cachePolicyConfiguration);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(cachePolicy, "evaluate", executionContext, Mockito.mock(ProxyResponse.class), "false");

        assertFalse(evaluate);
    }
}
