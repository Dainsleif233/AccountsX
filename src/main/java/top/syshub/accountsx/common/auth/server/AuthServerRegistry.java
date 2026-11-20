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

    /** 启动装载：读 servers 数组（builtin kind 不进入 servers 列表）。 */
    public synchronized void load(JsonArray array) {
        servers.clear();
        metadata.clear();
        for (AuthServer server : AuthServer.readAll(array)) {
            if (server.kind().builtin())
                continue;
            servers.put(Key.of(server), server);
        }
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
                    if (servers.containsKey(key))
                        servers.put(key, merged);
                }
                changed = true;
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

    /** 移除条目（UI「移除认证服务器」）；返回是否真的删掉了，删掉后触发持久化。 */
    public boolean remove(AuthServerKind kind, String apiRoot) {
        if (kind == null || apiRoot == null) {
            return false;
        }
        boolean removed;
        synchronized (this) {
            removed = servers.remove(new Key(kind, apiRoot)) != null;
            metadata.remove(apiRoot);
        }
        if (removed && onChange != null) {
            onChange.run();
        }
        return removed;
    }
}
