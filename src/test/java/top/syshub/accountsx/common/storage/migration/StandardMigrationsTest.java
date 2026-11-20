package top.syshub.accountsx.common.storage.migration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.storage.ConfigStore;
import top.syshub.accountsx.common.storage.JsonConfigStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2.3 迁移链测试（08 文档测试清单：每跳一个用例 + v0→4 端到端 + 方案 C 场景矩阵）。
 * 全程 jimfs；所有凭据值 fake- 前缀。
 */
class StandardMigrationsTest {

    private static final Gson GSON = new Gson();
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    private final FileSystem fs = Jimfs.newFileSystem(
            Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build());
    private final Path global = fs.getPath("/config/accountsx.json");
    private final Path instanceDir = fs.getPath("/game/config/accountsx");
    private final Path legacyHome = fs.getPath("/home/.accountsx");
    private final Path avatarDir = fs.getPath("/home/.cache/accountsx/avatars");

    private final MigrationContext ctx = new MigrationContext() {
        @Override
        public Path legacyHomeDir() {
            return legacyHome;
        }

        @Override
        public Path avatarCacheDir() {
            return avatarDir;
        }
    };

    private JsonConfigStore store() {
        return new JsonConfigStore(fs, global, GSON, StandardMigrations.chain(), ctx, (title, desc, args) -> { });
    }

    private JsonObject doc(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void writeInstanceConfig(String id) throws IOException {
        Files.createDirectories(instanceDir);
        Files.writeString(instanceDir.resolve("accounts.json"),
                "{\"version\":3,\"id\":\"" + id + "\"}", StandardCharsets.UTF_8);
    }

    private void writePayload(String id, String accountsJson) throws IOException {
        Files.createDirectories(legacyHome);
        Files.writeString(legacyHome.resolve(id + ".json"), accountsJson, StandardCharsets.UTF_8);
    }

    // ── 逐跳 ─────────────────────────────────────────────────────

    @Test
    void hop0to1_removesInjectorAccounts() {
        JsonObject config = doc("{\"version\":0,\"accounts\":[{\"type\":\"INJECTOR\"},{\"type\":\"OFFLINE\"}]}");
        JsonObject out = new InjectorSafety().apply(config, ctx).config();

        assertThat(out.getAsJsonArray("accounts")).hasSize(1);
        assertThat(out.getAsJsonArray("accounts").get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("OFFLINE");
    }

    @Test
    void hop1to2_renamesKnownAndDropsUnknown() {
        JsonObject config = doc("{\"version\":1,\"accounts\":[{\"type\":\"OFFLINE\"},{\"type\":\"MICROSOFT\"},"
                + "{\"type\":\"INJECTOR\"},{\"type\":\"BOGUS\"}]}");
        JsonObject out = new RenameAccountType().apply(config, ctx).config();

        JsonArray accounts = out.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(3);
        assertThat(accounts.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("offline");
        assertThat(accounts.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("microsoft");
        assertThat(accounts.get(2).getAsJsonObject().get("type").getAsString()).isEqualTo("injector.authlib-injector");
    }

    @Test
    void v2UsesInlineAccountsAndNeverReadsOrRewritesUnrelatedV3Payload() throws Exception {
        writePayload(ID, "[{}]");
        Path payload = legacyHome.resolve(ID + ".json");
        JsonObject v2 = new JsonObject();
        v2.addProperty("version", 2);
        v2.addProperty("id", ID);
        v2.add("accounts", JsonParser.parseString("[{\"type\":\"offline\"}]"));

        Migration.Result upgraded = StandardMigrations.chain().upgrade(v2, 2, ctx);

        assertThat(upgraded.config().getAsJsonArray("accounts")).hasSize(1);
        assertThat(upgraded.config().get("version").getAsInt()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(upgraded.legacyPayload()).isNull();
        assertThat(Files.readString(payload)).isEqualTo("[{}]");
        assertThat(Files.exists(global)).isFalse();

        v2.remove("accounts");
        assertThat(StandardMigrations.chain().upgrade(v2, 2, ctx).config().getAsJsonArray("accounts")).isEmpty();
        v2.add("accounts", new JsonObject());
        assertThatThrownBy(() -> StandardMigrations.chain().upgrade(v2, 2, ctx))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("accounts");
    }

    // ── 3→4 UNIFY_AND_DECOUPLE ───────────────────────────────────

    private JsonObject legacyV3Doc() {
        return doc("{\"version\":3,\"id\":\"" + ID + "\"}");
    }

    private String legacyPayload() {
        String b64 = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        return "["
                + "{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}},"
                + "{\"type\":\"microsoft\",\"storage\":{\"accessToken\":\"fake-mc-token\",\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"},"
                + "\"microsoftAccountAccessToken\":\"fake-xbl-token\",\"microsoftAccountRefreshToken\":\"fake-ms-refresh\"},"
                + "{\"type\":\"injector.authlib-injector\",\"storage\":{\"accessToken\":\"fake-ygg-token\",\"playerName\":\"Alex\",\"playerUUID\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"},"
                + "\"server\":\"https://mcskin.ecustvr.top/api/yggdrasil\",\"loginToken\":\"fake-ygg-token\",\"accountName\":\"ECUSTMC\",\"avatarKey\":\"fake-key-1\"},"
                + oauthAccountJson()
                + ","
                // 统一通行证类型已移除：迁移应告警并跳过该条目，其余账号照常转换
                + "{\"type\":\"injector.united\",\"storage\":{\"accessToken\":\"fake-united-token\",\"playerName\":\"Alex\",\"playerUUID\":\"cccccccc-dddd-eeee-ffff-aaaaaaaaaaaa\"},"
                + "\"server\":\"https://auth.mc-user.com:233/0123456789abcdef0123456789abcdef\",\"loginToken\":\"fake-united-token\",\"accountName\":\"UnitedPass\",\"avatar\":\"" + b64 + "\"}"
                + "]";
    }

    /** OAuth 信封含内嵌引号，用 Gson 现场拼，避免 fixture 里三层转义。 */
    private String oauthAccountJson() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("token_endpoint", "https://littleskin.cn/oauth/token");
        envelope.addProperty("refresh_token", "fake-ls-refresh");
        envelope.addProperty("client_id", "1214");
        JsonObject account = new JsonObject();
        account.addProperty("type", "injector.authlib-injector");
        JsonObject storage = new JsonObject();
        storage.addProperty("accessToken", "fake-ls-at");
        storage.addProperty("playerName", "Alex");
        storage.addProperty("playerUUID", "bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        account.add("storage", storage);
        account.addProperty("server", "https://littleskin.cn/api/yggdrasil");
        account.addProperty("loginToken", "OAuth " + envelope);
        account.addProperty("accountName", "LittleSkin");
        return account.toString();
    }

    @Test
    void startupMigratesAllV3AccountShapesAndCleansUp() throws Exception {
        writeInstanceConfig(ID);
        writePayload(ID, legacyPayload());

        JsonObject out = store().load(instanceDir).raw();

        assertThat(out.get("version").getAsInt()).isEqualTo(4);
        JsonArray accounts = out.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(4);
        assertThat(accounts.toString()).doesNotContain("\"united\"");

        // offline：uuid 归一为无横线 32 位 hex，无 credentials
        JsonObject offline = accounts.get(0).getAsJsonObject();
        assertThat(offline.get("profile").getAsJsonObject().get("uuid").getAsString())
                .isEqualTo("069a79f444e94726a5befca90e38aaf5");
        assertThat(offline.has("credentials")).isFalse();

        // microsoft：XBL 中间 token 不进 v4（扩大明文落地面且可由 refreshToken 重建）
        JsonObject ms = accounts.get(1).getAsJsonObject();
        assertThat(ms.getAsJsonObject("credentials").get("accessToken").getAsString()).isEqualTo("fake-mc-token");
        assertThat(ms.getAsJsonObject("credentials").get("refreshToken").getAsString()).isEqualTo("fake-ms-refresh");
        assertThat(out.toString()).doesNotContain("fake-xbl-token");

        // yggdrasil 密码流：原样 accessToken + avatarKey 沿用
        JsonObject ygg = accounts.get(2).getAsJsonObject();
        assertThat(ygg.get("type").getAsString()).isEqualTo("yggdrasil");
        assertThat(ygg.get("server").getAsString()).isEqualTo("https://mcskin.ecustvr.top/api/yggdrasil");
        assertThat(ygg.getAsJsonObject("credentials").entrySet()).hasSize(1);
        assertThat(ygg.get("avatarKey").getAsString()).isEqualTo("fake-key-1");

        // yggdrasil OAuth：信封拆成 credentials{oauth,accessToken,refreshToken}，端点种进 server.oauth
        JsonObject oauth = accounts.get(3).getAsJsonObject();
        assertThat(oauth.getAsJsonObject("credentials").get("oauth").getAsBoolean()).isTrue();
        assertThat(oauth.getAsJsonObject("credentials").get("refreshToken").getAsString()).isEqualTo("fake-ls-refresh");
        assertThat(oauth.getAsJsonObject("credentials").get("accessToken").getAsString()).isEqualTo("fake-ls-at");
        assertThat(oauth.has("loginToken")).isFalse();

        // servers：2 个真实 yggdrasil，无 builtin mojang/offline、无 united
        assertThat(out.getAsJsonArray("servers")).hasSize(2);
        JsonObject lsServer = findServer(out, "https://littleskin.cn/api/yggdrasil");
        assertThat(lsServer.get("displayName").getAsString()).isEqualTo("LittleSkin");
        assertThat(lsServer.getAsJsonObject("oauth").get("clientId").getAsString()).isEqualTo("1214");
        assertThat(lsServer.getAsJsonObject("oauth").get("tokenEndpoint").getAsString())
                .isEqualTo("https://littleskin.cn/oauth/token");

        // 全局文件已落盘（先落盘再删旧文件）
        JsonObject onDisk = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(onDisk.get("version").getAsInt()).isEqualTo(4);
        assertThat(onDisk.getAsJsonArray("accounts")).hasSize(4);

        // 旧文件处理（方案 C）：载荷被删、目录空了被删、实例配置改名 .migrated
        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isFalse();
        assertThat(Files.exists(legacyHome)).isFalse();
        assertThat(Files.exists(instanceDir.resolve("accounts.json"))).isFalse();
        assertThat(Files.exists(instanceDir.resolve("accounts.json.migrated"))).isTrue();
    }

    @Test
    void hop3to4_seedsServerOauthFromWhicheverAccountHasIt() throws Exception {
        // 同一服务器上先出现的可能是密码登录的账号（没有信封）：条目建出来时没有 oauth，
        // 后面的 OAuth 账号必须能把 client_id / token_endpoint 补进去，否则它读回时降级成"需重登"。
        String passwordFirst = "{\"type\":\"injector.authlib-injector\",\"storage\":{\"accessToken\":\"fake-pw-at\","
                + "\"playerName\":\"PwUser\",\"playerUUID\":\"dddddddd-eeee-ffff-aaaa-bbbbbbbbbbbb\"},"
                + "\"server\":\"https://littleskin.cn/api/yggdrasil\",\"loginToken\":\"fake-pw-at\",\"accountName\":\"LittleSkin\"}";
        writeInstanceConfig(ID);
        writePayload(ID, "[" + passwordFirst + "," + oauthAccountJson() + "]");

        JsonObject out = StandardMigrations.chain().upgrade(legacyV3Doc(), 3, ctx).config();

        assertThat(out.getAsJsonArray("accounts")).hasSize(2);
        JsonArray servers = out.getAsJsonArray("servers");
        assertThat(servers).hasSize(1); // 同一 apiRoot 不重复建条目
        JsonObject server = servers.get(0).getAsJsonObject();
        assertThat(server.get("apiRoot").getAsString()).isEqualTo("https://littleskin.cn/api/yggdrasil");
        assertThat(server.get("displayName").getAsString()).isEqualTo("LittleSkin");
        JsonObject oauth = server.getAsJsonObject("oauth");
        assertThat(oauth.get("clientId").getAsString()).isEqualTo("1214");
        assertThat(oauth.get("tokenEndpoint").getAsString()).isEqualTo("https://littleskin.cn/oauth/token");

        // 补种只影响服务器条目，账号自己的凭据不被互相污染
        assertThat(out.getAsJsonArray("accounts").get(0).getAsJsonObject().getAsJsonObject("credentials")
                .has("oauth")).isFalse();
        assertThat(out.getAsJsonArray("accounts").get(1).getAsJsonObject().getAsJsonObject("credentials")
                .get("oauth").getAsBoolean()).isTrue();
    }

    @Test
    void hop3to4_brokenOAuthKeepsAccountAndClearsCredentials() throws Exception {
        writeInstanceConfig(ID);
        JsonObject bad = doc("{\"type\":\"injector.authlib-injector\","
                + "\"storage\":{\"accessToken\":\"fake-a\",\"playerName\":\"Alex\",\"playerUUID\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"},"
                + "\"server\":\"https://example.invalid/api/yggdrasil\"}");
        bad.addProperty("loginToken", "OAuth { not json");
        JsonObject missingField = doc("{\"type\":\"injector.authlib-injector\","
                + "\"storage\":{\"accessToken\":\"fake-b\",\"playerName\":\"Bob\",\"playerUUID\":\"bbbbbbbb-cccc-dddd-eeee-ffffffffffff\"},"
                + "\"server\":\"https://other.invalid/api/yggdrasil\"}");
        JsonObject partial = new JsonObject();
        partial.addProperty("token_endpoint", "https://other.invalid/oauth/token");
        missingField.addProperty("loginToken", "OAuth " + partial);
        writePayload(ID, "[" + bad + "," + missingField + "]");

        JsonObject out = StandardMigrations.chain().upgrade(legacyV3Doc(), 3, ctx).config();

        JsonArray accounts = out.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(2);
        // 降级为"需要重新登录"：条目与 profile 保留、credentials 清空，账号不消失
        assertThat(accounts.get(0).getAsJsonObject().has("credentials")).isFalse();
        assertThat(accounts.get(0).getAsJsonObject().getAsJsonObject("profile").get("name").getAsString()).isEqualTo("Alex");
        assertThat(accounts.get(1).getAsJsonObject().has("credentials")).isFalse();
    }

    @Test
    void hop3to4_schemeC_leavesOtherInstancesPayloadsUntouched() throws Exception {
        writeInstanceConfig(ID);
        writePayload(ID, "[{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}}]");
        byte[] other1 = "[{\"type\":\"offline\"}]".getBytes(StandardCharsets.UTF_8);
        Files.write(legacyHome.resolve("other-1.json"), other1);
        Files.write(legacyHome.resolve("other-2.json"), other1);

        store().load(instanceDir);

        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isFalse();
        assertThat(Files.readAllBytes(legacyHome.resolve("other-1.json"))).isEqualTo(other1);
        assertThat(Files.readAllBytes(legacyHome.resolve("other-2.json"))).isEqualTo(other1);
        assertThat(Files.exists(legacyHome)).isTrue(); // 还有别人的账号集，目录不删
    }

    @Test
    void hop3to4_corruptPayloadRefusesAndKeepsEverything() throws Exception {
        writeInstanceConfig(ID);
        writePayload(ID, "{ \"not\": \"an array\" }");
        assertThatThrownBy(() -> StandardMigrations.chain().upgrade(legacyV3Doc(), 3, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数组");
        // 读不懂的载荷绝不删除
        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isTrue();
        assertThat(Files.exists(instanceDir.resolve("accounts.json"))).isTrue();

        writePayload(ID, "{ this is not json ]");
        assertThatThrownBy(() -> StandardMigrations.chain().upgrade(legacyV3Doc(), 3, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析失败");
        assertThat(Files.exists(legacyHome.resolve(ID + ".json"))).isTrue();
        assertThat(Files.exists(global)).isFalse(); // 未落盘，全局文件原样（此前不存在）
    }

    @Test
    void hop3to4_missingIdRefusesToRun() {
        JsonObject config = doc("{\"version\":3}");
        assertThatThrownBy(() -> StandardMigrations.chain().upgrade(config, 3, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id");
    }

    @Test
    void hop3to4_invalidIdIsOmittedFromDiagnostic() {
        JsonObject config = legacyV3Doc();
        config.addProperty("id", "fake-id" + (char) 0);
        assertThatThrownBy(() -> StandardMigrations.chain().upgrade(config, 3, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("载荷文件名")
                .hasMessageNotContaining("fake-id")
                .hasNoCause();
    }

    @Test
    void hop3to4_payloadMissingWritesEmptyGlobalAndCleansUp() throws Exception {
        writeInstanceConfig(ID); // 载荷不存在：实例从未添加过账号

        JsonObject out = store().load(instanceDir).raw();

        assertThat(out.getAsJsonArray("accounts")).isEmpty();
        assertThat(out.getAsJsonArray("servers")).hasSize(0); // 无 builtin mojang/offline
        assertThat(Files.exists(instanceDir.resolve("accounts.json.migrated"))).isTrue();
    }

    // ── 端到端 ───────────────────────────────────────────────────

    @Test
    void fullChain_v0ToV4_endToEnd() throws Exception {
        JsonObject v0 = doc("{\"version\":0,\"accounts\":[{\"type\":\"INJECTOR\"},"
                + "{\"type\":\"OFFLINE\",\"storage\":{\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}},"
                + "{\"type\":\"MICROSOFT\",\"storage\":{\"accessToken\":\"fake-mc\",\"playerName\":\"Sam\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf7\"},"
                + "\"microsoftAccountRefreshToken\":\"fake-r\"},"
                + "{\"type\":\"BOGUS\"}]}");

        JsonObject out = StandardMigrations.chain().upgrade(v0, 0, ctx).config();

        assertThat(out.get("version").getAsInt()).isEqualTo(ConfigStore.CURRENT_VERSION);
        // INJECTOR 在 0→1 删除、BOGUS 在 1→2 跳过；2→4 转换内联账号。
        assertThat(out.getAsJsonArray("accounts")).hasSize(2);
        assertThat(out.getAsJsonArray("servers")).hasSize(0);
        assertThat(Files.exists(global)).isFalse();
        assertThat(Files.exists(legacyHome)).isFalse();
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private static JsonObject findServer(JsonObject out, String apiRoot) {
        for (var e : out.getAsJsonArray("servers")) {
            JsonObject so = e.getAsJsonObject();
            if (so.has("apiRoot") && so.get("apiRoot").getAsString().equals(apiRoot))
                return so;
        }
        throw new AssertionError("server not found: " + apiRoot);
    }
}