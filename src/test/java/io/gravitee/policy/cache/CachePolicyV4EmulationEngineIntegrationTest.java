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

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.policy.PolicyBuilder;
import io.gravitee.apim.gateway.tests.sdk.policy.fakes.Stream1Policy;
import io.gravitee.apim.gateway.tests.sdk.resource.ResourceBuilder;
import io.gravitee.plugin.policy.PolicyPlugin;
import io.gravitee.plugin.resource.ResourcePlugin;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.v3.cache.CachePolicyV3;
import io.micrometer.observation.Observation;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@GatewayTest
@DeployApi("/io/gravitee/policy/cache/integration/cacheV3.json")
public abstract class CachePolicyV4EmulationEngineIntegrationTest extends AbstractPolicyTest<CachePolicyV3, CachePolicyConfiguration> {

    public static final String RESPONSE_FROM_BACKEND_1 = "response from backend";
    public static final String RESPONSE_FROM_BACKEND_2 = "response from backend modified";

    @Override
    public void configurePolicies(Map<String, PolicyPlugin> policies) {
        policies.put("transform-headers", PolicyBuilder.build("transform-headers", TransformHeaderPolicy.class));
        super.configurePolicies(policies);
    }

    @Override
    public void configureResources(Map<String, ResourcePlugin> resources) {
        resources.put("dummy-cache", ResourceBuilder.build("dummy-cache", DummyCacheResource.class));
    }

    @AfterEach
    public void setup() {
        DummyCacheResource.clearCache();
        wiremock.resetAll();
    }

    @Test
    @DisplayName("Should invoke cache instead of backend when doing the same call twice")
    void shouldUseCache(HttpClient client) throws Exception {
        performFirstCall(client);

        wiremock.stubFor(get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_2)));

        final var secondObs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(HttpClientRequest::rxSend)
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        secondObs.await(1000, TimeUnit.MILLISECONDS);
        secondObs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer).hasToString(RESPONSE_FROM_BACKEND_1);
                return true;
            })
            .assertNoErrors();

        // For the second call, we should have called the backend only once (the first time)
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        DummyCacheResource.checkNumberOfCacheEntries(1);
    }

    @Test
    @DisplayName("Should not put client response headers in cache")
    void shouldNotPutClientResponseHeaderInCache(HttpClient client) throws Exception {
        try {
            final AtomicInteger c = new AtomicInteger(0);
            final TestScheduler testScheduler = new TestScheduler();
            RxJavaPlugins.setIoSchedulerHandler(s -> {
                if (c.incrementAndGet() == 2) {
                    // Intercept the scheduler use to store in cache. It should be the second one (first is getCache)
                    return testScheduler;
                }

                return s;
            });

            performFirstCall(
                client,
                () -> {
                    try {
                        Thread.sleep(1000);
                    } catch (Exception ignored) {}
                    testScheduler.triggerActions();
                }
            );

            RxJavaPlugins.reset();

            wiremock.stubFor(get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_2)));

            final var secondObs = client
                .rxRequest(HttpMethod.GET, "/test")
                .flatMap(HttpClientRequest::rxSend)
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.headers().contains("X-Transform-Header-Policy"))
                        .withFailMessage("The transform header policy should have been executed")
                        .isTrue();
                    assertThat(response.headers().contains("X-Backend-Header"))
                        .withFailMessage("The X-Backend-Header should have been cached")
                        .isTrue();
                    assertThat(response.headers().contains("X-Client-Header"))
                        .withFailMessage("The X-Client-Header should NOT have been cached")
                        .isFalse();
                    return response.toFlowable();
                })
                .test();

            secondObs.await(1000, TimeUnit.MILLISECONDS);
            secondObs
                .assertComplete()
                .assertValue(buffer -> {
                    assertThat(buffer).hasToString(RESPONSE_FROM_BACKEND_1);
                    return true;
                })
                .assertNoErrors();

            // For the second call, we should have called the backend only once (the first time)
            wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        } finally {
            RxJavaPlugins.reset();
        }
    }

    @Test
    @DisplayName("Should refresh cache if parameter is present in the header")
    void shouldRefreshCache_UsingHeader(HttpClient client) throws Exception {
        performFirstCall(client);
        DummyCacheResource.checkNumberOfCacheEntries(1);

        // ByPass will not update the cache
        DummyCacheResource.clearCache();

        wiremock.stubFor(get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_2)));

        final var secondObs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.putHeader(CachePolicyV3.X_GRAVITEE_CACHE_ACTION, CacheAction.REFRESH.name()).rxSend())
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        secondObs.await(1000, TimeUnit.MILLISECONDS);

        secondObs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer).hasToString(RESPONSE_FROM_BACKEND_2);
                return true;
            })
            .assertNoErrors();

        // For the second call, we should have called the backend a second time
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/endpoint")));
        DummyCacheResource.checkNumberOfCacheEntries(1);
    }

    @Test
    @DisplayName("Should Refresh cache if parameter is present in the query parameters")
    void shouldRefreshCache_UsingParams(HttpClient client) throws Exception {
        performFirstCall(client);
        DummyCacheResource.checkNumberOfCacheEntries(1);

        // ByPass will not update the cache
        DummyCacheResource.clearCache();

        wiremock.stubFor(get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_2)));

        final var secondObs = client
            .rxRequest(HttpMethod.GET, "/test?cache=" + CacheAction.REFRESH.name())
            .flatMap(HttpClientRequest::rxSend)
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        secondObs.await(1000, TimeUnit.MILLISECONDS);
        secondObs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer).hasToString(RESPONSE_FROM_BACKEND_2);
                return true;
            })
            .assertNoErrors();

        // For the second call, we should have called the backend a second time
        wiremock.verify(2, getRequestedFor(urlPathEqualTo("/endpoint")));
        DummyCacheResource.checkNumberOfCacheEntries(1);
    }

    @Test
    @DisplayName("Should bypass cache if parameter is present in the header")
    void shouldBypassCache_UsingHeader(HttpClient client) throws Exception {
        wiremock.stubFor(get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_2)));

        final var obs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.putHeader(CachePolicyV3.X_GRAVITEE_CACHE_ACTION, CacheAction.BY_PASS.name()).rxSend())
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        obs.await(1000, TimeUnit.MILLISECONDS);

        obs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer).hasToString(RESPONSE_FROM_BACKEND_2);
                return true;
            })
            .assertNoErrors();

        // For the second call, we should have called the backend a second time
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        DummyCacheResource.checkNumberOfCacheEntries(0);
    }

    @Test
    @DisplayName("Should bypass cache if parameter is present in the query parameters")
    void shouldBypassCache_UsingParams(HttpClient client) throws Exception {
        // ByPass will not update the cache
        DummyCacheResource.clearCache();

        wiremock.stubFor(get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_2)));

        final var obs = client
            .rxRequest(HttpMethod.GET, "/test?cache=" + CacheAction.BY_PASS.name())
            .flatMap(HttpClientRequest::rxSend)
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        obs.await(1000, TimeUnit.MILLISECONDS);
        obs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer).hasToString(RESPONSE_FROM_BACKEND_2);
                return true;
            })
            .assertNoErrors();

        // For the second call, we should have called the backend a second time
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        DummyCacheResource.checkNumberOfCacheEntries(0);
    }

    private void performFirstCall(HttpClient client) throws InterruptedException {
        performFirstCall(client, () -> {});
    }

    private void performFirstCall(HttpClient client, Runnable action) throws InterruptedException {
        wiremock.stubFor(
            get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_1).withHeader("X-Backend-Header", "This_Header_Should_Be_Cached"))
        );

        final var obs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(httpClientRequest -> {
                httpClientRequest.headers().add("X-First-Call", "True");
                return httpClientRequest.rxSend();
            })
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        action.run();

        obs.await(30000, TimeUnit.MILLISECONDS);
        obs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer).hasToString(RESPONSE_FROM_BACKEND_1);
                return true;
            })
            .assertNoErrors();

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
    }
}
