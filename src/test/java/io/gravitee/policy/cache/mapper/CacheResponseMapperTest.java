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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.buffer.BufferFactory;
import io.gravitee.gateway.buffer.netty.BufferFactoryImpl;
import io.gravitee.policy.cache.CacheResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class CacheResponseMapperTest {

    CacheResponseMapper cacheResponseMapper = new CacheResponseMapper();
    BufferFactory factory = new BufferFactoryImpl();
    CacheResponse cacheResponse = new CacheResponse();

    @Before
    public void setup() {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set("Authentication", "Bearer: hackathon");
        httpHeaders.set("Content-Type", "application/json");
        cacheResponse.setHeaders(httpHeaders);
        cacheResponse.setContent(factory.buffer("foobar"));
        cacheResponse.setStatus(200);
    }

    @Test
    public void shouldSerialize() throws JsonProcessingException {
        String responseAsString = cacheResponseMapper.writeValueAsString(cacheResponse);

        Assert.assertEquals(
            responseAsString,
            "{\n" +
            "  \"status\" : 200,\n" +
            "  \"headers\" : {\n" +
            "    \"Authentication\" : [ \"Bearer: hackathon\" ],\n" +
            "    \"Content-Type\" : [ \"application/json\" ]\n" +
            "  },\n" +
            "  \"content\" : {\n" +
            "    \"buffer\" : \"foobar\"\n" +
            "  }\n" +
            "}"
        );
    }

    @Test
    public void shouldDeserialize() throws JsonProcessingException {
        CacheResponse response = cacheResponseMapper.readValue(
            "{\n" +
            "  \"status\" : 200,\n" +
            "  \"headers\" : {\n" +
            "    \"Authentication\" : [ \"Bearer: hackathon\" ],\n" +
            "    \"Content-Type\" : [ \"application/json\" ]\n" +
            "  },\n" +
            "  \"content\" : {\n" +
            "    \"buffer\" : \"foobar\"\n" +
            "  }\n" +
            "}",
            CacheResponse.class
        );

        Assert.assertEquals(response.getContent().toString(), cacheResponse.getContent().toString());
        Assert.assertEquals(response.getHeaders(), cacheResponse.getHeaders());
        Assert.assertEquals(response.getStatus(), cacheResponse.getStatus());
    }
}
