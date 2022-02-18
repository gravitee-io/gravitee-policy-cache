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
package io.gravitee.policy.cache.mapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.policy.cache.CacheResponse;
import io.gravitee.policy.cache.mapper.CacheResponseMapper;
import io.gravitee.policy.cache.resource.CacheElement;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class CacheResponseMapperTest {

    CacheResponseMapper mapper = new CacheResponseMapper();

    private static byte[] compress(String str) throws Exception {
        if (str == null || str.length() == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream obj = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(obj);
        gzip.write(str.getBytes(StandardCharsets.UTF_8));
        gzip.close();
        return obj.toByteArray();
    }

    public static String decompress(byte[] bytes) throws Exception {
        GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes));

        return new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
    }

    @Test
    public void shouldReadCacheResponseWithCompressContent() throws Exception {
        String content = "foobar";
        Buffer buffer = Buffer.buffer(compress(content));
        CacheResponse response = new CacheResponse();
        response.setContent(buffer);
        CacheElement element = new CacheElement("key", mapper.writeValueAsString(response));

        assertNotNull(element);

        CacheResponse cacheResponse = mapper.readValue(element.value().toString(), CacheResponse.class);
        assertNotNull(cacheResponse);

        String contentUncompressed = decompress(cacheResponse.getContent().getBytes());
        assertEquals(content, contentUncompressed);
    }
}
