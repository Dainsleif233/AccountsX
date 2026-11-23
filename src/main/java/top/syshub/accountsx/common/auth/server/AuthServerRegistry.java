package top.syshub.accountsx.common.auth.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.Threading;
import top.syshub.accountsx.common.auth.oauth.OAuthClientIds;
import top.syshub.accountsx.common.auth.oauth.OAuthEndpoints;

/**
 * 认证服务器注册表：servers 条目的内存视图 + 元数据缓存。
 *
 * <p>语义：元数据（公钥 / skinDomains / OIDC 端点）只在<b>启动</b>（{@link #refreshAll}，逐 server
 * 拉一次，失败保留旧条目不阻塞）与<b>加服</b>（{@link #add}，失败直接抛、不落盘）时取；
 * 登录 / 刷新 / createAccountContext 只读内存（miss 时兜底补测一次——重启后 refreshAll 还没跑完的窗口）。</p>
 *
 * <p>条目身份是 {@code (kind, apiRoot)}：locator（用户输入的地址）只是「加服」时 ALI 解析的输入，
 * 解析完就丢，不落盘也不缓存——登录路径直接拿左栏筛选项里的 {@link AuthServer}，不再解析地址。</p>
 *
 * <p>不做 TTL 过期：公钥轮换极少，过期反而引入登录中途失效的竞态。</p>
 *
 * <p>线程模型：条目与元数据在各自方法内同步；{@code persist(JsonArray)} 由
 * {@code AccountRepository.snapshot()} 在仓储锁内调用（锁序恒为 仓储→注册表；
 * 变更回调在注册表锁外触发，避免反向持锁）。</p>
 */
public final class AuthServerRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthServerRegistry.class);

    /** 条目身份：kind + apiRoot（固定条目不落表，不会出现在这里）。 */
    private record Key(AuthServerKind kind, String apiRoot) {
        static Key of(AuthServer server) {
            return new Key(server.kind(), server.apiRoot());
        }
    }

    private final HttpGateway http;

    /** 条目变化回调（持久化标脏）。可为 null（测试 / 游离注册表）。 */
    private final Runnable onChange;

    private final Map<Key, AuthServer> servers = new LinkedHashMap<>();

    private final Map<String, AuthServerMetadata> metadata = new LinkedHashMap<>();

    /**
     * 本实例显式加过（或重新探测覆盖过）的条目 key。
     *
     * <p>墓碑是给「别的实例手里那份旧快照」用的：它不能让删掉的服务器复活，但也不能永久封死
     * 这个服务器——用户删掉再加回来完全正常。只有本实例显式 {@link #store} 过的 key 才压在
     * 墓碑之上；另一实例内存里那份旧副本仍然会被墓碑挡住。</p>
     */
    private final java.util.Set<String> addedLocally = new java.util.LinkedHashSet<>();

    /**
     * 本实例显式删掉的条目（key → 时刻）。
     *
     * <p>与账号墓碑共用根节点的 {@code removed} 数组（见 {@link AuthServer#tombstoneKey}）：
     * 落盘时由仓储并进文件，别的实例保存时据此拒绝把条目写回。落盘成功后由仓储调用
     * {@link #forgetLocalIntents()} 清掉。</p>
     */
    private final Map<String, Long> removedLocally = new LinkedHashMap<>();

    /** 本地移位版本；与已落盘版本不等时，对账不采纳磁盘顺序，免得把还没写出去的移位盖回。 */
    private long orderVersion;
    private long persistedOrderVersion;

    public AuthServerRegistry(HttpGateway http) {
        this(http, null);
    }

    public AuthServerRegistry(HttpGateway http, Runnable onChange) {
        this.http = Objects.requireNonNull(http, "http");
        this.onChange = onChange;
    }

    public synchronized List<AuthServer> servers() {
        return List.copyOf(servers.values());
    }

    /** 按 apiRoot 查条目（任意 kind）；找不到返回 null。 */
    public synchronized AuthServer byApiRoot(String apiRoot) {
        if (apiRoot == null)
            return null;
        for (AuthServer server : servers.values()) {
            if (apiRoot.equals(server.apiRoot()))
                return server;
        }
        return null;
    }

    public synchronized AuthServerMetadata metadataOrNull(String apiRoot) {
        return apiRoot == null ? null : metadata.get(apiRoot);
    }

    /** 只读内存；miss 时兜底探测一次（启动预热未完成 / 首次见到该 apiRoot 的旧账号）。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public AuthServerMetadata metadata(String apiRoot) throws IOException {
        AuthServerMetadata cached = metadataOrNull(apiRoot);
        if (cached != null)
            return cached;
        AuthServerMetadata probed = YggdrasilServerProbe.readMetadata(http.get(apiRoot));
        synchronized (this) {
            metadata.put(apiRoot, probed);
        }
        return probed;
    }

    /** 启动装载：读 servers 数组（builtin kind 不进入 servers 列表）。本地意图也一并复位。 */
    public synchronized void load(JsonArray array) {
        load(array, java.util.Set.of());
        addedLocally.clear();
        removedLocally.clear();
    }

    /**
     * 装载并跳过墓碑标记的条目（{@code tombstoned} 是 {@link AuthServer#tombstoneKey} 形式的 key）。
     *
     * <p>本地显式加过的条目不受墓碑约束（删掉再加回来的服务器必须能写回文件）。</p>
     */
    public synchronized void load(JsonArray array, java.util.Set<String> tombstoned) {
        servers.clear();
        metadata.clear();
        for (AuthServer server : AuthServer.readAll(array)) {
            if (server.kind().builtin())
                continue;
            String key = AuthServer.tombstoneKey(server.kind(), server.apiRoot());
            if (tombstoned.contains(key) && !addedLocally.contains(key))
                continue;
            servers.put(Key.of(server), server);
        }
    }

    /**
     * 与磁盘上的条目合并（对账用），返回是否有变化、是否新增了条目。
     *
     * <p>两个方向都要合并，缺一个就会丢数据：</p>
     * <ul>
     *   <li>磁盘上新增的条目收进内存——否则本地保存会把它当成「多余」抹掉；</li>
     *   <li>同键条目磁盘上更新（{@code cachedAt} 更大：对端重新探测过）就采纳——否则本地
     *       手里那份旧端点会在下一次普通保存时把对端刚拿到的结果盖回去。</li>
     * </ul>
     */
    public synchronized Merge mergeFromDisk(JsonArray diskServers, java.util.Set<String> tombstoned) {
        boolean changed = false;
        boolean needsWarm = false;
        List<Key> diskOrder = new ArrayList<>();
        for (AuthServer disk : AuthServer.readAll(diskServers)) {
            if (disk.kind().builtin())
                continue;
            String tombstone = AuthServer.tombstoneKey(disk.kind(), disk.apiRoot());
            if (tombstoned.contains(tombstone) && !addedLocally.contains(tombstone))
                continue;
            Key key = Key.of(disk);
            diskOrder.add(key);
            AuthServer existing = servers.get(key);
            if (existing == null) {
                servers.put(key, disk);
                // 元数据（公钥 / skinDomains）不随条目一起同步，留给预热重新探测。
                metadata.remove(disk.apiRoot());
                needsWarm = true;
                changed = true;
            } else if (disk.cachedAt() > existing.cachedAt()) {
                // 对端重新探测过：采纳它拿到的端点，并丢掉我们的元数据缓存——
                // 同一份探测结果里的公钥 / skinDomains 也可能已经变了，下次用前必须重取。
                servers.put(key, disk);
                metadata.remove(disk.apiRoot());
                needsWarm = true;
                changed = true;
            }
        }
        // 顺序也是共享状态：本地这次没有未落盘的移位时按磁盘顺序重排，
        // 否则下一次普通保存会把对端移动过的服务器顺序写回旧顺序（与账号顺序同一机制）。
        if (orderVersion == persistedOrderVersion) {
            changed |= adoptDiskOrder(diskOrder);
        }
        return new Merge(changed, needsWarm);
    }

    /** 把内存服务器顺序调整为磁盘顺序；本地刚加、磁盘还没有的条目保持相对顺序放末尾。 */
    private boolean adoptDiskOrder(List<Key> diskOrder) {
        Map<Key, AuthServer> reordered = new LinkedHashMap<>();
        Map<Key, AuthServer> pending = new LinkedHashMap<>(servers);
        for (Key key : diskOrder) {
            AuthServer server = pending.remove(key);
            if (server != null) {
                reordered.put(key, server);
            }
        }
        reordered.putAll(pending);
        if (new ArrayList<>(reordered.keySet()).equals(new ArrayList<>(servers.keySet()))) {
            return false;
        }
        servers.clear();
        servers.putAll(reordered);
        return true;
    }

    /** {@link #mergeFromDisk} 的结果：{@code needsWarm} 表示有条目需要重新探测元数据。 */
    public record Merge(boolean changed, boolean needsWarm) {
    }

    /**
     * 丢弃被墓碑标记且本地没有显式加过的条目（对账：对端删掉了服务器）。
     *
     * @return 是否有条目被丢弃
     */
    public synchronized boolean dropTombstoned(java.util.Set<String> tombstoned) {
        boolean changed = false;
        var it = servers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, AuthServer> entry = it.next();
            String key = AuthServer.tombstoneKey(entry.getKey().kind(), entry.getKey().apiRoot());
            if (tombstoned.contains(key) && !addedLocally.contains(key)) {
                it.remove();
                metadata.remove(entry.getKey().apiRoot());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 本次落盘要写进 {@code removed} 数组的服务器墓碑，以及要撤销的墓碑。
     *
     * <p>两者必须一起给出：本实例刚删掉的条目要写成墓碑（挡住对端手里的旧副本），而刚加回来的
     * 条目要从文件里撤掉墓碑——否则下次启动装载时会把自己的条目当成「已删除」跳过。</p>
     */
    public record Tombstones(Map<String, Long> removed, java.util.Set<String> resurrected) {
        static final Tombstones NONE = new Tombstones(Map.of(), java.util.Set.of());
    }

    /** 本实例的服务器墓碑意图（写入用）。 */
    public synchronized Tombstones localTombstones() {
        java.util.Set<String> resurrected = java.util.Set.copyOf(addedLocally);
        Map<String, Long> removed = new LinkedHashMap<>(removedLocally);
        removed.keySet().removeAll(resurrected);
        return new Tombstones(removed, resurrected);
    }

    /** 落盘成功后调用：本次意图已经写进文件，内存里不必再留着。 */
    public synchronized void forgetLocalIntents() {
        addedLocally.clear();
        removedLocally.clear();
    }

    /**
     * 写回 servers 数组（仓储 snapshot 在其锁内调用）。
     *
     * <p>按 {@code (type, apiRoot)} 对齐原 JSON，保留我们没建模的未知字段（与 {@code ConfigRoot} 的
     * "未知字段原样回写"原则一致：新版本写入 → 旧版本保存不应丢数据）。已废弃的 {@code locator}
     * 字段不再回写，老配置里的残留会被这次保存顺手清掉。</p>
     */
    public synchronized void persist(JsonArray array) {
        Map<String, JsonObject> previous = new LinkedHashMap<>();
        for (JsonElement element : array) {
            if (element instanceof JsonObject jo)
                previous.put(persistKey(jo), jo);
        }
        List<JsonObject> next = new ArrayList<>(servers.size());
        for (AuthServer server : servers.values()) {
            JsonObject jo = server.toJson();
            JsonObject old = previous.get(server.kind().v4type() + "|" + Objects.toString(server.apiRoot(), ""));
            if (old != null) {
                for (String field : old.keySet()) {
                    if (!"locator".equals(field) && !jo.has(field))
                        jo.add(field, old.get(field));
                }
            }
            next.add(jo);
        }
        while (!array.isEmpty())
            array.remove(0);
        for (JsonObject jo : next)
            array.add(jo);
    }

    private static String persistKey(JsonObject jo) {
        String type = AuthServer.str(jo.get("type"));
        if (type == null)
            type = AuthServer.str(jo.get("kind"));
        String apiRoot = AuthServer.str(jo.get("apiRoot"));
        if (apiRoot == null)
            apiRoot = AuthServer.str(jo.get("server"));
        return Objects.toString(type, "") + "|" + Objects.toString(apiRoot, "");
    }

    /** 加服（UI）：clientId 默认取内置 apiRoot 表。 */
    public AuthServer add(AuthServerKind kind, String locator) throws IOException {
        return add(kind, locator, OAuthClientIds.BY_API_ROOT);
    }

    /**
     * 加服（UI 入口）：先 ALI 解析，再探测；{@code (kind, apiRoot)} 与现有条目相同则视为覆盖
     * （重新探测并替换条目，用户的显式动作总是拿到最新元数据）——{@code client_id} 例外，
     * 见 {@link #preserveClientId}。
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public AuthServer add(AuthServerKind kind, String locator, Map<String, String> knownClientIds)
            throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(locator, "locator");
        if (kind.builtin())
            return new AuthServer(null, kind, null, null, 0L);
        String trimmed = locator.trim();
        // 加服是用户显式动作：每次都重新解析（地址的 ALI 指向可能已经变了）。
        return store(kind, resolveApiRoot(kind, trimmed), knownClientIds);
    }

    /** 探测并写入条目（同键覆盖，client_id 除外），成功后触发持久化。 */
    private AuthServer store(AuthServerKind kind, String apiRoot, Map<String, String> knownClientIds)
            throws IOException {
        AuthServer probed = probe(apiRoot, kind, knownClientIds);
        AuthServer stored;
        synchronized (this) {
            Key key = new Key(kind, apiRoot);
            stored = preserveClientId(probed, servers.get(key));
            servers.put(key, stored);
            // 用户显式加服：这个 key 高于墓碑（删掉再加回来必须能落盘）。
            String tombstone = AuthServer.tombstoneKey(kind, apiRoot);
            addedLocally.add(tombstone);
            removedLocally.remove(tombstone);
        }
        if (onChange != null)
            onChange.run();
        return stored;
    }

    /** 启动预热：clientId 默认取内置 apiRoot 表。 */
    public void refreshAll() {
        refreshAll(OAuthClientIds.BY_API_ROOT);
    }

    /** 启动预热：逐个探测 injector 条目；失败保留旧条目（stale），不阻塞。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public void refreshAll(Map<String, String> knownClientIds) {
        List<AuthServer> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(servers.values());
        }
        boolean changed = false;
        for (AuthServer server : snapshot) {
            if (server.kind().builtin())
                continue;
            try {
                YggdrasilServerProbe.Probed probed = probeAndCacheMetadata(server.apiRoot(), knownClientIds);
                OAuthEndpoints oauth = OAuthEndpoints.merge(probed.oauth(), server.oauth());
                // 明确拒绝所需 scope 时，不能把缓存里的设备码端点当作缺失字段补回。
                if (probed.deviceScopesRejected() && oauth != null) {
                    oauth = new OAuthEndpoints(oauth.clientId(), oauth.tokenEndpoint(), null,
                            oauth.userinfoEndpoint(), oauth.issuer(), oauth.jwksUri());
                }
                AuthServer merged = new AuthServer(server.apiRoot(), server.kind(),
                        probed.displayName() != null ? probed.displayName() : server.displayName(),
                        oauth, System.currentTimeMillis());
                merged = preserveClientId(merged, server);
                synchronized (this) {
                    Key key = new Key(server.kind(), server.apiRoot());
                    if (servers.containsKey(key)) {
                        servers.put(key, merged);
                        // 只有语义变化才标脏：每次启动都重写一遍配置文件既无意义，
                        // 又会让另一个实例误以为"有变更"而去读盘对账。
                        if (!sameContent(server, merged))
                            changed = true;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("认证服务器启动探测失败，保留旧元数据: {}", server.apiRoot(), e);
            }
        }
        if (changed && onChange != null)
            onChange.run();
    }

    /**
     * 探测结果不得覆盖条目里已有的 {@code client_id}：它是账号 refresh token 的颁发方，
     * 换一个就等于把已存账号的刷新链全部作废（界面也没有手改入口，换错了只能删服重建）。
     * 条目还没有 client_id 时照常由探测填入，其余端点跟随探测更新。
     */
    private static AuthServer preserveClientId(AuthServer probed, AuthServer existing) {
        if (existing == null || existing.oauth() == null)
            return probed;
        String clientId = existing.oauth().clientId();
        OAuthEndpoints oauth = probed.oauth();
        if (clientId == null || (oauth != null && clientId.equals(oauth.clientId())))
            return probed;
        OAuthEndpoints merged = oauth == null
                ? new OAuthEndpoints(clientId, null, null, null)
                : new OAuthEndpoints(clientId, oauth.tokenEndpoint(), oauth.deviceAuthorizationEndpoint(),
                        oauth.userinfoEndpoint(), oauth.issuer(), oauth.jwksUri());
        return new AuthServer(probed.apiRoot(), probed.kind(), probed.displayName(), merged, probed.cachedAt());
    }

    /** 两个条目除了 {@code cachedAt} 是否完全一样（探测结果没变时不必落盘）。 */
    private static boolean sameContent(AuthServer a, AuthServer b) {
        return Objects.equals(a.displayName(), b.displayName())
                && Objects.equals(a.oauth(), b.oauth());
    }

    /** locator → apiRoot。Yggdrasil 对输入地址发 GET 并跟随重定向，取 X-Authlib-Injector-API-Location 头或最终 URL；固定条目原样返回。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public String resolveApiRoot(AuthServerKind kind, String locator) throws IOException {
        return switch (kind) {
            case YGGDRASIL -> YggdrasilServerProbe.resolveApiRoot(locator, http);
            case MOJANG, OFFLINE -> locator;
        };
    }

    /** 探测一个 apiRoot：GET 根路径 → 公钥 / skinDomains / displayName / OIDC 端点。不落 servers，只填元数据缓存。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public AuthServer probe(String apiRoot, AuthServerKind kind, Map<String, String> knownClientIds)
            throws IOException {
        YggdrasilServerProbe.Probed probed = probeAndCacheMetadata(apiRoot, knownClientIds);
        return new AuthServer(apiRoot, kind, probed.displayName(), probed.oauth(),
                System.currentTimeMillis());
    }

    private YggdrasilServerProbe.Probed probeAndCacheMetadata(String apiRoot, Map<String, String> knownClientIds)
            throws IOException {
        YggdrasilServerProbe.Probed probed = YggdrasilServerProbe.probe(http, apiRoot, knownClientIds);
        synchronized (this) {
            metadata.put(apiRoot, probed.metadata());
        }
        return probed;
    }

    /** 当前移位版本，供仓储在落盘快照时记录、写成功后回填 {@link #markOrderPersisted}。 */
    public synchronized long orderVersion() {
        return orderVersion;
    }

    /** 落盘成功后由仓储调用：本次快照里的顺序已进文件，之后的对账可以采纳别处的顺序。 */
    public synchronized void markOrderPersisted(long version) {
        persistedOrderVersion = Math.max(persistedOrderVersion, version);
    }

    /**
     * 调整服务器在左栏的顺序（UI 上/下移）；返回是否真的移动了，移动后触发持久化。
     * 与账号 {@code AccountRepository.move} 同一套顺序版本机制，避免对端旧快照把顺序写回。
     */
    public boolean move(AuthServerKind kind, String apiRoot, int delta) {
        if (kind == null || apiRoot == null || delta == 0) {
            return false;
        }
        boolean moved;
        // 与 remove 同一锁纪律：变更在锁内做，onChange（仓储 markDirty）必须在释放注册表锁之后调。
        // 客户端线程持注册表锁再拿仓储锁，会与「工作线程持仓储锁再拿注册表锁」的保存路径反向死锁。
        synchronized (this) {
            Key key = new Key(kind, apiRoot);
            List<Key> keys = new ArrayList<>(servers.keySet());
            int index = keys.indexOf(key);
            if (index < 0) {
                return false;
            }
            int target = Math.max(0, Math.min(index + delta, keys.size() - 1));
            if (target == index) {
                return false;
            }
            keys.remove(index);
            keys.add(target, key);
            Map<Key, AuthServer> reordered = new LinkedHashMap<>();
            for (Key k : keys) {
                reordered.put(k, servers.get(k));
            }
            servers.clear();
            servers.putAll(reordered);
            orderVersion++;
            moved = true;
        }
        if (moved && onChange != null) {
            onChange.run();
        }
        return moved;
    }

    /** 移除条目（UI「移除认证服务器」）；返回是否真的删掉了，删掉后触发持久化。 */
    public boolean remove(AuthServerKind kind, String apiRoot) {
        if (kind == null || apiRoot == null) {
            return false;
        }
        boolean removed;
        synchronized (this) {
            removed = servers.remove(new Key(kind, apiRoot)) != null;
            metadata.remove(apiRoot);
            // 记墓碑：对端内存里的旧副本下一次保存不能把它写回来（与账号墓碑同一机制）。
            String tombstone = AuthServer.tombstoneKey(kind, apiRoot);
            removedLocally.put(tombstone, System.currentTimeMillis());
            addedLocally.remove(tombstone);
        }
        if (removed && onChange != null) {
            onChange.run();
        }
        return removed;
    }
}
