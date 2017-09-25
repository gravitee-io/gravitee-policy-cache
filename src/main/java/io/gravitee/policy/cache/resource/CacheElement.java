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
package io.gravitee.policy.cache.resource;

import io.gravitee.policy.cache.CacheResponse;
import io.gravitee.resource.cache.Element;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CacheElement implements Element {

    private final String key;

    private final CacheResponse response;

    private int timeToLive = 0;

    public CacheElement(String key, CacheResponse response) {
        this.key = key;
        this.response = response;
    }

    public int getTimeToLive() {
        return timeToLive;
    }

    public void setTimeToLive(int timeToLive) {
        this.timeToLive = timeToLive;
    }

    @Override
    public Object key() {
        return key;
    }

    @Override
    public Object value() {
        return response;
    }

    @Override
    public int timeToLive() {
        return timeToLive;
    }
}
