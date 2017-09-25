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
package io.gravitee.policy.cache.proxy;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.proxy.ProxyConnection;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import io.gravitee.gateway.api.stream.ReadStream;
import io.gravitee.policy.cache.CacheResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CacheProxyConnection implements ProxyConnection {

    private Handler<ProxyResponse> proxyResponseHandler;
    private final CacheResponse response;

    public CacheProxyConnection(final CacheResponse response) {
        this.response = response;
    }

    @Override
    public ProxyConnection write(Buffer buffer) {
        return this;
    }

    @Override
    public void end() {
        proxyResponseHandler.handle(new CacheProxyResponse(response));
    }

    @Override
    public ProxyConnection responseHandler(Handler<ProxyResponse> responseHandler) {
        this.proxyResponseHandler = responseHandler;
        return this;
    }

    class CacheProxyResponse implements ProxyResponse {
        private Handler<Buffer> bodyHandler;
        private Handler<Void> endHandler;

        private final CacheResponse cacheResponse;

        CacheProxyResponse(final CacheResponse cacheResponse) {
            this.cacheResponse = cacheResponse;
        }

        @Override
        public int status() {
            return cacheResponse.getStatus();
        }

        @Override
        public HttpHeaders headers() {
            return cacheResponse.getHeaders();
        }

        @Override
        public ProxyResponse bodyHandler(Handler<Buffer> bodyPartHandler) {
            this.bodyHandler = bodyPartHandler;
            return this;
        }

        @Override
        public ProxyResponse endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            if (cacheResponse.getContent() != null) {
                bodyHandler.handle(cacheResponse.getContent());
            }

            endHandler.handle(null);
            return this;
        }
    }
}