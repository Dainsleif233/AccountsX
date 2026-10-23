package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider.AuthlibInjectorAccount;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.net.JdkHttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「关游戏 → 重开」的可重复回归测试，用本地假 Yggdrasil 服务器模拟 drasl 的令牌语义。
 *
 * <p>假服务器实现与 drasl 一致的两条关键规则：</p>
 * <ul>
 *   <li>{@code /authserver/refresh} 要求 {@code clientToken} 与服务端记录一致，否则 403
 *       {@code ForbiddenOperationException: Invalid token}（drasl {@code GetClient}）；</li>
 *   <li>每次成功刷新都递增版本号并签发新 JWT，<b>旧 accessToken 立即失效</b>（drasl {@code AuthRefresh}）。</li>
 * </ul>
 *
 * <p>这个组合意味着：刷新成功后若不把新令牌写回磁盘，下次启动必然拿旧令牌去刷新 → 403。
 * 后者正是「外置登录只有第一次能用」在第二次重启后的表现，本测试把它钉死。</p>
 */
class LiveYggdrasilLifecycleTest {

    /** 服务端的 client：clientToken 固定，accessToken 每次刷新后轮换（旧值立即失效，同 drasl 版本号）。 */
    private static final class Client {
        final String clientToken;
        String currentAccessToken;

        Client(String clientToken) {
            this.clientToken = clientToken;
        }
    }

    private final Map<String, Client> clientsByAccessToken = new ConcurrentHashMap<>();
    private final Map<String, Client> clientsByClientToken = new ConcurrentHashMap<>();
    private final AtomicInteger tokenSeq = new AtomicInteger();

    private HttpServer server;
    private String serverBase;
    private String apiBase;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // 所有请求都落到根 context，再按路径分派：HttpServer 会选最长匹配前缀，
        // 因此不能用 "/" 之外的 context，否则根 context 会把它下面的路径全吃掉。
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonObject response;
            int status;

            if (path.contains("/authserver/authenticate")) {
                response = authenticate();
                status = 200;
            } else if (path.contains("/authserver/refresh")) {
                response = refresh(exchange);
                status = response.has("error") ? 403 : 200;
            } else if (path.contains("/sessionserver/session/minecraft/profile/")) {
                response = profile();
                status = 200;
            } else {
                response = metadata();
                status = 200;
            }

            // drasl 在**每个**响应上都带这个头（main.go 的全局中间件），provider 据此解析 API 根；
            // 没有它就会按 authlib-injector 文档回退到 <base>/api/yggdrasil。
            exchange.getResponseHeaders().add("X-Authlib-Injector-API-Location", apiBase);

            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        server.start();
        serverBase = "http://127.0.0.1:" + server.getAddress().getPort();
        apiBase = serverBase + "/authlib-injector";
    }

    /** authlib-injector 元数据（provider 解析 signaturePublickey / skinDomains / serverName）。 */
    private static JsonObject metadata() {
        JsonObject meta = new JsonObject();
        meta.addProperty("serverName", "FakeDrasl");
        JsonObject body = new JsonObject();
        body.add("meta", meta);
        body.addProperty("signaturePublickey", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----");
        body.add("skinDomains", new JsonArray());
        return body;
    }

    private static JsonObject profile() {
        JsonObject body = new JsonObject();
        body.addProperty("id", "069a79f444e94726a5befca90e38aaf5");
        body.addProperty("name", "FakePlayer");
        body.add("properties", new JsonArray());
        return body;
    }

    /** 登录：签发 clientToken + 版本 0 的 accessToken。 */
    private JsonObject authenticate() {
        String clientToken = "client-token-" + tokenSeq.incrementAndGet();
        Client client = new Client(clientToken);
        String accessToken = issue(client);

        JsonObject profile = new JsonObject();
        profile.addProperty("name", "FakePlayer");
        profile.addProperty("id", "069a79f444e94726a5befca90e38aaf5");
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("clientToken", clientToken);
        body.add("selectedProfile", profile);
        return body;
    }

    /** drasl 的刷新语义：accessToken 必须是当前版本，clientToken 必须匹配。 */
    private JsonObject refresh(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        JsonObject request = NetworkUtils.GSON.fromJson(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);

        JsonObject error = new JsonObject();
        error.addProperty("error", "ForbiddenOperationException");
        error.addProperty("errorMessage", "Invalid token");

        String accessToken = request.has("accessToken") ? request.get("accessToken").getAsString() : "";
        String clientToken = request.has("clientToken") ? request.get("clientToken").getAsString() : "";

        Client client = clientsByAccessToken.get(accessToken);
        // 与 drasl 的 GetClient 一致：令牌必须属于该 client、clientToken 必须匹配，
        // 且必须是「当前版本」的 accessToken（刷新过的旧令牌立即失效）。
        if (client == null || !client.clientToken.equals(clientToken)
                || !accessToken.equals(client.currentAccessToken)) {
            return error;
        }

        String rotated = issue(client);
        JsonObject profile = new JsonObject();
        profile.addProperty("name", "FakePlayer");
        profile.addProperty("id", "069a79f444e94726a5befca90e38aaf5");
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", rotated);
        body.addProperty("clientToken", client.clientToken);
        body.add("selectedProfile", profile);
        return body;
    }

    private String issue(Client client) {
        String accessToken = "access-token-" + tokenSeq.incrementAndGet();
        client.currentAccessToken = accessToken;
        clientsByAccessToken.put(accessToken, client);
        clientsByClientToken.put(client.clientToken, client);
        return accessToken;
    }

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

    private AuthlibInjectorAccount login() throws IOException {
        Memory memory = new MapMemory();
        memory.set("guid:as.login.injector.widgets.server_url", serverBase);
        memory.set("guid:as.login.injector.widgets.user_name", "user");
        memory.set("guid:as.login.injector.widgets.user_password", "password");
        memory.set("guid:as.login.injector.widgets.player_name", "");
        return new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE).login(memory);
    }

    /** 生产载荷的真实类型：{@code List<BaseAccount>}（BaseAccount.Adapter 按 type 派发）。 */
    private static final java.lang.reflect.Type ACCOUNT_LIST_TYPE =
            new com.google.gson.reflect.TypeToken<List<? extends BaseAccount>>() {}.getType();

    /** 用指定令牌重建一份载荷 JSON（模拟磁盘上尚未更新的旧账号）。 */
    private static String stalePayload(AuthlibInjectorAccount account, String accessToken, String clientToken) {
        JsonObject json = NetworkUtils.GSON.toJsonTree(account).getAsJsonObject();
        json.addProperty("loginToken", accessToken);
        json.addProperty("accessToken", accessToken);
        json.addProperty("clientToken", clientToken);
        JsonArray array = new JsonArray();
        array.add(json);
        return array.toString();
    }

    /** 从落盘 JSON 读回账号，走与 ConfigHandle 相同的生产读取路径。 */
    private static AuthlibInjectorAccount readBack(String persistedPayload) {
        List<? extends BaseAccount> loaded = NetworkUtils.GSON.fromJson(persistedPayload, ACCOUNT_LIST_TYPE);
        return (AuthlibInjectorAccount) loaded.get(0);
    }

    /** 复刻 AccountManager 的「重开游戏」路径：读盘 → 刷新 → 落盘。 */
    private String simulateRestart(String persistedPayload) throws Exception {
        AuthlibInjectorAccount account = readBack(persistedPayload);
        assertThat(account.getAccountStorage().getState()).isEqualTo(AccountState.UNAUTHORIZED);

        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        // setProfileState / refresh 都要求 worker 线程（与 AccountManager.refreshAccount 一致）
        try {
            TaskScheduler.submitParallel(() -> {
                account.setProfileState(AccountState.AUTHORIZING);
                provider.refresh(account);
            }).get();
        } catch (Exception e) {
            throw e;
        }

        assertThat(account.getAccountStorage().getState()).isEqualTo(AccountState.AUTHORIZED);
        // 刷新成功 → 必须把新令牌写回（AccountManager 的 save()）
        return NetworkUtils.GSON.toJson(List.of(account));
    }

    /** 拿一份「已作废令牌」的账号副本去刷新，必须被服务端拒绝（drasl 的版本号语义）。 */
    private static void assertStaleTokenIsRejected(AuthlibInjectorAccount staleAccount) {
        assertThatThrownBy(() -> TaskScheduler.submitParallel(() -> {
            try {
                staleAccount.setProfileState(AccountState.AUTHORIZING);
                new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE).refresh(staleAccount);
            } catch (Exception e) {
                // 包一层：避免 TaskScheduler 的异常分支去碰 AccountsX.LOGGER（需要 Fabric Loader 类，
                // 而单测类路径里没有 fabric-loader，会抛 NoClassDefFoundError 盖住真正的断言）。
                throw new IllegalStateException(e.getMessage(), e);
            }
        }).get())
                .rootCause()
                .hasMessageContaining("Invalid token");
    }

    /**
     * 核心回归：login → L2 重开（刷新并落盘）→ L3 重开（用 L2 落盘的令牌刷新）。
     *
     * <p>覆盖 provider 侧的令牌轮换、持久化与再次刷新；<b>不</b>覆盖 {@code AccountManager} 的
     * 落盘时机（本测试固定「刷新完成后再落盘」）。后者由 {@code AccountManager.initialize} 把
     * {@code save()} 挂在 {@code runParallel(...).whenComplete} 之后保证，属于启动编排，单测无法
     * 直接驱动（{@code Platforms} 需要 Fabric 运行时）。</p>
     */
    @Test
    void threeLaunches_loginThenTwoRestarts() throws Exception {
        // ── L1：首次添加账号 ──
        AuthlibInjectorAccount account = login();
        String accessTokenL1 = account.getLoginToken();
        String clientTokenL1 = account.getClientToken();
        assertThat(clientTokenL1).matches("client-token-\\d+");

        String persistedAfterL2 = simulateRestart(NetworkUtils.GSON.toJson(List.of(account)));

        // ── L2 结束：磁盘上的 accessToken 必须已经轮换（这正是旧实现丢掉的那一步） ──
        AuthlibInjectorAccount afterL2 = readBack(persistedAfterL2);
        assertThat(afterL2.getLoginToken())
                .as("刷新后的 accessToken 必须落盘，否则下次启动会用已作废的令牌")
                .isNotEqualTo(accessTokenL1);
        assertThat(afterL2.getClientToken()).isEqualTo(clientTokenL1);

        // 旧令牌确实已经死了（与 drasl 的版本号语义一致），证明上面的断言不是无意义的。
        assertStaleTokenIsRejected(readBack(stalePayload(account, accessTokenL1, clientTokenL1)));

        // ── L3：用 L2 落盘的令牌再刷新一次，必须成功 ──
        String persistedAfterL3 = simulateRestart(persistedAfterL2);
        AuthlibInjectorAccount afterL3 = readBack(persistedAfterL3);
        assertThat(afterL3.getLoginToken()).isNotEqualTo(afterL2.getLoginToken());
        assertThat(afterL3.getClientToken()).isEqualTo(clientTokenL1);

        // 载荷里的 state 是 transient：读回必然是 UNAUTHORIZED（靠启动刷新恢复成 AUTHORIZED）。
        // 因此「账号仍可用」的判据是——用落盘的令牌再刷新一次能成功。
        assertThat(afterL3.getAccountStorage().getState()).isEqualTo(AccountState.UNAUTHORIZED);
        String persistedAfterL4 = simulateRestart(persistedAfterL3);
        assertThat(readBack(persistedAfterL4).getLoginToken()).isNotEqualTo(afterL3.getLoginToken());
        System.out.println("[IT] 连续 4 次启动均可刷新，账号保持可用");
    }
}
