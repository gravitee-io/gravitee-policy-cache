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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.buffer.BufferFactory;
import io.gravitee.gateway.buffer.netty.BufferFactoryImpl;
import io.gravitee.policy.cache.configuration.SerializationMode;
import java.io.IOException;

/**
 * @author Guillaume CUSNIEUX (guillaume.cusnieux at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CacheResponseMapper extends ObjectMapper {

    private SerializationMode serializationMode = null;

    public CacheResponseMapper() {
        super(
            new JsonFactoryBuilder()
                .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
                .build()
        );
        registerModule(new BufferModule());

        enable(SerializationFeature.INDENT_OUTPUT);
        enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    public void setSerializationMode(SerializationMode serializationMode) {
        this.serializationMode = serializationMode;
    }

    public boolean isSerializationModeDefined() {
        return serializationMode != null;
    }

    public class BufferModule extends SimpleModule {

        public BufferModule() {
            super("buffer");
            addDeserializer(Buffer.class, new BufferDeserializerModule());
            addSerializer(Buffer.class, new BufferSerializerModule());
        }
    }

    private class BufferDeserializerModule extends JsonDeserializer<Buffer> {

        BufferFactory factory = new BufferFactoryImpl();

        @Override
        public Buffer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.has("buffer")) {
                if (serializationMode == SerializationMode.TEXT) {
                    return factory.buffer(node.get("buffer").textValue());
                } else if (serializationMode == SerializationMode.BINARY) {
                    return factory.buffer(node.get("buffer").binaryValue());
                } else {
                    throw new IllegalStateException("Serialization mode is not defined");
                }
            }
            return null;
        }
    }

    private class BufferSerializerModule extends JsonSerializer<Buffer> {

        @Override
        public void serialize(Buffer value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeStartObject();
            if (serializationMode == SerializationMode.TEXT) {
                gen.writeStringField("buffer", value.toString());
            } else if (serializationMode == SerializationMode.BINARY) {
                gen.writeBinaryField("buffer", value.getBytes());
            } else {
                throw new IllegalStateException("Serialization mode is not defined");
            }
            gen.writeEndObject();
        }
    }
}
