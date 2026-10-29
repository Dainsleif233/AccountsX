package top.syshub.accountsx.common.sync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 多实例协调目录的路径规则。
 *
 * <p>目录名由配置文件路径派生（{@code accountsx.json} → {@code accountsx.json.d}），
 * 因此用 {@code ACCOUNTSX_CONFIG} 指向不同配置的两个实例天然互不干扰——不需要额外的
 * "实例 ID" 配置项，也不需要发现协议：会合点是用户已经指定的那份配置文件。</p>
 */
public final class SyncPaths {
    private final Path configFile;

    public SyncPaths(Path configFile) {
        this.configFile = configFile;
    }

    public Path configFile() {
        return configFile;
    }

    /** 协调目录根：与配置文件同级，名字是"配置文件名 + .d"。 */
    public Path root() {
        return configFile.resolveSibling(configFile.getFileName() + ".d");
    }

    /** 全局写锁：读盘 → 合并 → 原子写 期间独占。 */
    public Path configLock() {
        return root().resolve("config.lock");
    }

    /** 追加事件日志：把"别的实例写了什么"的发现延迟压到轮询周期，正确性不依赖它。 */
    public Path eventsLog() {
        return root().resolve("events.log");
    }

    public Path peersDir() {
        return root().resolve("peers");
    }

    public Path leasesDir() {
        return root().resolve("leases");
    }

    public Path peerFile(String instanceId) {
        return peersDir().resolve(instanceId + ".json");
    }

    /**
     * 账号租约文件：同一账号的刷新与删除互斥。
     *
     * <p>账号 key 含 {@code :} {@code #} 与 apiRoot 里的斜杠，不能直接当文件名，
     * 所以取 SHA-256 前 24 个 hex 字符——碰撞概率与 UUID 同级，且文件名长度固定。</p>
     */
    public Path leaseFile(String accountKey) {
        return leasesDir().resolve(hash(accountKey) + ".lock");
    }

    /** 刷新意图：记录"谁在什么时候开始换票"，用于识别崩溃在"已轮换但未落盘"窗口。 */
    public Path intentFile(String accountKey) {
        return leasesDir().resolve(hash(accountKey) + ".intent");
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JRE 必备算法，缺失说明运行环境已损坏
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
