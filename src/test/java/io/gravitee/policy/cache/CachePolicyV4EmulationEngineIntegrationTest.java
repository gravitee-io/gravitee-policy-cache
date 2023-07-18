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
import io.gravitee.apim.gateway.tests.sdk.resource.ResourceBuilder;
import io.gravitee.plugin.resource.ResourcePlugin;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import io.gravitee.policy.v3.cache.CachePolicyV3;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava3.core.http.HttpClient;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@GatewayTest
@DeployApi("/io/gravitee/policy/cache/integration/cacheV3.json")
public abstract class CachePolicyV4EmulationEngineIntegrationTest extends AbstractPolicyTest<CachePolicyV3, CachePolicyConfiguration> {

    public static final String RESPONSE_FROM_BACKEND_1 = "response from backend";
    public static final String RESPONSE_FROM_BACKEND_2 = "response from backend modified";

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
            .flatMap(request -> request.rxSend())
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        secondObs.await(1000, TimeUnit.MILLISECONDS);
        secondObs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer.toString()).isEqualTo(RESPONSE_FROM_BACKEND_1);
                return true;
            })
            .assertNoErrors();

        // For the second call, we should have called the backend only once (the first time)
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        DummyCacheResource.checkNumberOfCacheEntries(1);
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
                assertThat(buffer.toString()).isEqualTo(RESPONSE_FROM_BACKEND_2);
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
            .flatMap(request -> request.rxSend())
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        secondObs.await(1000, TimeUnit.MILLISECONDS);
        secondObs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer.toString()).isEqualTo(RESPONSE_FROM_BACKEND_2);
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
                assertThat(buffer.toString()).isEqualTo(RESPONSE_FROM_BACKEND_2);
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
            .flatMap(request -> request.rxSend())
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        obs.await(1000, TimeUnit.MILLISECONDS);
        obs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer.toString()).isEqualTo(RESPONSE_FROM_BACKEND_2);
                return true;
            })
            .assertNoErrors();

        // For the second call, we should have called the backend a second time
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
        DummyCacheResource.checkNumberOfCacheEntries(0);
    }

    private void performFirstCall(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/endpoint").willReturn(ok(RESPONSE_FROM_BACKEND_1)));

        final var obs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> {
                return request.rxSend();
            })
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        obs.await(30000, TimeUnit.MILLISECONDS);
        obs
            .assertComplete()
            .assertValue(buffer -> {
                assertThat(buffer.toString()).isEqualTo(RESPONSE_FROM_BACKEND_1);
                return true;
            })
            .assertNoErrors();

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
    }
}
