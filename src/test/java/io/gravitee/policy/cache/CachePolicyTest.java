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

import static org.mockito.Mockito.mock;

import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.MessageExecutionContext;
import io.gravitee.policy.cache.configuration.CachePolicyConfiguration;
import org.junit.Test;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class CachePolicyTest {

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
