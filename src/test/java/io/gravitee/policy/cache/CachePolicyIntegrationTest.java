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
import io.reactivex.observers.TestObserver;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
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
    void shouldUseCache(WebClient client) throws Exception {
        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));

        final TestObserver<HttpResponse<Buffer>> obs = client.get("/test").rxSend().test();

        awaitTerminalEvent(obs);
        obs
            .assertComplete()
            .assertValue(
                response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.bodyAsString()).isEqualTo("response from backend");
                    return true;
                }
            )
            .assertNoErrors();

        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));

        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend modified")));

        final TestObserver<HttpResponse<Buffer>> secondObs = client.get("/test").rxSend().test();

        secondObs.awaitTerminalEvent(1000, TimeUnit.MILLISECONDS);
        secondObs
            .assertComplete()
            .assertValue(
                response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.bodyAsString()).isEqualTo("response from backend");
                    return true;
                }
            )
            .assertNoErrors();

        // For the second call, we should have called the backend only once (the first time)
        wiremock.verify(1, getRequestedFor(urlPathEqualTo("/endpoint")));
    }
}
