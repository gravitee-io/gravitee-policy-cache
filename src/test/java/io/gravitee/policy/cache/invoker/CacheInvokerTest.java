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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.util.LinkedMultiValueMap;
import io.gravitee.common.util.MultiValueMap;
import io.gravitee.el.TemplateContext;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.reactive.api.context.ExecutionContext;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.HttpRequest;
import io.gravitee.gateway.reactive.api.context.Response;
import io.gravitee.gateway.reactive.api.invoker.Invoker;
import io.gravitee.policy.cache.CacheAction;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.cache.configuration.CacheScope;
import io.gravitee.policy.cache.mapper.CacheResponseMapper;
import io.gravitee.resource.api.ResourceManager;
import io.gravitee.resource.cache.api.Cache;
import io.gravitee.resource.cache.api.CacheResource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@RunWith(MockitoJUnitRunner.class)
public class CacheInvokerTest {

    @Mock
    private HttpExecutionContext httpExecutionContext;

    @Mock
    private CachePolicyConfiguration cachePolicyConfiguration;

    @Mock
    private Invoker delegateInvoker;

    @Mock
    private Cache cache;

    @Mock
    private CacheResponseMapper mapper;

    @Mock
    private CacheAction action;

    @Mock
    protected HttpRequest request;

    private CacheInvoker cacheInvoker;

    @Before
    public void init() {
        MockitoAnnotations.openMocks(this);
        cacheInvoker = new CacheInvoker(delegateInvoker, cache, action, cachePolicyConfiguration, mapper);
    }

    @Test
    public void should_usecachecontrol_smaxage() {
        final var httpHeaders = new HttpHeaders();
        httpHeaders.add(HttpHeaderNames.CACHE_CONTROL, "max-age=600, no-cache, no-store, smax-age=300");
        httpHeaders.add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        long timeToLive = cacheInvoker.timeToLiveFromResponse(httpHeaders);
        Assert.assertEquals(300, timeToLive);
    }

    @Test
    public void should_usecachecontrol_maxage() {
        final var httpHeaders = new HttpHeaders();
        httpHeaders.add(HttpHeaderNames.CACHE_CONTROL, "max-age=600, no-cache, no-store");
        httpHeaders.add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        long timeToLive = cacheInvoker.timeToLiveFromResponse(httpHeaders);
        Assert.assertEquals(600, timeToLive);
    }

    @Test
    public void should_usecachecontrol_expires_past() {
        final var httpHeaders = new HttpHeaders();
        httpHeaders.add(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store");
        httpHeaders.add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        long timeToLive = cacheInvoker.timeToLiveFromResponse(httpHeaders);
        Assert.assertEquals(-1, timeToLive);
    }

    @Test
    public void should_useconfigurationttl() {
        final var httpHeaders = new HttpHeaders();
        httpHeaders.add(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store");
        httpHeaders.add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        when(cachePolicyConfiguration.isUseResponseCacheHeaders()).thenReturn(false);
        when(cachePolicyConfiguration.getTimeToLiveSeconds()).thenReturn(10L);
        long timeToLive = cacheInvoker.resolveTimeToLive(httpHeaders);
        Assert.assertEquals(10, timeToLive);
    }

    @Test
    public void should_useconfigurationttl_whenmaxageprovided() {
        final var httpHeaders = new HttpHeaders();
        httpHeaders.add(HttpHeaderNames.CACHE_CONTROL, "max-age=600, no-cache, no-store");
        httpHeaders.add(HttpHeaderNames.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");

        when(cachePolicyConfiguration.isUseResponseCacheHeaders()).thenReturn(true);
        when(cachePolicyConfiguration.getTimeToLiveSeconds()).thenReturn(10L);
        long timeToLive = cacheInvoker.resolveTimeToLive(httpHeaders);
        Assert.assertEquals(10, timeToLive);
    }

    @Test
    public void shouldTrueIfEvaluateWithNoCondition() {
        var response = mock(Response.class);
        when(response.status()).thenReturn(200);
        Boolean evaluate = ReflectionTestUtils.invokeMethod(cacheInvoker, "evaluate", mock(ExecutionContext.class), response, null);

        assertTrue(evaluate);
    }

    @Test
    public void shouldTrueIfEvaluateWithEmptyCondition() {
        var response = mock(Response.class);
        when(response.status()).thenReturn(200);
        Boolean evaluate = ReflectionTestUtils.invokeMethod(cacheInvoker, "evaluate", mock(ExecutionContext.class), response, "");

        assertTrue(evaluate);
    }

    @Test
    public void shouldTrueIfEvaluationTrue() {
        var executionContext = mock(ExecutionContext.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        TemplateContext templateContext = mock(TemplateContext.class);
        when(executionContext.getTemplateEngine()).thenReturn(templateEngine);
        when(templateEngine.getTemplateContext()).thenReturn(templateContext);
        when(templateEngine.getValue(eq("true"), eq(Boolean.class))).thenReturn(Boolean.TRUE);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(cacheInvoker, "evaluate", executionContext, mock(Response.class), "true");

        assertTrue(evaluate);
    }

    @Test
    public void shouldFalseIfEvaluationFalse() {
        var executionContext = mock(ExecutionContext.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        TemplateContext templateContext = mock(TemplateContext.class);
        when(executionContext.getTemplateEngine()).thenReturn(templateEngine);
        when(templateEngine.getTemplateContext()).thenReturn(templateContext);
        when(templateEngine.getValue(eq("false"), eq(Boolean.class))).thenReturn(Boolean.FALSE);

        Boolean evaluate = ReflectionTestUtils.invokeMethod(cacheInvoker, "evaluate", executionContext, mock(Response.class), "false");

        assertFalse(evaluate);
    }

    @Test
    public void shouldHashKeyUsingQueryParametersInAnyOrder() {
        final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();

        ResourceManager resourceManager = mock(ResourceManager.class);
        when(httpExecutionContext.getComponent(ResourceManager.class)).thenReturn(resourceManager);
        when(resourceManager.getResource(any(), eq(CacheResource.class))).thenReturn(mock(CacheResource.class));
        when(cachePolicyConfiguration.getScope()).thenReturn(CacheScope.API);
        when(httpExecutionContext.request()).thenReturn(request);
        when(request.path()).thenReturn("/test");
        when(request.parameters()).thenReturn(queryParams);

        queryParams.add("foo", "a");
        queryParams.add("foo", "b");
        queryParams.add("bar", "c");
        queryParams.add("bar", "d");

        String hash1 = cacheInvoker.hash(httpExecutionContext);

        queryParams.clear();
        queryParams.add("bar", "d");
        queryParams.add("bar", "c");
        queryParams.add("foo", "b");
        queryParams.add("foo", "a");

        String hash2 = cacheInvoker.hash(httpExecutionContext);

        assertEquals(hash1, hash2);

        queryParams.clear();

        String hash3 = cacheInvoker.hash(httpExecutionContext);

        assertNotEquals(hash2, hash3);
    }
}
