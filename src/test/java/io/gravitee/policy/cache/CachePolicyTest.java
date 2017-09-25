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

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.HashMap;

import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class CachePolicyTest {

    @Mock
    protected ProxyResponse response;

    @Before
    public void init() {
        initMocks(this);
    }

    @Test
    public void should_usecachecontrol_smaxage() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(HttpHeaders.CACHE_CONTROL, "max-age=600, no-cache, no-store, smax-age=300");
                put(HttpHeaders.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");
            }
        });

        when(response.headers()).thenReturn(headers);

        long timeToLive = CachePolicy.timeToLiveFromResponse(response);
        Assert.assertEquals(300, timeToLive);
    }

    @Test
    public void should_usecachecontrol_maxage() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(HttpHeaders.CACHE_CONTROL, "max-age=600, no-cache, no-store");
                put(HttpHeaders.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");
            }
        });

        when(response.headers()).thenReturn(headers);

        long timeToLive = CachePolicy.timeToLiveFromResponse(response);
        Assert.assertEquals(600, timeToLive);
    }

    @Test
    public void should_usecachecontrol_expires_past() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
                put(HttpHeaders.EXPIRES, "Thu, 01 Dec 1994 16:00:00 GMT");
            }
        });

        when(response.headers()).thenReturn(headers);

        long timeToLive = CachePolicy.timeToLiveFromResponse(response);
        Assert.assertEquals(-1, timeToLive);
    }
}
