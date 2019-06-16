package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */

/**
 * 用来将本地instanceinfo更新并复制到远程服务器的task。
 * 这个task的属性是：
 - 配置单个更新线程以保证对远程服务器的顺序更新
 - 可以通过onDemandUpdate（）按需安排更新任务
 - 任务处理速率由burstSize限制
 - 一个新的更新任务总是在老的更新之后被调度。 但是，如果开始on-demand任务，
 则会排除之前的自动更新任务会被丢弃，并且新的更新任务将会在on-demand任务之后调度
 */
//应用实例信息复制器
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    //应用实例信息
    private final InstanceInfo instanceInfo;

    //定时执行频率，单位：秒
    private final int replicationIntervalSeconds;
    //定时执行器
    private final ScheduledExecutorService scheduler;
    //定时执行任务的 Future
    private final AtomicReference<Future> scheduledPeriodicRef;

    //是否开启调度
    private final AtomicBoolean started;
    // 限流相关
    private final RateLimiter rateLimiter;
    // 限流相关 令牌桶上限，默认：2
    private final int burstSize;
    // 限流相关 令牌再装平均速率，默认：60 * 2 / 30 = 4
    private final int allowedRatePerMinute;

    //应用实例信息复制器
    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    //因为 InstanceInfo 刚被创建时，在 Eureka-Server 不存在，也会被注册
    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            // 设置 应用实例信息 数据不一致
            instanceInfo.setIsDirty();  // for initial register
            // 提交任务，并设置该任务的 Future
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
        started.set(false);
    }

    //向 Eureka-Server 发起注册，同步应用实例信息。InstanceInfoReplicator 使用 RateLimiter ，
    //避免状态频繁发生变化，向 Eureka-Server 频繁同步。
    public boolean onDemandUpdate() {
        //若获取成功，向 Eureka-Server 发起注册，同步应用实例信息。
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            //InstanceInfoReplicator 会固定周期检查本地应用实例是否
            // 有没向 Eureka-Server ，若未同步，则发起同步。
            scheduler.submit(new Runnable() {
                @Override
                public void run() {
                    logger.debug("Executing on-demand update of local InstanceInfo");
                    // 取消任务
                    // 取消定时任务，避免无用的注册
                    Future latestPeriodic = scheduledPeriodicRef.get();
                    if (latestPeriodic != null && !latestPeriodic.isDone()) {
                        logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                        latestPeriodic.cancel(false);
                    }
                    // 再次调用
                    InstanceInfoReplicator.this.run();
                }
            });
            return true;
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    //定时检查 InstanceInfo 的状态( status ) 属性是否发生变化。若是，发起注册
    public void run() {
        try {
            // 刷新 应用实例信息
            discoveryClient.refreshInstanceInfo();
            // 判断 应用实例信息 是否数据不一致
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                //真正注册的地方
                discoveryClient.register();
                // 设置 应用实例信息 数据一致
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            // 提交任务，并设置该任务的 Future
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

}