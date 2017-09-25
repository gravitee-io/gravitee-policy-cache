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
package io.gravitee.policy.cache.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class ExpiresUtil {

    // The format is an absolute date and time as defined by HTTP-date in section 3.3.1;
    // it MUST be in RFC 1123 date format (example: Thu, 01 Dec 1994 16:00:00 GMT)
    private final static DateTimeFormatter EXPIRES_HEADER_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z").withLocale(Locale.ENGLISH);

    private ExpiresUtil() {}

    public static Instant parseExpires(String expires) {
        if (expires == null) {
            return null;
        }

        try {
            return LocalDateTime.parse(expires, EXPIRES_HEADER_FORMATTER).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException dtpe) {
            return null;
        }
    }
}
