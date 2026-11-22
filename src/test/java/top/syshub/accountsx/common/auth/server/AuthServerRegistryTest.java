package top.syshub.accountsx.common.auth.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.net.HttpGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.syshub.accountsx.common.auth.oauth.OAuthEndpoints;
import top.syshub.accountsx.common.auth.YggdrasilFixtures;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilDeviceCodeFlow;

/**
 * 注册表：探测去重（同服务器多账号只探测一次）、元数据内存缓存、
 * stale 降级、servers[] 往返（迁移形状不动）。网关全假，无网络。
 */
class AuthServerRegistryTest {

    private static final String LOCATOR = "http://injector.test";
    private static final String API_ROOT = "http://injector.test/api/yggdrasil";

    private final class FakeGateway implements HttpGateway {
        final Map<String, Integer> counts = new java.util.HashMap<>();
        boolean failGet = false;
        final JsonObject openid = YggdrasilFixtures.openidConfig(API_ROOT);

        private void hit(String url) {
            counts.merge(url, 1, Integer::sum);
        }

        int count(String url) {
            return counts.getOrDefault(url, 0);
        }

        @Override
        public JsonObject get(String url) throws IOException {
            hit(url);
            if (failGet)
                throw new IOException("fake gateway down");
            if (url.equals(API_ROOT + "/.well-known/openid"))
                return openid.deepCopy();
            if (url.equals(API_ROOT)) {
                try {
                    return YggdrasilFixtures.rootMeta(API_ROOT);
                } catch (IOException e) {
                    throw new IOException(e);
                }
            }
            throw new IOException("unexpected GET " + url);
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) throws IOException {
            return get(url);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Headers getHeaders(String url) {
            hit("GET " + url);
            if (url.equals(LOCATOR))
                return new Headers(URI.create(url),
                        Map.of("X-Authlib-Injector-API-Location", List.of("/api/yggdrasil")));
            return new Headers(URI.create(url), Map.of());
        }

        @Override
        public byte[] getBinary(String url) {
            // 头像可重建：返回坏响应，AvatarService 吞掉即可
            return new byte[0];
        }
    }

    private FakeGateway gateway;
    private AuthServerRegistry registry;
    private final List<Integer> changeEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        registry = new AuthServerRegistry(gateway, () -> changeEvents.add(1));
    }

    @Test
    void add_probesLocatorAndStoresEntry() throws Exception {
        AuthServer first = registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        assertThat(first.apiRoot()).isEqualTo(API_ROOT);
        assertThat(first.displayName()).isEqualTo("FakeInjector");
        assertThat(first.oauth().clientId()).isEqualTo("fake-shared-client");
        assertThat(first.oauth().deviceAuthorizationEndpoint()).isEqualTo(API_ROOT + "/oauth/device");
        // ALI 一跳（GET）+ 根文档一次 + openid 一次，之后条目里的端点/元数据都从内存取
        assertThat(gateway.count("GET " + LOCATOR)).isEqualTo(1);
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);
        assertThat(changeEvents).hasSize(1);
        // add 只新增自己一条：mojang / offline 在 load() 时补齐，fresh 注册表没有
        assertThat(registry.servers()).hasSize(1);
    }

    @Test
    void add_overwritesSameApiRoot() throws Exception {
        AuthServer first = registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        assertThat(first.oauth().clientId()).isEqualTo("fake-shared-client");
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);

        // 解析结果相同的地址再加一次：视为覆盖——重新探测，但列表里仍只有一条
        AuthServer second = registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        assertThat(second.apiRoot()).isEqualTo(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(2);
        assertThat(registry.servers()).hasSize(1);
        assertThat(changeEvents).hasSize(2);
    }

    @Test
    void metadata_isMemoryCachedAfterProbe() throws Exception {
        registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        int before = gateway.count(API_ROOT);
        AuthServerMetadata m = registry.metadata(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(before);
        assertThat(m.skinDomains()).containsExactly("skin.example.invalid");
        assertThat(m.profilePropertyKeys()).hasSize(1);
        assertThat(m.playerCertificateKeys()).hasSize(1);
    }

    @Test
    void metadata_probeOnMiss() throws Exception {
        // 未预热（重启后 refreshAll 未跑完的窗口）：兜底补测一次并缓存
        registry.metadata(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);
        registry.metadata(API_ROOT);
        assertThat(gateway.count(API_ROOT)).isEqualTo(1);
    }

    @Test
    void refreshAll_keepsStaleOnFailure() throws Exception {
        registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        long cachedAt = registry.byApiRoot(API_ROOT).cachedAt();
        changeEvents.clear();
        gateway.failGet = true;
        registry.refreshAll(Map.of());
        AuthServer still = registry.byApiRoot(API_ROOT);
        assertThat(still.displayName()).isEqualTo("FakeInjector");
        assertThat(still.cachedAt()).isEqualTo(cachedAt);
        assertThat(changeEvents).isEmpty();
    }

    @Test
    void refreshAll_doesNotOverwriteStoredClientId() throws Exception {
        // 迁移种下的 client_id 是账号 refresh token 的颁发方：预热探测只准补缺，不准替换。
        JsonArray stored = new JsonArray();
        stored.add(JsonParser.parseString("{\"type\":\"yggdrasil\",\"apiRoot\":\"" + API_ROOT
                + "\",\"oauth\":{\"clientId\":\"fake-seeded\",\"tokenEndpoint\":\"" + API_ROOT + "/oauth/token\"}}"));
        registry.load(stored);

        registry.refreshAll(Map.of());

        AuthServer kept = registry.byApiRoot(API_ROOT);
        assertThat(kept.oauth().clientId()).isEqualTo("fake-seeded");
        // 条目里缺的端点仍由探测补全，displayName 也照常刷新
        assertThat(kept.oauth().deviceAuthorizationEndpoint()).isEqualTo(API_ROOT + "/oauth/device");
        assertThat(kept.displayName()).isEqualTo("FakeInjector");
    }

    @Test
    void refreshAll_clearsCachedDeviceEndpointWhenRequiredScopeIsRejected() throws Exception {
        AuthServer cached = registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(cached)).isTrue();
        JsonArray stored = new JsonArray();
        registry.persist(stored);
        registry.load(stored);

        JsonArray scopes = new JsonArray();
        scopes.add("openid");
        gateway.openid.add("scopes_supported", scopes);
        gateway.openid.addProperty("shared_client_id", "fake-new-client");
        gateway.openid.remove("token_endpoint");
        registry.refreshAll(Map.of());

        AuthServer refreshed = registry.byApiRoot(API_ROOT);
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(refreshed)).isFalse();
        assertThat(refreshed.oauth().clientId()).isEqualTo("fake-shared-client");
        assertThat(refreshed.oauth().tokenEndpoint()).isEqualTo(API_ROOT + "/oauth/token");

        registry.persist(stored);
        registry.load(stored);
        assertThat(registry.byApiRoot(API_ROOT).oauth().deviceAuthorizationEndpoint()).isNull();
    }

    @Test
    void add_keepsClientIdOfExistingEntry() throws Exception {
        // 重复「添加认证服务器」同一个地址：端点刷新，client_id 保持原样
        JsonArray stored = new JsonArray();
        stored.add(JsonParser.parseString("{\"type\":\"yggdrasil\",\"apiRoot\":\"" + API_ROOT
                + "\",\"oauth\":{\"clientId\":\"fake-seeded\"}}"));
        registry.load(stored);

        AuthServer readded = registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of());

        assertThat(readded.oauth().clientId()).isEqualTo("fake-seeded");
        assertThat(readded.oauth().tokenEndpoint()).isEqualTo(API_ROOT + "/oauth/token");
        assertThat(readded.oauth().deviceAuthorizationEndpoint()).isEqualTo(API_ROOT + "/oauth/device");
        assertThat(registry.servers()).hasSize(1);
    }

    @Test
    void loadPersist_roundTripKeepsMigrationShape() throws Exception {
        JsonArray stored = new JsonArray();
        // 3→4 种下的形状：oauth 只有 clientId/tokenEndpoint，无 apiRoot 的固定条目
        stored.add(JsonParser.parseString("{\"type\":\"yggdrasil\",\"apiRoot\":\"" + API_ROOT
                + "\",\"locator\":\"http://injector.test\",\"futureField\":\"keep-me\",\"oauth\":{\"clientId\":\"fake-1214\",\"tokenEndpoint\":\"http://injector.test/oauth/token\"}}"));
        stored.add(JsonParser.parseString("{\"type\":\"mojang\"}"));
        registry.load(stored);

        assertThat(registry.servers()).hasSize(1); // builtin (mojang/offline) 不再落 servers
        assertThat(registry.byApiRoot(API_ROOT).oauth().clientId()).isEqualTo("fake-1214");
        assertThat(registry.byApiRoot(API_ROOT).oauth().deviceAuthorizationEndpoint()).isNull();

        // 生产路径是 persist 回同一份 live 数组（snapshot 传 root.servers()），未知字段靠此对齐
        registry.persist(stored);
        JsonArray out = stored;
        JsonObject ygg = out.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(j -> "yggdrasil".equals(j.get("type").getAsString()))
                .findFirst().orElseThrow();
        assertThat(ygg.get("oauth").getAsJsonObject().get("tokenEndpoint").getAsString())
                .isEqualTo("http://injector.test/oauth/token");
        // mojang 是 builtin kind，不再写入 servers 列表
        assertThat(out.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .noneMatch(j -> "mojang".equals(j.get("type").getAsString()))).isTrue();
        // 未建模字段原样回写：新→旧方向的字段不能被注册表吞掉
        assertThat(ygg.get("futureField").getAsString()).isEqualTo("keep-me");
        // 已废弃的 locator 不再回写：老配置里的残留会被顺手清掉
        assertThat(ygg.has("locator")).isFalse();
    }

    @Test
    void oauth_mergeKeepsSeededFields() {
        OAuthEndpoints seeded = new OAuthEndpoints("fake-1214", "https://x/oauth/token", null, null);
        OAuthEndpoints probed = new OAuthEndpoints(null, null, "https://x/oauth/device", "https://x/oauth/userinfo");
        OAuthEndpoints merged = OAuthEndpoints.merge(seeded, probed);
        assertThat(merged.clientId()).isEqualTo("fake-1214");
        assertThat(merged.tokenEndpoint()).isEqualTo("https://x/oauth/token");
        assertThat(merged.deviceAuthorizationEndpoint()).isEqualTo("https://x/oauth/device");
    }

    @Test
    void add_builtinNoNetwork() throws Exception {
        AuthServer mojang = registry.add(AuthServerKind.MOJANG, "mojang", Map.of());
        assertThat(mojang.apiRoot()).isNull();
        assertThat(gateway.counts).isEmpty();
    }

    @Test
    void probeFailure_propagatesOnAdd() {
        gateway.failGet = true;
        assertThatThrownBy(() -> registry.add(AuthServerKind.YGGDRASIL, LOCATOR, Map.of()))
                .isInstanceOf(IOException.class);
        // 加服失败不落盘：条目不存在
        assertThat(registry.byApiRoot(API_ROOT)).isNull();
    }
}
