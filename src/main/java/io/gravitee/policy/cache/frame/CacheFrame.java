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
package io.gravitee.policy.cache.frame;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.policy.cache.CachedResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CacheFrame {

    static final byte FRAME_VERSION_1 = 0x01;

    private static final ObjectMapper LEGACY_MAPPER = createLegacyMapper();

    private CacheFrame() {}

    /**
     * Coerces a cache element value into a byte[] frame representation.
     * Accepts the new {@code byte[]} format produced by this policy and the legacy {@code String}
     * format produced by {@code gravitee-policy-cache <= 4.0.0-alpha.2} (when stored via the
     * pre-binary {@code Cache} API on Redis cache resources).
     */
    public static byte[] asFrame(Object value) {
        if (value instanceof byte[] b) {
            return b;
        }
        if (value instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    public static boolean isLegacyFormat(byte[] frame) {
        return frame == null || frame.length < 1 || frame[0] != FRAME_VERSION_1;
    }

    public static byte[] encode(CachedResponse response) {
        ByteBuf prefix = Unpooled.buffer();
        prefix.writeByte(FRAME_VERSION_1);
        prefix.writeShort(response.status());

        int headersLenPos = prefix.writerIndex();
        prefix.writeInt(0);
        int headersStart = prefix.writerIndex();
        prefix.writeShort(0);

        int count = 0;
        HttpHeaders headers = response.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                byte[] nameBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                for (String value : entry.getValue()) {
                    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
                    prefix.writeShort(nameBytes.length);
                    prefix.writeBytes(nameBytes);
                    prefix.writeInt(valueBytes.length);
                    prefix.writeBytes(valueBytes);
                    count++;
                }
            }
        }
        int headersEnd = prefix.writerIndex();
        prefix.setShort(headersStart, count);
        prefix.setInt(headersLenPos, headersEnd - headersStart);

        int prefixLen = prefix.readableBytes();
        Buffer body = response.body();
        int bodyLen = body == null ? 0 : body.length();
        byte[] result = new byte[prefixLen + bodyLen];
        prefix.getBytes(0, result, 0, prefixLen);
        if (bodyLen > 0) {
            body.getNativeBuffer().getBytes(0, result, prefixLen, bodyLen);
        }
        return result;
    }

    public static CachedResponse decode(byte[] frame) {
        ByteBuf buf = Unpooled.wrappedBuffer(frame);
        int offset = 1;
        int status = buf.getUnsignedShort(offset);
        offset += 2;
        int headersLen = buf.getInt(offset);
        offset += 4;
        int headersEnd = offset + headersLen;

        HttpHeaders headers = new HttpHeaders();
        int headerCount = buf.getUnsignedShort(offset);
        offset += 2;
        for (int i = 0; i < headerCount; i++) {
            int nameLen = buf.getUnsignedShort(offset);
            offset += 2;
            String name = buf.toString(offset, nameLen, StandardCharsets.UTF_8);
            offset += nameLen;
            int valueLen = buf.getInt(offset);
            offset += 4;
            String value = buf.toString(offset, valueLen, StandardCharsets.UTF_8);
            offset += valueLen;
            headers.add(name, value);
        }

        Buffer body = frame.length > headersEnd
            ? Buffer.buffer(Unpooled.wrappedBuffer(frame, headersEnd, frame.length - headersEnd))
            : Buffer.buffer();

        return new CachedResponse(status, headers, body);
    }

    /**
     * Decodes a cache entry produced by gravitee-policy-cache 4.0.0-alpha.2 and earlier (JSON envelope
     * with optionally Base64-encoded body for binary content types). Used during rolling upgrades to
     * avoid forcing a cache thrash on shared Redis when a mixed-version fleet is live (APIM-13628).
     * <p>
     * The new policy never writes the legacy format. Entries decoded via this method are served as-is
     * and left untouched in cache; they migrate to the binary frame format on natural TTL expiry.
     */
    public static CachedResponse decodeLegacy(byte[] jsonBytes) throws Exception {
        JsonNode root = LEGACY_MAPPER.readTree(jsonBytes);
        int status = root.path("status").asInt();
        HttpHeaders headers = new HttpHeaders();
        JsonNode headersNode = root.path("headers");
        if (headersNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = headersNode.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode values = entry.getValue();
                if (values.isArray()) {
                    for (JsonNode v : values) {
                        headers.add(entry.getKey(), v.asText());
                    }
                }
            }
        }
        String bufferStr = root.path("content").path("buffer").asText("");
        byte[] bodyBytes = hasBinaryContentType(headers)
            ? Base64.getDecoder().decode(bufferStr)
            : bufferStr.getBytes(StandardCharsets.UTF_8);
        return new CachedResponse(status, headers, Buffer.buffer(bodyBytes));
    }

    private static boolean hasBinaryContentType(HttpHeaders headers) {
        List<String> contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null || contentType.isEmpty()) {
            return false;
        }
        String type = contentType.get(0);
        if (type == null) {
            return false;
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return lower.startsWith("image/") || lower.contains("application/octet-stream");
    }

    private static ObjectMapper createLegacyMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build());
        return mapper;
    }
}
