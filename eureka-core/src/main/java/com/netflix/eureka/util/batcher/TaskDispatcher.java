package com.netflix.eureka.util.batcher;

/**
 * Task dispatcher takes task from clients, and delegates their execution to a configurable number of workers.
 * The task can be processed one at a time or in batches. Only non-expired tasks are executed, and if a newer
 * task with the same id is scheduled for execution, the old one is deleted. Lazy dispatch of work (only on demand)
 * to workers, guarantees that data are always up to date, and no stale task processing takes place.
 * <h3>Task processor</h3>
 * A client of this component must provide an implementation of {@link TaskProcessor} interface, which will do
 * the actual work of task processing. This implementation must be thread safe, as it is called concurrently by
 * multiple threads.
 * <h3>Execution modes</h3>
 * To create non batched executor call {@link TaskDispatchers#createNonBatchingTaskDispatcher(String, int, int, long, long, TaskProcessor)}
 * method. Batched executor is created by {@link TaskDispatchers#createBatchingTaskDispatcher(String, int, int, int, long, long, TaskProcessor)}.
 *
 * 任务分发器接口，从客户端获取任务，并代理到可配数量的执行器中。任务可以一次处理一个或者批量处理，只有未过期的任务可以被执行，并且
 * 如果有一个新任务有和老任务相同的id，老任务会被删除。任务懒派发（只在需要时派发），保证了数据的即使更新，并且没有过期任务会被执行
 * 任务处理器
 * 该组件的客户端必须提供一个TaskProcessor接口的实现类，该实现类执行具体任务。该实现类必须是线程安全的，因为它会被多个线程并发调用。
 * 执行模式
 * 要创建非批量任务分发器，调用TaskDispatchers.createNonBatchingTaskDispatcher,要创建批量接口，调用TaskDispatchers.createBatchingTaskDispatcher
 * 任务分发器接口
 * @author Tomasz Bak
 */
public interface TaskDispatcher<ID, T> {

    //提交任务编号，任务，任务过期时间给任务分发器处理
    void process(ID id, T task, long expiryTime);

    void shutdown();
}
