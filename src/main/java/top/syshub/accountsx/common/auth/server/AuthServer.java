package top.syshub.accountsx.common.auth.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import top.syshub.accountsx.common.auth.oauth.OAuthEndpoints;

/**
 * 认证服务器一等实体。磁盘形状即 v4 {@code servers[]} 条目：
 * {@code type}（kind）+ {@code apiRoot}（ALI 解析后的 API 根，账号的 {@code server} 字段引用它）。
 *
 * <p>条目的唯一身份是 {@code kind + apiRoot}；用户输入的地址（locator）只用于解析，不落盘——
 * 同一个服务器可以有很多种写法，apiRoot 才是事实。{@code mojang} / {@code offline} 固定条目
 * 没有 apiRoot，也不进入 servers 列表，写回时不补 apiRoot，避免污染迁移形状。</p>
 */
public record AuthServer(String apiRoot, AuthServerKind kind, String displayName,
        OAuthEndpoints oauth, long cachedAt) {
    public AuthServer {
        Objects.requireNonNull(kind, "kind");
    }

    static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    static long lng(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0L;
    }

    public static AuthServer fromJson(JsonObject jo) {
        if (jo == null)
            return null;
        String type = str(jo.get("type"));
        if (type == null)
            type = str(jo.get("kind"));
        AuthServerKind kind = AuthServerKind.fromV4(type);
        if (kind == null)
            return null;
        String apiRoot = str(jo.get("apiRoot"));
        if (apiRoot == null)
            apiRoot = str(jo.get("server"));
        if (apiRoot == null && !kind.builtin())
            return null;
        OAuthEndpoints oauth = jo.get("oauth") instanceof JsonObject o ? OAuthEndpoints.fromJson(o) : null;
        return new AuthServer(apiRoot, kind, str(jo.get("displayName")), oauth, lng(jo.get("cachedAt")));
    }

    public JsonObject toJson() {
        JsonObject jo = new JsonObject();
        jo.addProperty("type", kind.v4type());
        // 固定条目（mojang / offline）保持迁移形状：不写合成 apiRoot
        if (apiRoot != null)
            jo.addProperty("apiRoot", apiRoot);
        if (displayName != null)
            jo.addProperty("displayName", displayName);
        if (oauth != null)
            jo.add("oauth", oauth.toJson());
        if (cachedAt != 0L)
            jo.addProperty("cachedAt", cachedAt);
        return jo;
    }

    /**
     * 服务器墓碑的 key：与账号墓碑共用根节点的 {@code removed} 数组，用前缀区分。
     *
     * <p>账号 key 的首段是 {@code offline} / {@code microsoft} / {@code yggdrasil}，
     * 永远不可能以 {@code server:} 开头，两条命名空间因此不会撞车。旧版本读到这些条目只会当成
     * 认不出的账号墓碑（与任何账号 key 都不相等），不会误删账号。</p>
     */
    public static String tombstoneKey(AuthServerKind kind, String apiRoot) {
        return "server:" + kind.v4type() + "|" + apiRoot;
    }

    public static boolean isServerTombstone(String key) {
        return key != null && key.startsWith("server:");
    }

    public static List<AuthServer> readAll(JsonArray servers) {
        List<AuthServer> out = new ArrayList<>();
        if (servers == null)
            return out;
        for (JsonElement e : servers) {
            if (e instanceof JsonObject jo) {
                AuthServer server = fromJson(jo);
                if (server != null)
                    out.add(server);
            }
        }
        return out;
    }
}
