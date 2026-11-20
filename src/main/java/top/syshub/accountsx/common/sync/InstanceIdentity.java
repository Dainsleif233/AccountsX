package top.syshub.accountsx.common.sync;

import java.time.Instant;

/**
 * 本进程的身份：随机 id + pid + 进程启动时刻。
 *
 * <p>pid 会被操作系统复用，启动时刻用于区分占用同一个 pid 的不同进程。</p>
 */
public record InstanceIdentity(String id, long pid, long startedAtMillis) {
    private static volatile InstanceIdentity current;

    public static InstanceIdentity current() {
        InstanceIdentity value = current;
        if (value == null) {
            synchronized (InstanceIdentity.class) {
                value = current;
                if (value == null) {
                    ProcessHandle self = ProcessHandle.current();
                    value = new InstanceIdentity(
                            java.util.UUID.randomUUID().toString(),
                            self.pid(),
                            startedAt(self).orElse(0L));
                    current = value;
                }
            }
        }
        return value;
    }

    /** 进程启动时刻（epoch 毫秒）；平台不提供时返回空。 */
    public static java.util.OptionalLong startedAt(ProcessHandle handle) {
        try {
            java.util.Optional<Instant> start = handle.info().startInstant();
            return start.isPresent()
                    ? java.util.OptionalLong.of(start.get().toEpochMilli())
                    : java.util.OptionalLong.empty();
        } catch (RuntimeException e) {
            return java.util.OptionalLong.empty();
        }
    }

    public boolean isSelf(String instanceId) {
        return id.equals(instanceId);
    }
}
