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

package com.netflix.discovery.shared.transport.decorator;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpClientFactory;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.TransportUtils;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.discovery.EurekaClientNames.METRIC_TRANSPORT_PREFIX;

/**
 * {@link SessionedEurekaHttpClient} enforces full reconnect at a regular interval (a session), preventing
 * a client to sticking to a particular Eureka server instance forever. This in turn guarantees even
 * load distribution in case of cluster topology change.
 *
 * @author Tomasz Bak
 */
/*
支持会话的 EurekaHttpClient 。执行定期的重建会话，防止一个 Eureka-Client 永远只连接一个特定的 Eureka-Server 。反过来，这也保证了 Eureka-Server 集群变更时，Eureka-Client 对 Eureka-Server 连接的负载均衡。
 */
public class SessionedEurekaHttpClient extends EurekaHttpClientDecorator {
    private static final Logger logger = LoggerFactory.getLogger(SessionedEurekaHttpClient.class);

    private final Random random = new Random();

    private final String name;
    private final EurekaHttpClientFactory clientFactory;
    private final long sessionDurationMs;
    private volatile long currentSessionDurationMs;

    private volatile long lastReconnectTimeStamp = -1;
    private final AtomicReference<EurekaHttpClient> eurekaHttpClientRef = new AtomicReference<>();

    public SessionedEurekaHttpClient(String name, EurekaHttpClientFactory clientFactory, long sessionDurationMs) {
        this.name = name;
        this.clientFactory = clientFactory;
        this.sessionDurationMs = sessionDurationMs;
        this.currentSessionDurationMs = randomizeSessionDuration(sessionDurationMs);
        Monitors.registerObject(name, this);
    }

    @Override
    protected <R> EurekaHttpResponse<R> execute(RequestExecutor<R> requestExecutor) {
        long now = System.currentTimeMillis();
        // 超过 当前会话时间，关闭当前委托的 EurekaHttpClient 。
        long delay = now - lastReconnectTimeStamp;
        if (delay >= currentSessionDurationMs) {
            logger.debug("Ending a session and starting anew");
            lastReconnectTimeStamp = now;
            /* todo ?
            增加会话过期的随机性，实现所有 Eureka-Client 的会话过期重连的发生时间更加离散，避免集中时间过期。
            目前猜测这么做的目的和 TODO[0028]：写入集群和读取集群 有关，即返回 302 。
             */
            //计算下一次会话超时时长，公式为 sessionDurationMs * (0.5, 1.5)
            currentSessionDurationMs = randomizeSessionDuration(sessionDurationMs);
            TransportUtils.shutdown(eurekaHttpClientRef.getAndSet(null));
        }

        // 获得委托的 EurekaHttpClient 。若不存在，则创建新的委托的 EurekaHttpClient 。
        EurekaHttpClient eurekaHttpClient = eurekaHttpClientRef.get();
        if (eurekaHttpClient == null) {
            /*
            该方法实现，获得 eurekaHttpClientRef 里的 EurekaHttpClient 。若获取不到，
            将 another 设置到 eurekaHttpClientRef 。当有多个线程设置时，有且只有
            一个线程设置成功，另外的设置失败的线程们，
            意味着当前 eurekaHttpClientRef 有 EurekaHttpClient ，返回 eurekaHttpClientRef
             */
            eurekaHttpClient = TransportUtils.getOrSetAnotherClient(eurekaHttpClientRef, clientFactory.newClient());
    }
    //执行请求
        return requestExecutor.execute(eurekaHttpClient);
    }

    @Override
    public void shutdown() {
        if(Monitors.isObjectRegistered(name, this)) {
            Monitors.unregisterObject(name, this);
        }
        TransportUtils.shutdown(eurekaHttpClientRef.getAndSet(null));
    }

    /**
     * @return a randomized sessionDuration in ms calculated as +/- an additional amount in [0, sessionDurationMs/2]
     */
    //计算下一次会话超时时长，公式为 sessionDurationMs * random(0.5, 1.5)
    protected long randomizeSessionDuration(long sessionDurationMs) {
        long delta = (long) (sessionDurationMs * (random.nextDouble() - 0.5));
        return sessionDurationMs + delta;
    }

    @Monitor(name = METRIC_TRANSPORT_PREFIX + "currentSessionDuration",
            description = "Duration of the current session", type = DataSourceType.GAUGE)
    public long getCurrentSessionDuration() {
        return lastReconnectTimeStamp < 0 ? 0 : System.currentTimeMillis() - lastReconnectTimeStamp;
    }
}
