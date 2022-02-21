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
import io.gravitee.gateway.api.buffer.Buffer;
import java.io.*;
import java.util.Map;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CacheResponseSerializable extends AbstractCacheResponse<byte[], byte[]> implements Serializable {

    private int status;

    private byte[] headers;

    private byte[] content;

    public static CacheResponseSerializable serialize(CacheResponse cacheResponse) throws IOException {
        CacheResponseSerializable cacheResponseSerializable = new CacheResponseSerializable();
        cacheResponseSerializable.setStatus(cacheResponse.getStatus());
        cacheResponseSerializable.setContent(cacheResponse.getContent().getBytes());
        ByteArrayOutputStream headersOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(headersOut);
        out.writeObject(cacheResponse.getHeaders());
        cacheResponseSerializable.setHeaders(headersOut.toByteArray());
        return cacheResponseSerializable;
    }

    public static CacheResponse deserialize(CacheResponseSerializable cacheResponseSerializable)
        throws IOException, ClassNotFoundException {
        CacheResponse cacheResponse = new CacheResponse();
        cacheResponse.setStatus(cacheResponseSerializable.getStatus());
        cacheResponse.setContent(Buffer.buffer(cacheResponseSerializable.getContent()));
        ByteArrayInputStream byteIn = new ByteArrayInputStream(cacheResponseSerializable.getHeaders());
        ObjectInputStream in = new ObjectInputStream(byteIn);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll((Map) in.readObject());
        cacheResponse.setHeaders(headers);
        return cacheResponse;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public byte[] getHeaders() {
        return headers;
    }

    public void setHeaders(byte[] headers) {
        this.headers = headers;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }
}
