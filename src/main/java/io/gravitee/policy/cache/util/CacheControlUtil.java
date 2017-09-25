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

import io.gravitee.policy.cache.CacheControl;

import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class CacheControlUtil {

    private CacheControlUtil() {}

    /**
     * Parse the Cache-Control header
     */
    public static CacheControl parseCacheControl(String cacheControlHeader) {
        if (cacheControlHeader == null) {
            return null;
        }

        CacheControlParser parser = new CacheControlParser(cacheControlHeader);
        CacheControl cacheControl = new CacheControl();
        for (Directive directive : parser) {
            switch (directive) {
                case NOCACHE:
                    cacheControl.setNoCache(true);
                    break;
                case NOSTORE:
                    cacheControl.setNoStore(true);
                    break;
                case NOTRANSFORM:
                    cacheControl.setNoTransform(true);
                    break;
                case MUSTREVALIDATE:
                    cacheControl.setMustRevalidate(true);
                    break;
                case PUBLIC:
                    cacheControl.setPublic(true);
                    break;
                case PRIVATE:
                    cacheControl.setPrivate(true);
                    break;
                case MAXAGE:
                    cacheControl.setMaxAge(Long.parseLong(parser.getValue(directive)));
                    break;
                case SMAXAGE:
                    cacheControl.setSMaxAge(Long.parseLong(parser.getValue(directive)));
                    break;
            }
        }

        return cacheControl;
    }

    /**
     * Cache Control Directives
     */
    public enum Directive {
        MAXAGE, MAXSTALE, MINFRESH, NOCACHE, NOSTORE, NOTRANSFORM, ONLYIFCACHED, MUSTREVALIDATE, PRIVATE,
        PROXYREVALIDATE, PUBLIC, SMAXAGE, UNKNOWN;

        public static Directive select(String d) {
            try {
                d = d.toUpperCase().replaceAll("-", "");
                return Directive.valueOf(d);
            } catch (Exception e) {
            }
            return UNKNOWN;
        }
    }

    /**
     * Parser for the Cache-Control header
     */
    public static class CacheControlParser implements Iterable<Directive> {

        private static final String REGEX =
                "\\s*([\\w\\-]+)\\s*(=)?\\s*(\\d+|\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)+\")?\\s*";

        private static final Pattern pattern = Pattern.compile(REGEX);

        private HashMap<Directive, String> values = new HashMap<>();

        public CacheControlParser(String value) {
            Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                String d = matcher.group(1);
                Directive directive = Directive.select(d);
                if (directive != Directive.UNKNOWN) {
                    values.put(directive, matcher.group(3));
                }
            }
        }

        public String getValue(Directive directive) {
            return values.get(directive);
        }

        public Iterator<Directive> iterator() {
            return values.keySet().iterator();
        }
    }
}
