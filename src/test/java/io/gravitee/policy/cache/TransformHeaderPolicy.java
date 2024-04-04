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

import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.annotations.OnResponse;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * @author Jeoffrey HAEYAERT (jeoffrey.haeyaert at graviteesource.com)
 * @author GraviteeSource Team
 */
public class TransformHeaderPolicy implements Policy {

    @Override
    public String id() {
        return "transform-headers";
    }

    @OnResponse
    public void onResponse(Request request, Response response, PolicyChain policyChain) {
        execute(request.headers(), response.headers());
        policyChain.doNext(request, response);
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return Completable.fromAction(() -> execute(ctx.request().headers(), ctx.response().headers())).subscribeOn(Schedulers.single());
    }

    private void execute(HttpHeaders requestHeaders, HttpHeaders responseHeaders) {
        if (requestHeaders.contains("X-First-Call")) {
            // Manipulate header on first call only to simulate a specific behavior happening on headers after the backend response has been cached.
            responseHeaders.remove("X-Backend-Header");
            responseHeaders.add("X-Client-Header", "This_Header_Should_Not_Be_Cached");
        }

        // Add a fingerprint header that can be verified to make sure the policy has been executed.
        responseHeaders.add("X-Transform-Header-Policy", "Run");
    }
}
