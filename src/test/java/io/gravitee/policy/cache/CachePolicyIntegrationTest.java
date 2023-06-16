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
import io.reactivex.rxjava3.observers.TestObserver;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.ext.web.client.HttpResponse;
import io.vertx.rxjava3.ext.web.client.WebClient;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
@GatewayTest
@DeployApi("/io/gravitee/policy/cache/integration/cache.json")
class CachePolicyIntegrationTest extends AbstractPolicyTest<CachePolicy, CachePolicyConfiguration> {

    @Override
    public void configureResources(Map<String, ResourcePlugin> resources) {
        resources.put("dummy-cache", ResourceBuilder.build("dummy-cache", DummyCacheResource.class));
    }

    @Test
    @DisplayName("Should invoke cache instead of backend when doing the same call twice")
    void shouldUseCache(HttpClient client) throws Exception {
        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));

        final var obs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.rxSend())
            .flatMapPublisher(
                response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                }
            )
            .test();

        obs.await(30000, TimeUnit.MILLISECONDS);
        obs
            .assertComplete()
            .assertValue(
                buffer -> {
                    assertThat(buffer.toString()).isEqualTo("response from backend");
                    return true;
                }
            )
            .assertNoErrors();

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));

        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend modified")));

        final var secondObs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(request -> request.rxSend())
            .flatMapPublisher(
                response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                }
            )
            .test();

        secondObs.await(1000, TimeUnit.MILLISECONDS);
        secondObs
            .assertComplete()
            .assertValue(
                buffer -> {
                    assertThat(buffer.toString()).isEqualTo("response from backend");
                    return true;
                }
            )
            .assertNoErrors();

        // For the second call, we should have called the backend only once (the first time)
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
    }
}
