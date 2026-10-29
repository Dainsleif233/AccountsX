package top.syshub.accountsx.common.sync;

import java.nio.file.Path;

/**
 * 在截止时间内没拿到文件锁。
 *
 * <p>刻意是运行时异常：调用点要么是"跳过这一轮"（刷新延后），要么是"放弃合并写"，
 * 都不需要把 {@code throws} 传染给上层编排队列。</p>
 */
public class SyncBusyException extends RuntimeException {
    public SyncBusyException(Path lockFile, long waitedMillis) {
        super("获取文件锁超时（" + waitedMillis + "ms）: " + lockFile);
    }
}
