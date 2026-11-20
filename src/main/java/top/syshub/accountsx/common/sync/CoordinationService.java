package top.syshub.accountsx.common.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 去中心化多实例协调（P6）。
 *
 * <p>没有中心节点、没有监听端口、没有 leader：会合点是配置文件旁边的 {@code *.d/} 目录，
 * 串行点是文件锁，写入后更新一个变化标记。每个实例职责完全相同，角色按操作临时获得。</p>
 *
 * <p>正确性由「用令牌前必读盘 + 租约内读-改-写」保证；变化标记只用于通知界面。</p>
 */
public final class CoordinationService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CoordinationService.class);
    private static final long POLL_MILLIS = 250L;
    /** 刷新换票可能走在网络超时边缘：给足等待对端的时间，超时就跳过这一轮而不是强行换。 */
    public static final long LEASE_TIMEOUT_MILLIS = 15_000L;
    private static final long CONFIG_LOCK_TIMEOUT_MILLIS = 10_000L;

    /** 远端有变化时回调（在协调线程上执行，不能直接碰 UI）。 */
    @FunctionalInterface
    public interface Listener {
        void onRemoteChange();
    }

    private final SyncPaths paths;
    private final InstanceIdentity identity;
    private final PeerRegistry peers;

    private volatile Listener listener;
    private volatile boolean running;
    private String lastChange;
    private Thread pollThread;

    public CoordinationService(Path configFile) {
        this.identity = InstanceIdentity.current();
        this.paths = new SyncPaths(configFile);
        this.peers = new PeerRegistry(paths, identity);
    }

    public SyncPaths paths() {
        return paths;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 登记自己并启动变化标记轮询。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        peers.publish();
        lastChange = null; // 首次轮询也对账，覆盖加载配置与启动协调之间的写入。
        pollThread = daemon("accountsx-sync-poll", () -> {
            while (running) {
                sleep(POLL_MILLIS);
                if (!running) {
                    return;
                }
                pollOnce();
            }
        });
    }

    private void pollOnce() {
        String change;
        try {
            change = Files.readString(paths.changeSignal(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            LOGGER.debug("读取配置变化标记失败。", e);
            return;
        }
        if (change.equals(lastChange))
            return;
        lastChange = change;
        Listener current = this.listener;
        if (current == null) {
            return;
        }
        try {
            current.onRemoteChange();
        } catch (RuntimeException e) {
            // 留待下一次写入通知，保持轮询线程运行。
            LOGGER.warn("多实例对账失败。", e);
        }
    }

    /** 配置落盘后更新标记。轮询方从配置重新对账，无需回放中间的写入。 */
    public void publishWrite() {
        try {
            SyncFiles.writeAtomically(paths.changeSignal(), UUID.randomUUID().toString());
        } catch (IOException e) {
            LOGGER.debug("发布配置变化标记失败。", e);
        }
    }

    /** 别的实例是否正把该账号当作当前账号（用于删除前的二次确认）。 */
    public boolean isUsedByOther(String accountKey) {
        return peers.isUsedByOther(accountKey);
    }

    /** 当前账号变化时立即发布，供其他实例删除前查询。 */
    public void publishCurrent(String accountKey) {
        peers.setCurrent(accountKey);
    }

    /**
     * 在全局写锁内执行：{@code 读盘 → 合并 → 写回} 必须整体串行，否则后写的实例会抹掉对方刚加的账号。
     *
     * <p>锁超时取 {@link #LEASE_TIMEOUT_MILLIS} 同量级：写文件是毫秒级操作，等待说明对面在换票，
     * 宁可这一轮放弃（内存状态还在，下次变更重试）也不强行插队。</p>
     */
    public <T> T withConfigLock(FileMutex.Action<T> action) throws Exception {
        return FileMutex.withLock(paths.configLock(), CONFIG_LOCK_TIMEOUT_MILLIS, action);
    }

    /** 在账号租约内执行：同一账号的换票跨进程串行。 */
    public <T> T withAccountLease(String accountKey, long timeoutMillis, FileMutex.Action<T> action) throws Exception {
        return FileMutex.withLock(paths.leaseFile(accountKey), timeoutMillis, action);
    }

    /**
     * 记录"即将换票"。
     *
     * <p>崩溃窗口只有一种无法自愈的情况：服务端已经轮换、本地还没落盘。
     * 意图文件让下次启动能认出这个窗口并提示重新登录，而不是给出一个看不懂的 401。</p>
     */
    public void recordRefreshIntent(String accountKey) {
        JsonObject intent = new JsonObject();
        intent.addProperty("key", accountKey);
        intent.addProperty("owner", identity.id());
        intent.addProperty("pid", identity.pid());
        intent.addProperty("at", System.currentTimeMillis());
        try {
            SyncFiles.writeAtomically(paths.intentFile(accountKey), intent.toString());
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("写入刷新意图失败（仅影响崩溃后的提示）。", e);
        }
    }

    public void clearRefreshIntent(String accountKey) {
        try {
            Files.deleteIfExists(paths.intentFile(accountKey));
        } catch (IOException e) {
            LOGGER.debug("清理刷新意图失败。", e);
        }
    }

    /** 属于已经死掉的进程的刷新意图：这些账号的令牌可能已被轮换但没落盘。 */
    public List<String> staleIntents() {
        List<String> stale = new ArrayList<>();
        Path dir = paths.leasesDir();
        if (!Files.isDirectory(dir)) {
            return stale;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.intent")) {
            for (Path file : stream) {
                JsonObject intent = readObject(file);
                if (intent == null) {
                    continue;
                }
                String owner = str(intent.get("owner"));
                if (owner != null && identity.isSelf(owner)) {
                    // 自己上次运行留下的：可能已被轮换，同样需要重新登录
                    stale.add(str(intent.get("key")));
                    deleteQuietly(file);
                    continue;
                }
                long pid = number(intent.get("pid"));
                boolean alive = pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                if (!alive) {
                    String key = str(intent.get("key"));
                    if (key != null) {
                        stale.add(key);
                    }
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("枚举刷新意图失败。", e);
        }
        stale.removeIf(java.util.Objects::isNull);
        return stale;
    }

    @Override
    public synchronized void close() {
        running = false;
        join(pollThread);
        pollThread = null;
        peers.removeSelf();
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonObject readObject(Path file) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return parsed instanceof JsonObject jo ? jo : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("清理失败: {}", file, e);
        }
    }

    private static String str(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }

    private static long number(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isNumber() ? primitive.getAsLong() : 0L;
    }
}
