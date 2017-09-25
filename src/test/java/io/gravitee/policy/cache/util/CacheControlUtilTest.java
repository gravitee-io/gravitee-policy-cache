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
import io.gravitee.policy.cache.util.CacheControlUtil;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CacheControlUtilTest {

    @Test
    public void shouldExtractCacheControl() {
        CacheControl cacheControl = CacheControlUtil.parseCacheControl("max-age=0, no-cache, no-store");
        Assert.assertEquals(0, cacheControl.getMaxAge());
        Assert.assertTrue(cacheControl.isNoCache());
    }

    @Test
    public void shouldExtractCacheControl2() {
        CacheControl cacheControl = CacheControlUtil.parseCacheControl("no-transform,public,max-age=300,s-maxage=900");
        Assert.assertEquals(300, cacheControl.getMaxAge());
        Assert.assertEquals(900, cacheControl.getSMaxAge());
        Assert.assertTrue(cacheControl.isNoTransform());
        Assert.assertTrue(cacheControl.isPublic());
    }
}
