/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.discovery.shared.transport;

import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Tomasz Bak
 */
public final class TransportUtils {

    private TransportUtils() {
    }

    //todo 本方法有bug
    /*
    目前该方法存在 BUG ，失败的线程直接返回 existing 的是 null ，需要修改成 return eurekaHttpClientRef.get()
     */
    public static EurekaHttpClient getOrSetAnotherClient(AtomicReference<EurekaHttpClient> eurekaHttpClientRef, EurekaHttpClient another) {
        EurekaHttpClient existing = eurekaHttpClientRef.get();
        // todo 这边设置的比较好 为空才设置
        if (eurekaHttpClientRef.compareAndSet(null, another)) {
            return another;
        }
        // 设置失败，意味着另外一个线程已经设置
        another.shutdown();
        //todo 此处有并发问题，应该改成eurekaHttpClientRef.get()
        return existing;
    }

    public static void shutdown(EurekaHttpClient eurekaHttpClient) {
        if (eurekaHttpClient != null) {
            eurekaHttpClient.shutdown();
        }
    }
}
