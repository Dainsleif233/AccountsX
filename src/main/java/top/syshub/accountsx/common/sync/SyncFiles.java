package top.syshub.accountsx.common.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 协调目录用的小型原子写工具。
 *
 * <p>临时文件名带 pid 与进程内序号：两个实例同时写同一个目标时不会互相截断
 * {@code .tmp}，即便全局写锁因文件系统限制失效也留有一层保险。</p>
 */
public final class SyncFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger(SyncFiles.class);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private SyncFiles() {
    }

    public static void writeAtomically(Path target, String text) throws IOException {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        String suffix = ".tmp-" + ProcessHandle.current().pid() + "-" + SEQUENCE.incrementAndGet();
        Path tmp = target.resolveSibling(target.getFileName() + suffix);
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            fsync(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                LOGGER.debug("清理临时文件失败: {}", tmp, ignored);
            }
            throw e;
        }
    }

    private static void fsync(Path tmp) {
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (UnsupportedOperationException | IOException e) {
            // 内存文件系统与个别挂载点不支持 force：尽力而为，不因此失败整个写入
            LOGGER.debug("跳过 fsync: {}", tmp, e);
        }
    }
}
