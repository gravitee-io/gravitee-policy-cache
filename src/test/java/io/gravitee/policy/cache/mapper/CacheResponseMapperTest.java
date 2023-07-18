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
package io.gravitee.policy.cache.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.buffer.BufferFactory;
import io.gravitee.gateway.buffer.netty.BufferFactoryImpl;
import io.gravitee.policy.cache.CacheResponse;
import io.gravitee.policy.cache.configuration.SerializationMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
class CacheResponseMapperTest {

    CacheResponseMapper cacheResponseMapper = new CacheResponseMapper();
    BufferFactory factory = new BufferFactoryImpl();
    CacheResponse cacheResponse = new CacheResponse();

    @BeforeEach
    void setup() {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set("Authentication", "Bearer: hackathon");
        httpHeaders.set("Content-Type", "application/json");
        cacheResponse.setHeaders(httpHeaders);
        cacheResponse.setContent(factory.buffer("foobar"));
        cacheResponse.setStatus(200);
    }

    @Test
    void shouldSerialize() throws JsonProcessingException {
        cacheResponseMapper.setSerializationMode(SerializationMode.TEXT);

        String responseAsString = cacheResponseMapper.writeValueAsString(cacheResponse);

        Assertions.assertEquals(
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
    void shouldDeserialize() throws JsonProcessingException {
        cacheResponseMapper.setSerializationMode(SerializationMode.TEXT);

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

        Assertions.assertEquals(response.getContent().toString(), cacheResponse.getContent().toString());
        Assertions.assertEquals(response.getHeaders(), cacheResponse.getHeaders());
        Assertions.assertEquals(response.getStatus(), cacheResponse.getStatus());
    }

    @ParameterizedTest
    @EnumSource(SerializationMode.class)
    void shouldReturnSameDataAsCacheInput(SerializationMode serializationMode) throws JsonProcessingException {
        cacheResponseMapper.setSerializationMode(serializationMode);

        String responseAsString = cacheResponseMapper.writeValueAsString(cacheResponse);
        CacheResponse response = cacheResponseMapper.readValue(responseAsString, CacheResponse.class);

        Assertions.assertEquals(response.getContent().toString(), cacheResponse.getContent().toString());
        Assertions.assertEquals(response.getHeaders(), cacheResponse.getHeaders());
        Assertions.assertEquals(response.getStatus(), cacheResponse.getStatus());
    }
}
