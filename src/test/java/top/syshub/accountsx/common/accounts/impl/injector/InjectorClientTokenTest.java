package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider.AuthlibInjectorAccount;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外置登录（authlib-injector / drasl）的 {@code clientToken} 契约回归测试。
 *
 * <p>背景：drasl 的 {@code /authserver/refresh} 用 {@code mo.Some(req.ClientToken)} 强制比对
 * 客户端令牌（drasl {@code auth.go:304} → {@code model.go:383}），缺失即 403
 * {@code ForbiddenOperationException: Invalid token}。而 {@code /authserver/authenticate} 在请求省略
 * {@code clientToken} 时由服务端随机生成一个（{@code auth.go:178-192}），永远不会替客户端稳定下来。
 * 因此客户端必须自己生成一次、随账号持久化，并在登录与刷新时原样回传 —— 否则账号只在「登录那次」
 * 可用，下次启动刷新即失效。</p>
 *
 * <p>本测试用可注入的假网关断言请求体契约，并用 Gson 往返验证 {@code clientToken} 确实进了持久化载荷。</p>
 */
class InjectorClientTokenTest {

    private static final String SERVER = "https://drasl.example.com";
    private static final String YGGDRASIL = SERVER + "/api/yggdrasil";
    private static final String USER_NAME = "fake-user";
    private static final String PASSWORD = "fake-password";
    private static final String PLAYER_NAME = "FakePlayer";
    private static final UUID PLAYER_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String ACCESS_TOKEN = "fake-access-token";
    private static final String NEW_ACCESS_TOKEN = "fake-access-token-2";

    /** 生产载荷的真实类型：{@code List<BaseAccount>}（走 BaseAccount.Adapter 按 type 派发）。 */
    private static final java.lang.reflect.Type ACCOUNT_LIST_TYPE =
            new com.google.gson.reflect.TypeToken<List<? extends BaseAccount>>() {}.getType();

    /** 记录每次请求、按 URL 返回预置响应的假网关。 */
    private static final class ScriptedHttpGateway implements HttpGateway {
        private record Call(String method, String url, JsonElement body, boolean ignoredHttpStatus) {}

        private final List<Call> calls = new ArrayList<>();

        /** 非 null 时，refresh 端点返回该错误响应（模拟 drasl 的 403 Invalid token）。 */
        private JsonObject refreshError;

        /** 非 null 时，authenticate 端点返回该错误响应（模拟登录被拒）。 */
        private JsonObject authenticateError;

        List<Call> callsTo(String url) {
            return calls.stream().filter(c -> c.url().equals(url)).toList();
        }

        JsonObject lastBodyTo(String url) {
            List<Call> matched = callsTo(url);
            assertThat(matched).as("no request recorded for %s", url).isNotEmpty();
            return matched.get(matched.size() - 1).body().getAsJsonObject();
        }

        /** 最近一次发往该 URL 的 POST 是否忽略了 HTTP 状态码（决定能否读到错误体）。 */
        boolean lastPostIgnoredStatus(String url) {
            List<Call> matched = callsTo(url);
            assertThat(matched).as("no request recorded for %s", url).isNotEmpty();
            return matched.get(matched.size() - 1).ignoredHttpStatus();
        }

        /** drasl 的 meta：只有 serverName，没有 openid_configuration_url（drasl 不支持 OAuth 设备码）。 */
        private static JsonObject yggdrasilMeta() {
            JsonObject meta = new JsonObject();
            meta.addProperty("serverName", "Drasl");
            JsonObject json = new JsonObject();
            json.add("meta", meta);
            return json;
        }

        private static JsonObject emptyProfile() {
            JsonObject json = new JsonObject();
            json.addProperty("id", PLAYER_UUID.toString().replace("-", ""));
            json.addProperty("name", PLAYER_NAME);
            // 无 textures 属性 → AvatarService 解析不到皮肤 URL，直接返回 null，不触发 AWT / 下载。
            json.add("properties", new JsonArray());
            return json;
        }

        private static JsonObject authenticateResponse() {
            JsonObject profile = new JsonObject();
            profile.addProperty("name", PLAYER_NAME);
            profile.addProperty("id", PLAYER_UUID.toString());

            JsonObject json = new JsonObject();
            json.addProperty("accessToken", ACCESS_TOKEN);
            json.add("selectedProfile", profile);
            return json;
        }

        /** 非 null 时用作 refresh 的成功响应（用于断言服务端回显的 clientToken 被采纳）。 */
        private JsonObject refreshSuccess;

        private static JsonObject refreshResponse() {
            JsonObject profile = new JsonObject();
            profile.addProperty("name", PLAYER_NAME);
            profile.addProperty("id", PLAYER_UUID.toString());

            JsonObject json = new JsonObject();
            json.addProperty("accessToken", NEW_ACCESS_TOKEN);
            json.add("selectedProfile", profile);
            return json;
        }

        @Override
        public JsonObject get(String url) {
            calls.add(new Call("GET", url, null, false));
            if (url.equals(YGGDRASIL)) {
                return yggdrasilMeta();
            }
            if (url.startsWith(YGGDRASIL + "/sessionserver/session/minecraft/profile/")) {
                return emptyProfile();
            }
            throw new UnsupportedOperationException("fake: unexpected GET " + url);
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            return get(url);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            return postJson(url, body, false);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            calls.add(new Call("POST", url, body, ignoreHttpStatus));
            if (url.equals(YGGDRASIL + "/authserver/authenticate")) {
                return authenticateError != null ? authenticateError : authenticateResponse();
            }
            if (url.equals(YGGDRASIL + "/authserver/refresh")) {
                if (refreshError != null) return refreshError;
                return refreshSuccess != null ? refreshSuccess : refreshResponse();
            }
            throw new UnsupportedOperationException("fake: unexpected POST " + url);
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException("fake: postForm not expected (drasl 无 OAuth 端点)");
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            return postForm(url, formData);
        }

        /** HEAD 用于解析 X-Authlib-Injector-API-Location；空头即让 provider 退回 {@code <base>/api/yggdrasil}。 */
        @Override
        public Map<String, List<String>> head(String url) {
            calls.add(new Call("HEAD", url, null, false));
            return Map.of();
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }
    }

    private static AuthlibInjectorAccountProvider provider(HttpGateway http) {
        return new AuthlibInjectorAccountProvider(http);
    }

    /** 测试内自带的 {@link Memory} 实现：登录路径只用到 set/get，不需要屏幕状态。 */
    private static final class MapMemory implements Memory {
        private final Map<String, Object> values = new ConcurrentHashMap<>();

        @Override
        public <T> void set(String guid, T value) {
            values.put(guid, value);
        }

        @Override
        public <T> T get(String guid, Class<T> type) {
            return type.cast(values.get(guid));
        }

        @Override
        public boolean isScreenClosed() {
            return false;
        }
    }

    private static Memory loginMemory() {
        Memory memory = new MapMemory();
        memory.set("guid:as.login.injector.widgets.server_url", SERVER);
        memory.set("guid:as.login.injector.widgets.user_name", USER_NAME);
        memory.set("guid:as.login.injector.widgets.user_password", PASSWORD);
        memory.set("guid:as.login.injector.widgets.player_name", "");
        return memory;
    }

    /** 密码登录必须自带 clientToken，否则服务端每次都会新建 client，刷新时无 token 可比对。 */
    @Test
    void passwordLogin_sendsOwnClientToken() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();

        AuthlibInjectorAccount account = provider(fake).login(loginMemory());

        JsonObject body = fake.lastBodyTo(YGGDRASIL + "/authserver/authenticate");
        assertThat(body.get("clientToken").getAsString()).isNotBlank();
        assertThat(account.getClientToken()).isEqualTo(body.get("clientToken").getAsString());
    }

    /** clientToken 必须落到持久化载荷里，否则重启后刷新又变回无 token。 */
    @Test
    void clientToken_survivesPersistence() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        AuthlibInjectorAccount account = provider(fake).login(loginMemory());

        String json = NetworkUtils.GSON.toJson(account);
        assertThat(json).contains(account.getClientToken());

        // 走生产路径：载荷是 List<BaseAccount>，由 BaseAccount.Adapter 按 "type" 派发到具体类
        // （@JsonAdapter 不继承，直接 fromJson(..., AuthlibInjectorAccount.class) 并不覆盖这条分派）。
        List<? extends BaseAccount> restored = NetworkUtils.GSON.fromJson("[" + json + "]", ACCOUNT_LIST_TYPE);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0)).isInstanceOf(AuthlibInjectorAccount.class);
        AuthlibInjectorAccount reloaded = (AuthlibInjectorAccount) restored.get(0);
        assertThat(reloaded.getClientToken()).isEqualTo(account.getClientToken());
        assertThat(reloaded.getLoginToken()).isEqualTo(account.getLoginToken());
    }

    /** 刷新必须回传持久化的 clientToken —— 这是 drasl 上「第二次打不开」的直接原因。 */
    @Test
    void refresh_sendsPersistedClientToken() throws ExecutionException, InterruptedException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        String clientToken = UUID.randomUUID().toString();

        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, clientToken
        );

        // refresh 内的 setProfile/setLoginProfile 要求 worker 线程，故经调度器在 worker 线程上执行。
        TaskScheduler.submitParallel(() -> provider(fake).refresh(account)).get();

        JsonObject body = fake.lastBodyTo(YGGDRASIL + "/authserver/refresh");
        assertThat(body.get("clientToken").getAsString()).isEqualTo(clientToken);
        assertThat(body.get("accessToken").getAsString()).isEqualTo(ACCESS_TOKEN);
        assertThat(account.getLoginToken()).isEqualTo(NEW_ACCESS_TOKEN);
    }

    /** 固定 32 字符十六进制：drasl 自己生成的就是这个形状，且第三方 Yggdrasil 普遍按 32 字符校验。 */
    @Test
    void generatedClientToken_is32HexChars() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();

        AuthlibInjectorAccount account = provider(fake).login(loginMemory());

        assertThat(account.getClientToken()).matches("[0-9a-f]{32}");
    }

    /** 兼容性：旧配置的账号没有 clientToken 字段，反序列化后为 null，不能因此加载失败。 */
    @Test
    void legacyPayloadWithoutClientToken_loads() {
        List<? extends BaseAccount> loaded = NetworkUtils.GSON.fromJson(
                "[" + legacyAccountJson() + "]",
                ACCOUNT_LIST_TYPE
        );
        AuthlibInjectorAccount restored = (AuthlibInjectorAccount) loaded.get(0);

        assertThat(restored).isNotNull();
        assertThat(restored.getClientToken()).isNull();
        assertThat(restored.getLoginToken()).isEqualTo(ACCESS_TOKEN);
    }

    /** 记录用：断言假网关真的收到了 HEAD（transformServerBaseURL 的探测），避免测试假象。 */
    @Test
    void serverBaseUrl_isResolvedThroughHeadProbe() throws IOException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();

        provider(fake).login(loginMemory());

        assertThat(fake.callsTo(SERVER)).isNotEmpty();
    }

    /** 旧账号刷新：没有 clientToken 时给出可操作提示，且不发出注定被 403 的请求。 */
    @Test
    void legacyAccountRefresh_failsActionablyWithoutRequest() {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        AuthlibInjectorAccount legacy = NetworkUtils.GSON.fromJson(legacyAccountJson(), AuthlibInjectorAccount.class);

        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> provider(fake).refresh(legacy)).get())
                .hasRootCauseInstanceOf(InjectorAuthException.class)
                .rootCause()
                .hasMessageContaining("clientToken");

        assertThat(fake.callsTo(YGGDRASIL + "/authserver/refresh")).isEmpty();
    }

    /** 服务端 403：携带 error/errorMessage 作为可操作提示，且绝不泄漏 accessToken。 */
    @Test
    void rejectedRefresh_doesNotLeakAccessToken() {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        JsonObject error = new JsonObject();
        error.addProperty("error", "ForbiddenOperationException");
        error.addProperty("errorMessage", "Invalid token");
        fake.refreshError = error;

        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, "fake-client-token"
        );

        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> provider(fake).refresh(account)).get())
                .hasRootCauseInstanceOf(InjectorAuthException.class)
                .rootCause()
                .hasMessageContaining("ForbiddenOperationException")
                .hasMessageContaining("Invalid token")
                .hasMessageNotContaining(ACCESS_TOKEN);
    }

    /**
     * 不规范的错误响应（{@code "error": null}）不得绕过 {@link InjectorAuthException}。
     *
     * <p>原实现直接对 {@code error} 调 {@code getAsString()}，遇到 JsonNull 会抛
     * {@link UnsupportedOperationException}，用户看到的会退化成「未知错误」。</p>
     */
    @Test
    void malformedErrorResponse_stillYieldsActionableException() {
        // "error": null 视为“没有错误字段”，走成功路径 → 由 requireAccessToken 报出，而不是 NPE。
        ScriptedHttpGateway nullErrorFake = new ScriptedHttpGateway();
        JsonObject nullError = new JsonObject();
        nullError.add("error", JsonNull.INSTANCE);
        nullErrorFake.refreshError = nullError;

        AuthlibInjectorAccount nullErrorAccount = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, "fake-client-token"
        );
        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> provider(nullErrorFake).refresh(nullErrorAccount)).get())
                .rootCause()
                .isInstanceOf(IOException.class)
                .hasMessageContaining("accessToken");

        // "error" 是对象：仍必须产出可操作异常，而不是 UnsupportedOperationException。
        ScriptedHttpGateway objectErrorFake = new ScriptedHttpGateway();
        JsonObject objectError = new JsonObject();
        JsonObject nested = new JsonObject();
        nested.addProperty("code", 403);
        objectError.add("error", nested);
        objectErrorFake.refreshError = objectError;

        AuthlibInjectorAccount objectErrorAccount = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, "fake-client-token"
        );
        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> provider(objectErrorFake).refresh(objectErrorAccount)).get())
                .rootCause()
                .isInstanceOf(InjectorAuthException.class);
    }

    /**
     * 认证请求必须忽略 HTTP 状态码：Yggdrasil 的失败是标准 JSON 错误体（drasl 一律 403），
     * 若先被网关的状态码校验拦掉，就只剩 {@code HTTP 403}，服务端原因与 i18n 提示全部丢失。
     * 真机上验证过该缺陷，这里锁住回归。
     */
    @Test
    void authRequestsIgnoreHttpStatusSoErrorBodyIsReachable() {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        JsonObject error = new JsonObject();
        error.addProperty("error", "ForbiddenOperationException");
        error.addProperty("errorMessage", "Invalid token");
        fake.refreshError = error;

        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, "fake-client-token"
        );

        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> provider(fake).refresh(account)).get())
                .rootCause()
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("Invalid token");

        assertThat(fake.lastPostIgnoredStatus(YGGDRASIL + "/authserver/refresh"))
                .as("refresh 必须用 ignoreHttpStatus=true 才能读到 403 的错误体")
                .isTrue();
    }

    /** 服务端在响应里回显不同的 clientToken 时必须被采纳，并在下一次刷新时使用新值。 */
    @Test
    void rotatedClientToken_isAdoptedAndUsedNextTime() throws ExecutionException, InterruptedException {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        JsonObject rotated = new JsonObject();
        rotated.addProperty("accessToken", NEW_ACCESS_TOKEN);
        rotated.addProperty("clientToken", "rotated-client-token");
        JsonObject profile = new JsonObject();
        profile.addProperty("name", PLAYER_NAME);
        profile.addProperty("id", PLAYER_UUID.toString());
        rotated.add("selectedProfile", profile);
        fake.refreshSuccess = rotated;

        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                ACCESS_TOKEN, PLAYER_NAME, PLAYER_UUID,
                YGGDRASIL, PLAYER_UUID.toString(), "Drasl", null, 0L, "original-client-token"
        );
        TaskScheduler.submitParallel(() -> provider(fake).refresh(account)).get();

        assertThat(account.getClientToken()).isEqualTo("rotated-client-token");

        // 轮换后的值必须进入持久化载荷，供下次启动使用。
        List<? extends BaseAccount> persisted = NetworkUtils.GSON.fromJson(
                "[" + NetworkUtils.GSON.toJson(account) + "]",
                ACCOUNT_LIST_TYPE
        );
        AuthlibInjectorAccount reloaded = (AuthlibInjectorAccount) persisted.get(0);
        assertThat(reloaded.getClientToken()).isEqualTo("rotated-client-token");
    }

    /** 登录被拒（密码写错等）应给出「检查凭据」而不是「删除后重新添加」——此时还没有账号可删。 */
    @Test
    void rejectedLogin_usesCredentialHintKey() {
        ScriptedHttpGateway fake = new ScriptedHttpGateway();
        JsonObject error = new JsonObject();
        error.addProperty("error", "ForbiddenOperationException");
        error.addProperty("errorMessage", "Invalid credentials. Invalid username or password.");
        fake.authenticateError = error;

        assertThatThrownBy(() -> provider(fake).login(loginMemory()))
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("Invalid credentials");
    }

    /** 失败提示使用的 i18n key 必须真实存在，否则界面会直接显示原始 key。 */
    @Test
    void failureTranslationKeysExistInLangFiles() throws IOException {
        for (String lang : List.of("en_us.json", "zh_cn.json")) {
            try (var in = getClass().getResourceAsStream("/assets/accountsx/lang/" + lang)) {
                assertThat(in).as("%s not found", lang).isNotNull();
                JsonObject json = NetworkUtils.GSON.fromJson(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
                assertThat(json.keySet()).contains(
                        "accountsx.account.fail.injector_login",
                        "accountsx.account.fail.injector_invalid_token",
                        "accountsx.account.fail.unknown",
                        "accountsx.account.fail.title"
                );
            }
        }
    }

    private static JsonObject legacyAccountJson() {
        JsonObject legacy = new JsonObject();
        legacy.addProperty("accessToken", ACCESS_TOKEN);
        legacy.addProperty("playerName", PLAYER_NAME);
        legacy.addProperty("playerUUID", PLAYER_UUID.toString());
        legacy.addProperty("server", YGGDRASIL);
        legacy.addProperty("loginToken", ACCESS_TOKEN);
        legacy.addProperty("preferredPlayerUUID", PLAYER_UUID.toString());
        legacy.addProperty("type", "injector.authlib-injector");
        legacy.addProperty("accountName", "Drasl");
        return legacy;
    }
}
