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
package io.gravitee.policy.cache;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CacheControl {

    private boolean noCache;
    private boolean noStore;
    private boolean noTransform;
    private boolean mustRevalidate;
    private boolean isPrivate;
    private boolean isPublic;
    private long maxAge = -1;
    private long sMaxAge = -1;

    public boolean isPrivate() {
        return isPrivate;
    }

    public void setPrivate(boolean aPrivate) {
        isPrivate = aPrivate;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean aPublic) {
        isPublic = aPublic;
    }

    public long getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(long maxAge) {
        this.maxAge = maxAge;
    }

    public boolean isMustRevalidate() {
        return mustRevalidate;
    }

    public void setMustRevalidate(boolean mustRevalidate) {
        this.mustRevalidate = mustRevalidate;
    }

    public boolean isNoCache() {
        return noCache;
    }

    public void setNoCache(boolean noCache) {
        this.noCache = noCache;
    }

    public boolean isNoStore() {
        return noStore;
    }

    public void setNoStore(boolean noStore) {
        this.noStore = noStore;
    }

    public boolean isNoTransform() {
        return noTransform;
    }

    public void setNoTransform(boolean noTransform) {
        this.noTransform = noTransform;
    }

    public long getSMaxAge() {
        return sMaxAge;
    }

    public void setSMaxAge(long sMaxAge) {
        this.sMaxAge = sMaxAge;
    }
}
