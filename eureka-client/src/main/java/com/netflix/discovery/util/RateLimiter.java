/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.discovery.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate limiter implementation is based on token bucket algorithm. There are two parameters:
 * <ul>
 * <li>
 *      允许作为突发事件进入系统的最大请求数
 *     burst size - maximum number of requests allowed into the system as a burst
 * </li>
 * <li>
 *     预期的每秒请求数（RateLimiters也支持使用分钟为单位）
 *     average rate - expected number of requests per second (RateLimiters using MINUTES is also supported)
 * </li>
 * </ul>
 *
 * @author Tomasz Bak
 */

/**
 * 对于很多应用场景来说，除了要求能够限制数据的平均传输速率外，还要求允许某种程度的突发传输。
 * 这时候漏桶算法可能就不合适了，令牌桶算法更为适合。如图所示，令牌桶算法的原理是系统会以一个恒定的速度往桶里放入令牌，
 * 而如果请求需要被处理，则需要先从桶里获取一个令牌，当桶里没有令牌可取时，则拒绝服务。
 */
//todo ？ 如果超过一定时间，是否是固定令牌
public class RateLimiter {

    //限流时间单位
    private final long rateToMsConversion;

    //得到当前已消费的令牌数量
    private final AtomicInteger consumedTokens = new AtomicInteger();
    //上一次填充令牌的时间戳
    private final AtomicLong lastRefillTime = new AtomicLong(0);

    //限流时间单位可设置为TimeUnit.SECONDS,已废弃
    @Deprecated
    public RateLimiter() {
        this(TimeUnit.SECONDS);
    }

    //限流时间单位可设置为TimeUnit.SECONDS或TimeUnit.MINUTES
    public RateLimiter(TimeUnit averageRateUnit /*速率单位。构造方法里将 averageRateUnit 转换成 rateToMsConversion*/) {
        switch (averageRateUnit) {
            case SECONDS: // 秒级
                rateToMsConversion = 1000;
                break;
            case MINUTES: // 分钟级
                rateToMsConversion = 60 * 1000;
                break;
            default:
                throw new IllegalArgumentException("TimeUnit of " + averageRateUnit + " is not supported");
        }
    }

    /**
     * 获取令牌( Token )
     *
     * @param burstSize 令牌桶上限
     * @param averageRate 令牌再装平均速率
     * @return 是否获取成功
     */
    //这个方法默认传的当前系统时间戳
    public boolean acquire(int burstSize, long averageRate) {
        return acquire(burstSize, averageRate, System.currentTimeMillis());
    }

    public boolean acquire(int burstSize, long averageRate, long currentTimeMillis) {
        //这里为了避免傻白甜将burstSize和averageRate设为负值而抛出异常
        if (burstSize <= 0 || averageRate <= 0) { // Instead of throwing exception, we just let all the traffic go
            return true;
        }

        //填充令牌
        refillToken(burstSize, averageRate, currentTimeMillis);
        //消费令牌成功与否
        return consumeToken(burstSize);
    }

    /*
    调用 #refillToken(...) 方法，填充已消耗的令牌。可能很多同学开始和我想的一样，
    一个后台每毫秒执行填充。为什么不适合这样呢？一方面，实际项目里每个接口都会有
    相应的 RateLimiter ，导致太多执行频率极高的后台任务；另一方面，获取令牌时才计算，
    多次令牌填充可以合并成一次，减少冗余和无效的计算。
     */
    private void refillToken(int burstSize, long averageRate, long currentTimeMillis) {
        //得到上一次填充令牌的时间戳
        long refillTime = lastRefillTime.get();
        //时间间隔timeDelta = 传进来的时间戳currentTimeMillis - 上一次填充令牌的时间戳refillTime
        long timeDelta = currentTimeMillis - refillTime;

        //计算出新的令牌数量newTokens = 时间间隔 * 平均速率 / 限流时间单位
        /*
        这就是基于令牌桶算法的限流的特点：让流量平稳，而不是瞬间流量。1000 QPS 相对平均的分摊在这一秒内，而不是第 1 ms 999
        请求，后面 999 ms 0 请求
         */
        long newTokens = timeDelta * averageRate / rateToMsConversion;
        //如果新的令牌数量大于0个
        if (newTokens > 0) {
            //设置新的填充令牌时间戳newRefillTime，如果上一次填充令牌的时间戳==0就取传进来的currentTimeMillis，如果！=0，
            //就等于上一次填充令牌的时间戳 + 新的令牌数量 * 限流时间单位 / 平均速率
            long newRefillTime = refillTime == 0
                    ? currentTimeMillis
                    : refillTime + newTokens * rateToMsConversion / averageRate;
            //如果lastRefillTime内存偏移量值==上一次填充令牌的时间戳refillTime，则将lastRefillTime内存值设置为新的填充令牌时间戳newRefillTime
            //成功时进入条件体放令牌
            // CAS 保证有且仅有一个线程进入填充
            if (lastRefillTime.compareAndSet(refillTime, newRefillTime)) {
                //放令牌（核心代码）
                while (true) { // 死循环，直到成功
                    //得到当前已消费的令牌数量currentLevel
                    int currentLevel = consumedTokens.get();
                    //获取校正令牌数量adjustedLevel，从当前已消费的令牌数量currentLevel和允许最大请求数burstSize间取小者，
                    //以防允许最大请求数burstSize变小（如果不这样做，需要等到token积累到一定数量才能继续释放流量）,叫做“流量削峰”
                    //todo 
                    int adjustedLevel = Math.min(currentLevel, burstSize); // In case burstSize decreased
                    //获取新的令牌数量newLevel，0 与 （校正值 - 计算值）之间取大者
                    int newLevel = (int) Math.max(0, adjustedLevel - newTokens);
                    //如果当前已消费的令牌内存偏移量等于consumedTokens等于currentLevel，则将已消费的令牌量consumedTokens设置为新的令牌数量newLevel
                    //终止放令牌,在已消费偏移量不等于currentLevel时循环计算，直到它们相等
                    if (consumedTokens.compareAndSet(currentLevel, newLevel)) {
                        return;
                    }
                }
            }
        }
    }
    //消费令牌，传入突发量
    private boolean consumeToken(int burstSize) {
        // 死循环，直到没有令牌，或者获取令牌成功
        //取令牌
        while (true) {
            // 没有令牌
            //得到当前已消费的令牌数量currentLevel
            int currentLevel = consumedTokens.get();
            //如果已消费令牌量大于等于突发量，则不能消费令牌
            //已消费令牌数超过突发数，返回false
            if (currentLevel >= burstSize) {
                return false;
            }
            // CAS 避免和正在消费令牌或者填充令牌的线程冲突
            //消费令牌，已消费令牌量+1
            if (consumedTokens.compareAndSet(currentLevel, currentLevel + 1)) {
                return true;
            }
        }
    }

    //重置令牌桶
    public void reset() {
        consumedTokens.set(0);
        lastRefillTime.set(0);
    }
}
