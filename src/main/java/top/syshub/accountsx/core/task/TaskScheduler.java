package top.syshub.accountsx.core.task;

import org.jspecify.annotations.NonNull;
import top.syshub.accountsx.core.AccountsX;
import top.syshub.accountsx.core.accounts.model.PlayerNoLongerExistedException;
import top.syshub.accountsx.core.adapters.Platforms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务调度器（P1.2 重写，原 {@code AccountWorker}）。
 *
 * <p>两条通道：
 * <ul>
 *   <li><b>串行通道</b>：单守护线程消费 {@link LinkedBlockingQueue}（{@code take()} 阻塞，替代旧的
 *       {@code poll()} + {@code sleep(100)} 轮询），保证写操作等保序任务按提交顺序执行（1.5 修复）。</li>
 *   <li><b>并行通道</b>：有界线程池（上限 4，惰性创建、空闲回收），用于相互独立的刷新任务（1.6 修复）。</li>
 * </ul>
 *
 * <p>线程归属用 {@link ThreadLocal} 在任务执行时打标，取代旧的全局
 * {@code registerWorkerThread}/{@code unregisterWorkerThread} 注册表 hack。{@link #isWorkerThread(Thread)}
 * 仅在任务执行期间对执行线程返回 true，供 {@code Threading.checkAccountWorkerThread()} 校验。
 *
 * <p>中断语义（1.4 修复）：中断表示「关闭中」。串行线程在 {@link BlockingQueue#take()} 上被中断时静默退出，
 * <b>不</b>升级为游戏崩溃；任务自身抛 {@link InterruptedException} 时按取消处理，不冒泡到线程边界。
 *
 * <p>对外返回 {@link CompletableFuture}，让 UI 层可组合，取代手工 {@code submit(() -> { … minecraft.schedule(…) })} 嵌套。
 */
public final class TaskScheduler {

    /** 可取消、可抛异常的任务（替代 {@code AccountWorker.Task}）。 */
    public interface Task {
        void run() throws Exception;
    }

    private TaskScheduler() {
    }

    /** UI 显示「正在操作」前的延迟（与旧实现一致，避免短任务闪烁）。 */
    private static final int TASK_DISPLAY_DELAY_MS = 100;

    /** 有界并行池上限（与「min(4, accounts)」语义一致：至多 4 个刷新并发）。 */
    private static final int PARALLEL_MAX = 4;

    /** 标记当前线程是否正在执行本调度器的任务。 */
    private static final ThreadLocal<Boolean> ON_WORKER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final BlockingQueue<Runnable> serialQueue = new LinkedBlockingQueue<>();

    private static volatile boolean serialBusy = false;
    private static volatile long serialStart = -1L;

    private static final AtomicInteger parallelInFlight = new AtomicInteger();
    private static volatile long parallelStart = -1L;

    private static final ThreadPoolExecutor parallelPool = newParallelPool();

    private static ThreadPoolExecutor newParallelPool() {
        // core == max == PARALLEL_MAX：至多 4 并发；LinkedBlockingQueue 让超额任务排队而非拒绝。
        // allowCoreThreadTimeOut(true)：空闲线程在 keepAlive 后回收，避免常驻 4 个空闲线程。
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                PARALLEL_MAX, PARALLEL_MAX,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new WorkerThreadFactory());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(null, r, "AccountsX Parallel Refresh " + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    static {
        Thread worker = new Thread(null, TaskScheduler::runSerialLoop, "AccountsX Background Worker Thread");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runSerialLoop() {
        // 串行线程自身始终算 worker 线程（即便空闲时无任务在跑）。
        ON_WORKER.set(true);
        while (true) {
            Runnable task;
            try {
                task = serialQueue.take();
            } catch (InterruptedException ie) {
                // 外部中断表示关闭中：静默退出，绝不调用 crash()（1.4 修复）。
                Thread.currentThread().interrupt();
                return;
            }

            serialBusy = true;
            serialStart = System.currentTimeMillis();
            try {
                task.run();
            } finally {
                serialBusy = false;
            }
        }
    }

    /** 提交一个保序任务到串行通道，返回的 future 在任务结束后完成。 */
    public static CompletableFuture<Void> submit(Task task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        serialQueue.add(() -> executeWrapped(task, future));
        return future;
    }

    /** 提交一个独立任务到有界并行池，返回的 future 在任务结束后完成。 */
    public static CompletableFuture<Void> submitParallel(Task task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (parallelInFlight.getAndIncrement() == 0) {
            parallelStart = System.currentTimeMillis();
        }
        parallelPool.execute(() -> {
            try {
                executeWrapped(task, future);
            } finally {
                if (parallelInFlight.decrementAndGet() == 0) {
                    parallelStart = -1L;
                }
            }
        });
        return future;
    }

    /**
     * 批量并行执行相互独立的任务，全部完成后整体 future 才完成。任一个任务失败会让整体 future 异常完成，
     * 但每个失败任务已各自 toast（见 {@link #executeWrapped}）。
     */
    public static CompletableFuture<Void> runParallel(Collection<? extends Task> tasks) {
        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(tasks.size());
        for (Task task : tasks) {
            futures.add(submitParallel(task));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /** 是否有任务正在执行（含 100 ms 去抖延迟），供 UI 决定显示「正在操作」还是「账号列表」。 */
    public static boolean isRunning() {
        long now = System.currentTimeMillis();
        if (serialBusy && serialStart != -1L && now - serialStart >= TASK_DISPLAY_DELAY_MS) {
            return true;
        }
        return parallelInFlight.get() > 0 && parallelStart != -1L && now - parallelStart >= TASK_DISPLAY_DELAY_MS;
    }

    /**
     * 判断指定线程是否为本调度器的 worker 线程。仅当 {@code t} 是当前线程且正在执行本调度器任务时返回 true
     * ——这是 {@code Threading.checkAccountWorkerThread()} 唯一的使用方式。
     */
    public static boolean isWorkerThread(Thread t) {
        return t == Thread.currentThread() && ON_WORKER.get();
    }

    /** 串行与并行任务共用的执行包装：打标、执行、异常处理。 */
    private static void executeWrapped(Task task, CompletableFuture<Void> future) {
        ON_WORKER.set(true);
        try {
            task.run();
            future.complete(null);
        } catch (InterruptedException ie) {
            // 任务自身被中断按取消处理；这是任务级取消，不代表调度器要关闭，
            // 因此不重设线程中断状态（否则串行线程下一次 take() 会抛中断、误杀 worker）。
            // 真正的「关闭中」中断来自串行线程在 take() 上被外部中断，由 runSerialLoop 处理。
            future.cancel(true);
        } catch (Exception e) {
            future.completeExceptionally(e);
            AccountsX.LOGGER.warn("An exception has occurred in AccountsX Background Thread.", e);
            try {
                Platforms.getMinecraftPlatform().showToast("accountsx.account.fail.title", failureMessage(e));
            } catch (Throwable t) {
                // 适配器不可用（如单元测试环境）；上面已记录日志。
            }
        }
    }

    /** 异常 → i18n key 映射（原 AccountManager.handleException）。 */
    private static String failureMessage(Throwable t) {
        if (t instanceof PlayerNoLongerExistedException) {
            return "accountsx.account.fail.player_no_longer_existed";
        }
        return "accountsx.account.fail.unknown";
    }
}
