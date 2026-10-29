package top.syshub.accountsx.common.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 跨进程互斥（以文件锁承载）。
 *
 * <p>去中心化的关键原语：串行点不是"某个实例"，而是"某个文件"。任何实例都能拿，
 * 持有者崩溃时由内核释放，因此不引入 TTL、租约续期或 leader 选举。</p>
 *
 * <p>两个必须处理的细节：</p>
 * <ul>
 *   <li>同 JVM 内对同一文件再次 {@code tryLock()} 抛 {@link OverlappingFileLockException}
 *       而不是返回 null——不捕获就会把"另一个线程正在写"伪装成失败；</li>
 *   <li>必须用带截止时间的 {@code tryLock()} 轮询，不能用阻塞的 {@code lock()}：
 *       对端若卡在网络上，这里会被一起卡死。</li>
 * </ul>
 */
public final class FileMutex {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileMutex.class);
    private static final long POLL_MILLIS = 20L;

    private FileMutex() {
    }

    @FunctionalInterface
    public interface Action<T> {
        T run() throws Exception;
    }

    public static <T> T withLock(Path lockFile, long timeoutMillis, Action<T> action) throws Exception {
        Path dir = lockFile.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            while (true) {
                FileLock lock = tryAcquire(channel);
                if (lock != null) {
                    try {
                        return action.run();
                    } finally {
                        release(lock);
                    }
                }
                if (System.nanoTime() >= deadline) {
                    throw new SyncBusyException(lockFile, timeoutMillis);
                }
                sleepQuietly(timeoutMillis, lockFile);
            }
        }
    }

    private static FileLock tryAcquire(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            // 同 JVM 的另一线程持有：按"被占用"处理，等待而不是失败
            return null;
        }
    }

    private static void release(FileLock lock) {
        try {
            lock.release();
        } catch (IOException e) {
            LOGGER.debug("释放文件锁失败（进程退出时由内核兜底）。", e);
        }
    }

    private static void sleepQuietly(long timeoutMillis, Path lockFile) {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyncBusyException(lockFile, timeoutMillis);
        }
    }
}
