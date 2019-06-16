package com.netflix.eureka.cluster;

import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all replication tasks.
 */
//同步操作任务
/*
定义了 #getTaskName() 抽象方法。
定义了 #execute() 抽象方法，执行同步任务。
实现了 #handleSuccess() 方法，处理成功执行同步结果。
实现了 #handleFailure(...) 方法，处理失败执行同步结果。
 */
abstract class ReplicationTask {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationTask.class);

    protected final String peerNodeName;
    protected final Action action;

    ReplicationTask(String peerNodeName, Action action) {
        this.peerNodeName = peerNodeName;
        this.action = action;
    }

    public abstract String getTaskName();

    public Action getAction() {
        return action;
    }

    public abstract EurekaHttpResponse<?> execute() throws Throwable;

    public void handleSuccess() {
    }
    //有两个同步操作任务重写,cancle/Heartbeat
    public void handleFailure(int statusCode, Object responseEntity) throws Throwable {
        logger.warn("The replication of task {} failed with response code {}", getTaskName(), statusCode);
    }
}
