package com.netflix.eureka.cluster;

import java.io.IOException;
import java.util.List;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.cluster.protocol.ReplicationInstance;
import com.netflix.eureka.cluster.protocol.ReplicationInstance.ReplicationInstanceBuilder;
import com.netflix.eureka.cluster.protocol.ReplicationInstanceResponse;
import com.netflix.eureka.cluster.protocol.ReplicationList;
import com.netflix.eureka.cluster.protocol.ReplicationListResponse;
import com.netflix.eureka.util.batcher.TaskProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.cluster.protocol.ReplicationInstance.ReplicationInstanceBuilder.aReplicationInstance;

/**
 * @author Tomasz Bak
 */
//同步操作任务处理器
class ReplicationTaskProcessor implements TaskProcessor<ReplicationTask> {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationTaskProcessor.class);

    private final HttpReplicationClient replicationClient;

    private final String peerId;

    private volatile long lastNetworkErrorTime;

    ReplicationTaskProcessor(String peerId, HttpReplicationClient replicationClient) {
        this.replicationClient = replicationClient;
        this.peerId = peerId;
    }

    //处理单任务，用于 Eureka-Server 向亚马逊 AWS 的 ASG ( Autoscaling Group ) 同步状态
    @Override
    public ProcessingResult process(ReplicationTask task) {
        try {
            EurekaHttpResponse<?> httpResponse = task.execute();
            int statusCode = httpResponse.getStatusCode();
            Object entity = httpResponse.getEntity();
            if (logger.isDebugEnabled()) {
                logger.debug("Replication task {} completed with status {}, (includes entity {})", task.getTaskName(), statusCode, entity != null);
            }
            if (isSuccess(statusCode)) {
                task.handleSuccess();
            } else if (statusCode == 503) {
                logger.debug("Server busy (503) reply for task {}", task.getTaskName());
                return ProcessingResult.Congestion;
            } else {
                task.handleFailure(statusCode, entity);
                return ProcessingResult.PermanentError;
            }
        } catch (Throwable e) {
            if (isNetworkConnectException(e)) {
                logNetworkErrorSample(task, e);
                return ProcessingResult.TransientError;
            } else {
                logger.error(peerId + ": " + task.getTaskName() + "Not re-trying this exception because it does not seem to be a network exception", e);
                return ProcessingResult.PermanentError;
            }
        }
        return ProcessingResult.Success;
    }

    //处理批量任务，用于 Eureka-Server 集群注册信息的同步操作任务，通过调用被同步的 Eureka-Server 的 peerreplication/batch/
    // 接口，一次性将批量( 多个 )的同步操作任务发起请求
    @Override
    public ProcessingResult process(List<ReplicationTask> tasks) {
        // 创建 批量提交同步操作任务的请求对象
        ReplicationList list = createReplicationListOf(tasks);
        try {
            // 发起 批量提交同步操作任务的请求
            EurekaHttpResponse<ReplicationListResponse> response = replicationClient.submitBatchUpdates(list);
            // 处理 批量提交同步操作任务的响应
            int statusCode = response.getStatusCode();
            //判断请求是否成功，响应状态码是否在 [200, 300) 范围内
            if (!isSuccess(statusCode)) {
                //状态码 503 ，目前 Eureka-Server 返回 503 的原因是被限流。
                if (statusCode == 503) {
                    logger.warn("Server busy (503) HTTP status code received from the peer {}; rescheduling tasks after delay", peerId);
                    return ProcessingResult.Congestion;
                } else {
                    //Eureka-Server 在代码上看下来，不会返回这样的状态码。该情况为永久错误,不会重试
                    // Unexpected error returned from the server. This should ideally never happen.
                    logger.error("Batch update failure with HTTP status code {}; discarding {} replication tasks", statusCode, tasks.size());
                    return ProcessingResult.PermanentError;
                }
            } else {
                /*
                请求成功，调用 #handleBatchResponse(...) 方法，逐个处理每个 ReplicationTask 和
                ReplicationInstanceResponse 。这里有一点要注意下，之前请求成功指的是整个请求成功，
                实际每个 ReplicationInstanceResponse 可能返回的状态码不在 [200, 300) 范围内。
                 */
                handleBatchResponse(tasks, response.getEntity().getResponseList());
            }
        } catch (Throwable e) {
            if (isNetworkConnectException(e)) {
                logNetworkErrorSample(null, e);
                return ProcessingResult.TransientError;
            } else {
                logger.error("Not re-trying this exception because it does not seem to be a network exception", e);
                return ProcessingResult.PermanentError;
            }
        }
        return ProcessingResult.Success;
    }

    /**
     * We want to retry eagerly, but without flooding log file with tons of error entries.
     * As tasks are executed by a pool of threads the error logging multiplies. For example:
     * 20 threads * 100ms delay == 200 error entries / sec worst case
     * Still we would like to see the exception samples, so we print samples at regular intervals.
     */
    private void logNetworkErrorSample(ReplicationTask task, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - lastNetworkErrorTime > 10000) {
            lastNetworkErrorTime = now;
            StringBuilder sb = new StringBuilder();
            sb.append("Network level connection to peer ").append(peerId);
            if (task != null) {
                sb.append(" for task ").append(task.getTaskName());
            }
            sb.append("; retrying after delay");
            logger.error(sb.toString(), e);
        }
    }

    private void handleBatchResponse(List<ReplicationTask> tasks, List<ReplicationInstanceResponse> responseList) {
        if (tasks.size() != responseList.size()) {
            // This should ideally never happen unless there is a bug in the software.
            logger.error("Batch response size different from submitted task list ({} != {}); skipping response analysis", responseList.size(), tasks.size());
            return;
        }
        //逐个处理每个 ReplicationTask 和ReplicationInstanceResponse 。
        for (int i = 0; i < tasks.size(); i++) {
            handleBatchResponse(tasks.get(i), responseList.get(i));
        }
    }

    private void handleBatchResponse(ReplicationTask task, ReplicationInstanceResponse response) {
        // 执行成功
        int statusCode = response.getStatusCode();
        if (isSuccess(statusCode)) {
            task.handleSuccess();
            return;
        }
        // 执行失败
        try {
            task.handleFailure(response.getStatusCode(), response.getResponseEntity());
        } catch (Throwable e) {
            logger.error("Replication task " + task.getTaskName() + " error handler failure", e);
        }
    }

    private ReplicationList createReplicationListOf(List<ReplicationTask> tasks) {
        ReplicationList list = new ReplicationList();
        for (ReplicationTask task : tasks) {
            // Only InstanceReplicationTask are batched.
            list.addReplicationInstance(createReplicationInstanceOf((InstanceReplicationTask) task));
        }
        return list;
    }

    //请求是否成功，[200,300)
    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Check if the exception is some sort of network timeout exception (ie)
     * read,connect.
     *
     * @param e
     *            The exception for which the information needs to be found.
     * @return true, if it is a network timeout, false otherwise.
     */
    //todo 网络异常
    private static boolean isNetworkConnectException(Throwable e) {
        do {
            if (IOException.class.isInstance(e)) {
                return true;
            }
            e = e.getCause();
        } while (e != null);
        return false;
    }

    private static ReplicationInstance createReplicationInstanceOf(InstanceReplicationTask task) {
        ReplicationInstanceBuilder instanceBuilder = aReplicationInstance();
        instanceBuilder.withAppName(task.getAppName());
        instanceBuilder.withId(task.getId());
        InstanceInfo instanceInfo = task.getInstanceInfo();
        if (instanceInfo != null) {
            String overriddenStatus = task.getOverriddenStatus() == null ? null : task.getOverriddenStatus().name();
            instanceBuilder.withOverriddenStatus(overriddenStatus);
            instanceBuilder.withLastDirtyTimestamp(instanceInfo.getLastDirtyTimestamp());
            if (task.shouldReplicateInstanceInfo()) {
                instanceBuilder.withInstanceInfo(instanceInfo);
            }
            String instanceStatus = instanceInfo.getStatus() == null ? null : instanceInfo.getStatus().name();
            instanceBuilder.withStatus(instanceStatus);
        }
        instanceBuilder.withAction(task.getAction());
        return instanceBuilder.build();
    }
}

