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
package io.gravitee.policy.cache.util;

import io.gravitee.common.http.*;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.http.*;
import java.util.*;

/**
 * @author Wojciech BASZCZYK (wojciech.baszczyk at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ContentTypeUtil {

    private ContentTypeUtil() {}

    public static boolean hasBinaryContentType(HttpHeaders httpHeaders) {
        if (httpHeaders != null) {
            MediaType contentTypeMedia = Optional
                .ofNullable(httpHeaders.get(HttpHeaderNames.CONTENT_TYPE))
                .map(list -> list.get(0))
                .map(MediaType::parseMediaType)
                .orElse(MediaType.MEDIA_ALL);
            return MediaType.MEDIA_APPLICATION_OCTET_STREAM.equals(contentTypeMedia) || "image".equals(contentTypeMedia.getType());
        }
        return false;
    }
}
