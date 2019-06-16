/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.lease;

import com.netflix.eureka.registry.AbstractInstanceRegistry;

/**
 * This class is responsible for creating/renewing and evicting a <em>lease</em>
 * for a particular instance.
 *
 * <p>
 * Leases determine what instances receive traffic. When there is no renewal
 * request from the client, the lease gets expired and the instances are evicted
 * out of {@link AbstractInstanceRegistry}. This is key to instances receiving traffic
 * or not.
 * <p>
 *
 * @author Karthik Ranganathan, Greg Kim
 * 租约管理器接口，提供租约的注册、续租、取消( 主动下线 )、过期( 过期下线 )
 * @param <T>
 */
public interface LeaseManager<T> {

    /**
     * Assign a new {@link Lease} to the passed in {@link T}.
     *
     * @param r
     *            - T to register
     * @param leaseDuration
     * @param isReplication
     *            - whether this is a replicated entry from another eureka node.
     */
    void register(T r, int leaseDuration, boolean isReplication);

    /**
     * Cancel the {@link Lease} associated w/ the passed in <code>appName</code>
     * and <code>id</code>.
     *
     * @param appName
     *            - unique id of the application.
     * @param id
     *            - unique id within appName.
     * @param isReplication
     *            - whether this is a replicated entry from another eureka node.
     * @return true, if the operation was successful, false otherwise.
     */
    boolean cancel(String appName, String id, boolean isReplication);

    /**
     * Renew the {@link Lease} associated w/ the passed in <code>appName</code>
     * and <code>id</code>.
     *
     * @param id
     *            - unique id within appName
     * @param isReplication
     *            - whether this is a replicated entry from another ds node
     * @return whether the operation of successful
     */
    boolean renew(String appName, String id, boolean isReplication);

    /**
     * Evict {@link T}s with expired {@link Lease}(s).
     */
    /*
    计算公式如下：
    expectedNumberOfRenewsPerMin = 当前注册的应用实例数 x 2
    numberOfRenewsPerMinThreshold = expectedNumberOfRenewsPerMin * 续租百分比( eureka.renewalPercentThreshold )
    为什么乘以 2

    默认情况下，注册的应用实例每半分钟续租一次，那么一分钟心跳两次，因此 x 2 。
    这块会有一些硬编码的情况，因此不太建议修改应用实例的续租频率。

    为什么乘以续租百分比

    低于这个百分比，意味着开启自我保护机制。

    默认情况下，eureka.renewalPercentThreshold = 0.85 。

    如果你真的调整了续租频率，可以等比去续租百分比，以保证合适的触发自我保护机制的阀值。另外，你需要注意，续租频率是 Client 级别，续租百分比是 Server 级别。
     */
    void evict();
}
