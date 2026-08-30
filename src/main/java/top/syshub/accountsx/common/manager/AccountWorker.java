package top.syshub.accountsx.common.manager;

import top.syshub.accountsx.common.task.TaskScheduler;

import java.util.concurrent.CompletableFuture;

/**
 * 任务调度的旧入口，已重写为 {@link TaskScheduler}（core/task，P1.2）。
 *
 * <p>保留本类仅为兼容仍 import 了 {@code AccountWorker} 的 MC 适配器；新代码请直接使用
 * {@link TaskScheduler}。{@code registerWorkerThread}/{@code unregisterWorkerThread} 已从公开 API
 * 删除——线程归属改由 {@link TaskScheduler} 内部的 {@link ThreadLocal} 打标。
 *
 * @deprecated 改用 {@link TaskScheduler}
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public final class AccountWorker {
    /**
     * @deprecated 改用 {@link TaskScheduler.Task}
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public interface Task {
        void run() throws Exception;
    }

    private AccountWorker() {
    }

    /**
     * @deprecated 改用 {@link TaskScheduler#submit(TaskScheduler.Task)}
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static CompletableFuture<Void> submit(Task task) {
        return TaskScheduler.submit(task::run);
    }

    /**
     * @deprecated 改用 {@link TaskScheduler#isRunning()}
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static boolean isRunning() {
        return TaskScheduler.isRunning();
    }

    /**
     * @deprecated 改用 {@link TaskScheduler#isWorkerThread(Thread)}
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public static boolean isWorkerThread(Thread t) {
        return TaskScheduler.isWorkerThread(t);
    }
}
