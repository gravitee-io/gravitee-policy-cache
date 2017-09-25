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
package io.gravitee.policy.cache.configuration;

import io.gravitee.policy.api.PolicyConfiguration;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CachePolicyConfiguration implements PolicyConfiguration {

    private String cacheName;

    private String key;

    private CacheScope scope = CacheScope.APPLICATION;

    // Default to 10 minutes
    private long timeToLiveSeconds = 600;

    private boolean useResponseCacheHeaders = false;

    public String getCacheName() {
        return cacheName;
    }

    public void setCacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public CacheScope getScope() {
        return scope;
    }

    public void setScope(CacheScope scope) {
        this.scope = scope;
    }

    public long getTimeToLiveSeconds() {
        return timeToLiveSeconds;
    }

    public void setTimeToLiveSeconds(long timeToLiveSeconds) {
        this.timeToLiveSeconds = timeToLiveSeconds;
    }

    public boolean isUseResponseCacheHeaders() {
        return useResponseCacheHeaders;
    }

    public void setUseResponseCacheHeaders(boolean useResponseCacheHeaders) {
        this.useResponseCacheHeaders = useResponseCacheHeaders;
    }
}
