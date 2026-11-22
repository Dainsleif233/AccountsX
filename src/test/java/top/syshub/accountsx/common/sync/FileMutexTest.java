package top.syshub.accountsx.common.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件锁是去中心化协作的串行点，必须真的互斥、拿不到就超时、异常也要释放。
 * 这些行为只有真文件系统能验（FileLock 是内核语义），所以用 @TempDir 而非 jimfs。
 */
class FileMutexTest {

    @TempDir
    Path dir;

    private Path lock() {
        return dir.resolve("config.lock");
    }

    @Test
    void withLock_runsActionAndReturnsValue() throws Exception {
        String result = FileMutex.withLock(lock(), 2_000, () -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(lock()).exists();
    }

    @Test
    void withLock_releasesAfterActionSoNextCallerAcquires() throws Exception {
        FileMutex.withLock(lock(), 2_000, () -> null);

        // 第二次必须立刻拿到：第一次没释放的话这里会超时抛 SyncBusyException。
        assertThat(FileMutex.withLock(lock(), 500, () -> "second")).isEqualTo("second");
    }

    @Test
    void withLock_releasesEvenWhenActionThrows() throws Exception {
        assertThatThrownBy(() -> FileMutex.withLock(lock(), 2_000, () -> {
            throw new IllegalStateException("fake-boom");
        })).isInstanceOf(IllegalStateException.class);

        // 异常路径也要释放（finally），否则后续写入全被卡死。
        assertThat(FileMutex.withLock(lock(), 500, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void withLock_timesOutWhileAnotherThreadHoldsIt() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> FileMutex.withLock(lock(), 10_000, () -> {
                held.countDown();
                release.await();
                return null;
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            // 同 JVM 另一线程持锁时 tryLock 抛 OverlappingFileLockException，
            // 必须被当成「占用」继续轮询直到超时，而不是当场失败。
            assertThatThrownBy(() -> FileMutex.withLock(lock(), 150, () -> "never"))
                    .isInstanceOf(SyncBusyException.class);

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withLock_serializesConcurrentIncrements() throws Exception {
        int threads = 8;
        int perThread = 50;
        AtomicInteger counter = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        try {
                            FileMutex.withLock(lock(), 10_000, () -> {
                                int now = counter.get();
                                // 故意拉开读-改-写窗口：没有互斥就会丢更新。
                                Thread.yield();
                                counter.set(now + 1);
                                return null;
                            });
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(counter.get()).isEqualTo(threads * perThread);
    }
}
