package top.syshub.accountsx.core.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1.2 任务调度重写的安全网：阻塞队列串行通道、有界并行池、正确中断语义、ThreadLocal 线程标记、
 * 以及 {@link CompletableFuture} 返回。所有用例只走成功路径或受控中断，避免触发适配器 toast（测试无适配器）。
 */
class TaskSchedulerTest {

    @Test
    void submit_runsTaskAndCompletesFuture() throws Exception {
        CompletableFuture<Boolean> ran = new CompletableFuture<>();
        TaskScheduler.submit(() -> ran.complete(true));

        assertThat(ran.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void serialChannel_preservesSubmissionOrder() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        int count = 5;
        CountDownLatch done = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            final int idx = i;
            TaskScheduler.submit(() -> {
                order.add(idx);
                done.countDown();
            });
        }

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void isRunning_trueWhileTaskExecutes_falseAfter() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> future = TaskScheduler.submit(() -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
        });

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        boolean sawRunning = false;
        for (int i = 0; i < 100; i++) {
            if (TaskScheduler.isRunning()) {
                sawRunning = true;
                break;
            }
            Thread.sleep(10);
        }
        assertThat(sawRunning).isTrue();

        release.countDown();
        future.get(2, TimeUnit.SECONDS);
        assertThat(TaskScheduler.isRunning()).isFalse();
    }

    @Test
    void parallelPool_boundedAtFourConcurrentTasks() throws Exception {
        int tasks = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(tasks);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        for (int i = 0; i < tasks; i++) {
            TaskScheduler.submitParallel(() -> {
                startGate.await();
                int c = current.incrementAndGet();
                maxConcurrent.accumulateAndGet(c, Math::max);
                Thread.sleep(50);
                current.decrementAndGet();
                doneGate.countDown();
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(5, TimeUnit.SECONDS)).isTrue();

        // 有界并行池上限为 4（min(4, accounts)），不会出现 8 路全并发。
        assertThat(maxConcurrent.get()).isEqualTo(4);
    }

    @Test
    void isWorkerThread_trueInsideSubmittedTask() throws Exception {
        CompletableFuture<Boolean> wasWorker = new CompletableFuture<>();
        TaskScheduler.submit(() -> wasWorker.complete(TaskScheduler.isWorkerThread(Thread.currentThread())));

        assertThat(wasWorker.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void interruptedTask_isCancelled_notCrashing() throws Exception {
        CompletableFuture<Void> future = TaskScheduler.submit(() -> {
            throw new InterruptedException("simulated interrupt");
        });

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.CancellationException.class);

        // 串行 worker 未被中断杀死：后续任务仍正常执行。
        CompletableFuture<Boolean> survived = new CompletableFuture<>();
        TaskScheduler.submit(() -> survived.complete(true));
        assertThat(survived.get(2, TimeUnit.SECONDS)).isTrue();
    }
}
