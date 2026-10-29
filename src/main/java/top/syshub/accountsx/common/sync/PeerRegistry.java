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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 存活实例登记表（{@code peers/<instanceId>.json}）。
 *
 * <p>每个实例只写自己的文件：不需要选举、不需要权威节点，也没有"第一个启动的人"的特殊角色。
 * 读的一方用 {@code pid + 启动时刻} 判定存活，陈旧文件读时忽略并顺手删除——
 * 崩溃不会留下需要人工清理的状态。</p>
 */
public final class PeerRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(PeerRegistry.class);
    /** 启动时刻在不同平台上精度不同（Windows 约 15ms），比较时留出容差。 */
    private static final long START_TOLERANCE_MILLIS = 5_000L;

    private final SyncPaths paths;
    private final InstanceIdentity self;
    private volatile String currentKey;

    public PeerRegistry(SyncPaths paths, InstanceIdentity self) {
        this.paths = paths;
        this.self = self;
    }

    /** 发布/刷新自己的条目；切换当前账号时立即调用，让别人的删除确认看到最新状态。 */
    public void publish() {
        JsonObject peer = new JsonObject();
        peer.addProperty("id", self.id());
        peer.addProperty("pid", self.pid());
        peer.addProperty("startedAt", self.startedAtMillis());
        peer.addProperty("lastSeen", System.currentTimeMillis());
        String key = this.currentKey;
        if (key != null) {
            peer.addProperty("current", key);
        }
        try {
            SyncFiles.writeAtomically(paths.peerFile(self.id()), peer.toString());
        } catch (IOException | RuntimeException e) {
            // 登记失败只影响"别人删账号时是否多问一句"，不影响本实例的数据安全
            LOGGER.debug("写入实例登记失败: {}", paths.peerFile(self.id()), e);
        }
    }

    public void setCurrent(String accountKey) {
        this.currentKey = accountKey;
        publish();
    }

    public String currentKey() {
        return currentKey;
    }

    /** 除自己以外仍存活的实例条目。 */
    public List<JsonObject> livePeers() {
        List<JsonObject> result = new ArrayList<>();
        Path dir = paths.peersDir();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                JsonObject peer = read(file);
                if (peer == null) {
                    continue;
                }
                String id = str(peer.get("id"));
                if (id == null) {
                    id = file.getFileName().toString().replace(".json", "");
                }
                if (self.isSelf(id)) {
                    continue;
                }
                if (isAlive(peer)) {
                    result.add(peer);
                } else {
                    deleteQuietly(file);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("枚举实例登记失败: {}", dir, e);
        }
        return result;
    }

    /** 是否另有存活实例正把该账号当作当前账号。 */
    public boolean isUsedByOther(String accountKey) {
        if (accountKey == null) {
            return false;
        }
        for (JsonObject peer : livePeers()) {
            if (accountKey.equals(str(peer.get("current")))) {
                return true;
            }
        }
        return false;
    }

    public void removeSelf() {
        deleteQuietly(paths.peerFile(self.id()));
    }

    private static JsonObject read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            return parsed instanceof JsonObject jo ? jo : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("读取实例登记失败: {}", file, e);
            return null;
        }
    }

    /**
     * pid 存活且进程启动时刻一致才算同一个实例。
     * 启动时刻拿不到（平台不支持）时只信 pid——比反过来更安全：宁可多问一句，也不误删别人的账号。
     */
    private static boolean isAlive(JsonObject peer) {
        long pid = number(peer.get("pid"));
        if (pid <= 0) {
            return false;
        }
        java.util.Optional<ProcessHandle> found = ProcessHandle.of(pid);
        if (found.isEmpty() || !found.get().isAlive()) {
            return false;
        }
        OptionalLong startedAt = InstanceIdentity.startedAt(found.get());
        if (startedAt.isEmpty()) {
            // pid 存在但平台给不出启动时刻：活着就认为活着（宁可多问一句，也不误删别人的账号）
            return true;
        }
        long recorded = number(peer.get("startedAt"));
        return recorded <= 0 || Math.abs(startedAt.getAsLong() - recorded) <= START_TOLERANCE_MILLIS;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("清理实例登记失败: {}", file, e);
        }
    }

    private static String str(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }

    private static long number(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isNumber() ? primitive.getAsLong() : 0L;
    }
}
