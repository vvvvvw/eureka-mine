package com.netflix.eureka.util.batcher;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.netflix.eureka.util.batcher.TaskProcessor.ProcessingResult;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.annotations.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.StatsTimer;
import com.netflix.servo.monitor.Timer;
import com.netflix.servo.stats.StatsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.Names.METRIC_REPLICATION_PREFIX;

/**
 * An active object with an internal thread accepting tasks from clients, and dispatching them to
 * workers in a pull based manner. Workers explicitly request an item or a batch of items whenever they are
 * available. This guarantees that data to be processed are always up to date, and no stale data processing is done.
 *
 * <h3>Task identification</h3>
 * Each task passed for processing has a corresponding task id. This id is used to remove duplicates (replace
 * older copies with newer ones).
 *
 * <h3>Re-processing</h3>
 * If data processing by a worker failed, and the failure is transient in nature, the worker will put back the
 * task(s) back to the {@link AcceptorExecutor}. This data will be merged with current workload, possibly discarded if
 * a newer version has been already received.
 *
 * 任务接收执行器
 * @author Tomasz Bak
 */
class AcceptorExecutor<ID, T> {

    private static final Logger logger = LoggerFactory.getLogger(AcceptorExecutor.class);

    //待执行队列最大数量
    private final int maxBufferSize;
    //单个批量任务包含任务最大数量
    private final int maxBatchingSize;
    //批量任务等待最大延迟时长，单位：毫秒
    private final long maxBatchingDelay;

    // 是否关闭
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    //接收任务队列，队尾是新的任务
    private final BlockingQueue<TaskHolder<ID, T>> acceptorQueue = new LinkedBlockingQueue<>();
    //重新执行任务队列，队尾是新的任务
    private final BlockingDeque<TaskHolder<ID, T>> reprocessQueue = new LinkedBlockingDeque<>();
    //接收任务线程
    private final Thread acceptorThread;

    //待执行任务映射
    private final Map<ID, TaskHolder<ID, T>> pendingTasks = new HashMap<>();
    //待执行队列
    private final Deque<ID> processingOrder = new LinkedList<>();

    //单任务工作请求信号量
    private final Semaphore singleItemWorkRequests = new Semaphore(0);
    //单任务工作队列
    private final BlockingQueue<TaskHolder<ID, T>> singleItemWorkQueue = new LinkedBlockingQueue<>();

    //批量任务工作请求信号量
    private final Semaphore batchWorkRequests = new Semaphore(0);
    //批量任务工作队列
    private final BlockingQueue<List<TaskHolder<ID, T>>> batchWorkQueue = new LinkedBlockingQueue<>();

    //todo 网络通信整形器 网络通信整形器。当任务执行发生请求限流，或是请求网络失败的情况，则延时 AcceptorRunner 将任务提交到工作任务队列，从而避免任务很快去执行，再次发生上述情况。
    private final TrafficShaper trafficShaper;

    /*
     * Metrics
     */
    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptedTasks", description = "Number of accepted tasks", type = DataSourceType.COUNTER)
    volatile long acceptedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "replayedTasks", description = "Number of replayedTasks tasks", type = DataSourceType.COUNTER)
    volatile long replayedTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "expiredTasks", description = "Number of expired tasks", type = DataSourceType.COUNTER)
    volatile long expiredTasks;

    @Monitor(name = METRIC_REPLICATION_PREFIX + "overriddenTasks", description = "Number of overridden tasks", type = DataSourceType.COUNTER)
    volatile long overriddenTasks;

    //待执行队列溢出数
    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueOverflows", description = "Number of queue overflows", type = DataSourceType.COUNTER)
    volatile long queueOverflows;

    private final Timer batchSizeMetric;

    AcceptorExecutor(String id,
                     int maxBufferSize,
                     int maxBatchingSize,
                     long maxBatchingDelay,
                     long congestionRetryDelayMs,
                     long networkFailureRetryMs) {
        this.maxBufferSize = maxBufferSize;
        this.maxBatchingSize = maxBatchingSize;
        this.maxBatchingDelay = maxBatchingDelay;
        // 创建 网络通信整形器
        this.trafficShaper = new TrafficShaper(congestionRetryDelayMs, networkFailureRetryMs);

        ThreadGroup threadGroup = new ThreadGroup("eurekaTaskExecutors");
        // 创建 接收任务线程
        this.acceptorThread = new Thread(threadGroup, new AcceptorRunner(), "TaskAcceptor-" + id);
        this.acceptorThread.setDaemon(true);
        this.acceptorThread.start();

        final double[] percentiles = {50.0, 95.0, 99.0, 99.5};
        final StatsConfig statsConfig = new StatsConfig.Builder()
                .withSampleSize(1000)
                .withPercentiles(percentiles)
                .withPublishStdDev(true)
                .build();
        final MonitorConfig config = MonitorConfig.builder(METRIC_REPLICATION_PREFIX + "batchSize").build();
        this.batchSizeMetric = new StatsTimer(config, statsConfig);
        try {
            Monitors.registerObject(id, this);
        } catch (Throwable e) {
            logger.warn("Cannot register servo monitor for this object", e);
        }
    }

    void process(ID id, T task, long expiryTime) {
        acceptorQueue.add(new TaskHolder<ID, T>(id, task, expiryTime));
        acceptedTasks++;
    }

    void reprocess(List<TaskHolder<ID, T>> holders, ProcessingResult processingResult) {
        // 添加到 重新执行队列
        reprocessQueue.addAll(holders);
        // 监控相关
        replayedTasks += holders.size();
        // 提交任务结果给 TrafficShaper
        trafficShaper.registerFailure(processingResult);
    }

    void reprocess(TaskHolder<ID, T> taskHolder, ProcessingResult processingResult) {
        reprocessQueue.add(taskHolder);
        replayedTasks++;
        trafficShaper.registerFailure(processingResult);
    }

    BlockingQueue<TaskHolder<ID, T>> requestWorkItem() {
        singleItemWorkRequests.release();
        return singleItemWorkQueue;
    }

    //发起请求信号量，并获得批量任务的工作队列
    BlockingQueue<List<TaskHolder<ID, T>>> requestWorkItems() {
        batchWorkRequests.release();
        return batchWorkQueue;
    }

    void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            acceptorThread.interrupt();
        }
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "acceptorQueueSize", description = "Number of tasks waiting in the acceptor queue", type = DataSourceType.GAUGE)
    public long getAcceptorQueueSize() {
        return acceptorQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "reprocessQueueSize", description = "Number of tasks waiting in the reprocess queue", type = DataSourceType.GAUGE)
    public long getReprocessQueueSize() {
        return reprocessQueue.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "queueSize", description = "Task queue size", type = DataSourceType.GAUGE)
    public long getQueueSize() {
        return pendingTasks.size();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "pendingJobRequests", description = "Number of worker threads awaiting job assignment", type = DataSourceType.GAUGE)
    public long getPendingJobRequests() {
        return singleItemWorkRequests.availablePermits() + batchWorkRequests.availablePermits();
    }

    @Monitor(name = METRIC_REPLICATION_PREFIX + "availableJobs", description = "Number of jobs ready to be taken by the workers", type = DataSourceType.GAUGE)
    public long workerTaskQueueSize() {
        return singleItemWorkQueue.size() + batchWorkQueue.size();
    }

    class AcceptorRunner implements Runnable {
        @Override
        public void run() {
            long scheduleTime = 0;
            while (!isShutdown.get()) {
                try {
                    // 处理完输入队列( 接收队列 + 重新执行队列 )
                    drainInputQueues();

                    // 待执行任务数量
                    int totalItems = processingOrder.size();
                    // 计算调度时间
                    /*
                    计算可调度任务的最小时间( scheduleTime )。
                    当 scheduleTime 小于当前时间，不重新计算，即此时需要延迟等待调度。
                    当 scheduleTime 大于等于当前时间，配合 TrafficShaper#transmissionDelay(...) 重新计算
                     */
                    long now = System.currentTimeMillis();
                    if (scheduleTime < now) {
                        scheduleTime = now + trafficShaper.transmissionDelay();
                    }
                    // 调度
                    if (scheduleTime <= now) {
                        // 调度批量任务
                        assignBatchWork();
                        // 调用 #assignSingleItemWork() 方法，调度单任务
                        assignSingleItemWork();
                    }

                    // 1）任务执行器无任务请求，正在忙碌处理之前的任务；或者 2）任务延迟调度。睡眠 10 秒，避免资源浪费。
                    // If no worker is requesting data or there is a delay injected by the traffic shaper,
                    // sleep for some time to avoid tight loop.
                    if (totalItems == processingOrder.size()) {
                        Thread.sleep(10);
                    }
                } catch (InterruptedException ex) {
                    // Ignore
                } catch (Throwable e) {
                    //防御编程
                    // Safe-guard, so we never exit this loop in an uncontrolled way.
                    logger.warn("Discovery AcceptorThread error", e);
                }
            }
        }

        //判断待执行队列最大长度是否已经达到maxBufferSize
        private boolean isFull() {
            return pendingTasks.size() >= maxBufferSize;
        }

        //将reprocessQueue和acceptorQueue的元素增加入processingOrder队列中
        private void drainInputQueues() throws InterruptedException {
            do {
                // 处理完重新执行队列
                drainReprocessQueue();
                // 处理完接收队列
                drainAcceptorQueue();

                if (!isShutdown.get()) {
                    // 所有队列为空，等待 10 ms，看接收队列是否有新任务
                    // If all queues are empty, block for a while on the acceptor queue
                    if (reprocessQueue.isEmpty() && acceptorQueue.isEmpty() && pendingTasks.isEmpty()) {
                        TaskHolder<ID, T> taskHolder = acceptorQueue.poll(10, TimeUnit.MILLISECONDS);
                        if (taskHolder != null) {
                            appendTaskHolder(taskHolder);
                        }
                    }
                }
                /*
                直到同时满足如下全部条件：
                重新执行队列( reprocessQueue ) 和接收队列( acceptorQueue )为空
                待执行任务映射( pendingTasks )不为空
                 */
            } while (!reprocessQueue.isEmpty() || !acceptorQueue.isEmpty() || pendingTasks.isEmpty());
        }

        //将acceptorQueue队列中的task存入待执行队列中
        private void drainAcceptorQueue() {
            while (!acceptorQueue.isEmpty()) { // 循环，直到接收队列为空
                appendTaskHolder(acceptorQueue.poll());
            }
        }

        //将reprocessQueue队列中的task存入待执行队列中，如果待执行队列满了，则去除reprocessQueue队列中其他元素
        private void drainReprocessQueue() {
            long now = System.currentTimeMillis();
            while (!reprocessQueue.isEmpty() && !isFull()) {
                //优先从重新执行任务的队尾拿较新的任务，从而实现保留更新的任务在待执行任务映射( pendingTasks ) 里
                TaskHolder<ID, T> taskHolder = reprocessQueue.pollLast();
                ID id = taskHolder.getId();
                if (taskHolder.getExpiryTime() <= now) { // 过期
                    expiredTasks++;
                } else if (pendingTasks.containsKey(id)) { // 已存在
                    overriddenTasks++;
                } else {
                    pendingTasks.put(id, taskHolder);
                    processingOrder.addFirst(id); // 提交到队头
                }
            }
            // 如果待执行队列已满，清空重新执行队列，放弃较早的任务
            if (isFull()) {
                //监控
                queueOverflows += reprocessQueue.size();
                //reprocessQueue清空
                reprocessQueue.clear();
            }
        }

        //将任务添加入待执行队列，如果待执行队列满了，则去除待执行队列中已有的元素，再添加进去
        private void appendTaskHolder(TaskHolder<ID, T> taskHolder) {
            // 如果待执行队列已满，移除待处理队列，放弃较早的任务
            if (isFull()) {
                pendingTasks.remove(processingOrder.poll());
                queueOverflows++;
            }
            // 添加到待执行队列
            TaskHolder<ID, T> previousTask = pendingTasks.put(taskHolder.getId(), taskHolder);
            if (previousTask == null) {
                processingOrder.add(taskHolder.getId());
            } else {
                overriddenTasks++;
            }
        }

        void assignSingleItemWork() {
            // 待执行任队列不为空
            if (!processingOrder.isEmpty()) {
                // 获取 单任务工作请求信号量
                if (singleItemWorkRequests.tryAcquire(1)) {
                    // 【循环】获取单任务
                    long now = System.currentTimeMillis();
                    while (!processingOrder.isEmpty()) { // 一定不为空
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) {
                            singleItemWorkQueue.add(holder);
                            return;
                        }
                        expiredTasks++;
                    }
                    // 获取不到单任务，释放请求信号量
                    singleItemWorkRequests.release();
                }
            }
        }

        void assignBatchWork() {
            if (hasEnoughTasksForNextBatch()) {
                // 获取批量任务工作请求信号量( batchWorkRequests ) 。在任务执行器的批量任务执行器，每次执行时，发出 batchWorkRequests 。每一个信号量需要保证获取到一个批量任务
                if (batchWorkRequests.tryAcquire(1)) {
                    // 获取批量任务
                    long now = System.currentTimeMillis();
                    int len = Math.min(maxBatchingSize, processingOrder.size());
                    List<TaskHolder<ID, T>> holders = new ArrayList<>(len);
                    while (holders.size() < len && !processingOrder.isEmpty()) {
                        ID id = processingOrder.poll();
                        TaskHolder<ID, T> holder = pendingTasks.remove(id);
                        if (holder.getExpiryTime() > now) { // 过期
                            holders.add(holder);
                        } else {
                            expiredTasks++;
                        }
                    }
                    // 未调度到批量任务，释放请求信号量，代表请求实际未完成，每一个信号量需要保证获取到一个批量任务。
                    if (holders.isEmpty()) {
                        batchWorkRequests.release();
                    } else {
                        batchSizeMetric.record(holders.size(), TimeUnit.MILLISECONDS);
                        // 添加批量任务到批量任务工作队列
                        batchWorkQueue.add(holders);
                    }
                }
            }
        }

        /*
        判断是否有足够任务进行下一次批量任务调度
        1）待执行任务( processingOrder )映射已满；或者 2）到达批量任务处理最大等待延迟（// 到达批量任务处理最大等待延迟( 通过待处理队列的头部任务的提交时间判断 )）
         */
        private boolean hasEnoughTasksForNextBatch() {
            // 待执行队列为空
            if (processingOrder.isEmpty()) {
                return false;
            }
            // 待执行任务映射已满
            if (pendingTasks.size() >= maxBufferSize) {
                return true;
            }
            // 到达批量任务处理最大等待延迟( 通过待处理队列的头部任务的提交时间判断 )
            TaskHolder<ID, T> nextHolder = pendingTasks.get(processingOrder.peek());
            long delay = System.currentTimeMillis() - nextHolder.getSubmitTimestamp();
            return delay >= maxBatchingDelay;
        }
    }
}
