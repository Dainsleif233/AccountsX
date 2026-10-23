package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider.AuthlibInjectorAccount;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.net.JdkHttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真机集成测试（默认跳过）：用真实 {@link JdkHttpGateway} 与真实 Drasl 实例跑完整账号生命周期。
 *
 * <p>需要环境变量：{@code ACCOUNTSX_IT_SERVER}（如 {@code https://your-yggdrasil.example}）、
 * {@code ACCOUNTSX_IT_USER}、{@code ACCOUNTSX_IT_PASSWORD}。凭据只从环境读取，不落盘、不打印；
 * 未设置时两条用例经 {@code Assumptions} 跳过，因此 CI 不受影响。断言全部由传入的 server
 * 推导，不绑定任何具体实例。</p>
 *
 * <p>验证的是「关掉游戏再打开」这一原始故障场景：登录 → 持久化 → 反序列化 → 刷新。
 * 修复前该流程稳定卡在刷新一步（drasl 返回 403 {@code Invalid token}），是本 bug 的真机回归防线：</p>
 *
 * <pre>{@code
 * ACCOUNTSX_IT_SERVER=https://your-yggdrasil.example ACCOUNTSX_IT_USER=you ACCOUNTSX_IT_PASSWORD=<password> \
 *   ./gradlew --no-daemon --stacktrace :test --tests '*LiveDraslIntegrationTest'
 * }</pre>
 */
class LiveDraslIntegrationTest {

    private static final String SERVER = System.getenv("ACCOUNTSX_IT_SERVER");
    private static final String USER = System.getenv("ACCOUNTSX_IT_USER");

    private static boolean configured() {
        return SERVER != null && !SERVER.isBlank()
                && USER != null && !USER.isBlank()
                && System.getenv("ACCOUNTSX_IT_PASSWORD") != null;
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

    @Test
    void fullLifecycle_loginPersistReloadRefresh() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(configured(),
                "设置 ACCOUNTSX_IT_SERVER / USER / PASSWORD 后才运行");

        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);

        // ── 1) 首次添加账号（对应游戏内点「登录」）──
        Memory memory = new MapMemory();
        memory.set("guid:as.login.injector.widgets.server_url", SERVER);
        memory.set("guid:as.login.injector.widgets.user_name", USER);
        memory.set("guid:as.login.injector.widgets.user_password", System.getenv("ACCOUNTSX_IT_PASSWORD"));
        memory.set("guid:as.login.injector.widgets.player_name", "");

        AuthlibInjectorAccount account = provider.login(memory);

        assertThat(account.getClientToken()).as("登录后必须持有 clientToken").matches("[0-9a-f]{32}");
        assertThat(account.getLoginToken()).isNotBlank();
        // 断言由传入的 server 推导，不绑定具体实例：API 根即 <server>/authlib-injector（Drasl 惯例）。
        String apiBase = SERVER + "/authlib-injector";
        assertThat(account.getServer()).isEqualTo(apiBase);
        assertThat(account.getAccountName()).as("meta.serverName").isNotBlank();
        assertThat(account.getAccountStorage().getPlayerName()).isNotBlank();
        // 只打印长度，不打印 clientToken 的任何片段（项目规范：日志不得含凭据内容）。
        System.out.println("[IT] 登录成功 player=" + account.getAccountStorage().getPlayerName()
                + " uuid=" + account.getAccountStorage().getPlayerUUID()
                + " clientToken.len=" + account.getClientToken().length());

        // ── 2) 落盘 + 重新加载（对应关游戏 / 重开游戏）──
        String persisted = NetworkUtils.GSON.toJson(account);
        AuthlibInjectorAccount reloaded = NetworkUtils.GSON.fromJson(persisted, AuthlibInjectorAccount.class);
        assertThat(reloaded.getClientToken()).isEqualTo(account.getClientToken());

        // ── 3) 重开游戏后的启动刷新：修复前这一步必然 403 ──
        String firstAccessToken = reloaded.getLoginToken();
        TaskScheduler.submitParallel(() -> provider.refresh(reloaded)).get();

        assertThat(reloaded.getLoginToken()).as("刷新后 accessToken 应轮换").isNotEqualTo(firstAccessToken);
        assertThat(reloaded.getClientToken()).as("clientToken 应保持不变").isEqualTo(account.getClientToken());
        assertThat(reloaded.getAccountStorage().getPlayerName()).isEqualTo(account.getAccountStorage().getPlayerName());
        System.out.println("[IT] 二次刷新成功，accessToken 已轮换，clientToken 稳定");

        // ── 4) 切号时构建 authlib 上下文（解析 signaturePublickey / skinDomains）──
        AccountContext context = provider.createAccountContext(reloaded);
        assertThat(context).isNotNull();
        assertThat(context.server().sessionURL()).isEqualTo(apiBase + "/sessionserver");
        // 自身域名（来自 meta.skinDomains）放行，未知外域按策略拦截。
        assertThat(context.security().shouldBlockSkinUrl(SERVER + "/texture/x")).isFalse();
        assertThat(context.security().shouldBlockSkinUrl("https://evil.example.com/skin.png")).isTrue();
        System.out.println("[IT] AccountContext 构建成功，skinDomains 校验生效");
    }

    /** 打印归一化后的 meta，便于与 curl 结果比对（不含任何凭据）。 */
    @Test
    void metaIsReachable() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(configured(), "未配置服务器");

        JsonElement meta = JdkHttpGateway.INSTANCE.get(SERVER + "/authlib-injector").get("meta");
        System.out.println("[IT] meta = " + meta);
        assertThat(meta).isNotNull();
    }

    /**
     * 真机错误链路：拿一个无效 accessToken 去刷新，服务端必然 403，客户端必须
     * 抛出携带 i18n key 的 {@link InjectorAuthException}，且异常文本里不能出现令牌本身。
     */
    @Test
    void invalidAccessToken_yieldsActionableErrorWithoutLeakingToken() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(configured(), "未配置服务器");

        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        String leakedMarker = "TOP-SECRET-ACCESS-TOKEN-MARKER";
        AuthlibInjectorAccount broken = new AuthlibInjectorAccount(
                leakedMarker, "nobody", java.util.UUID.randomUUID(),
                SERVER + "/authlib-injector", java.util.UUID.randomUUID().toString(),
                null, null, 0L, "0123456789abcdef0123456789abcdef"
        );

        try {
            TaskScheduler.submitParallel(() -> provider.refresh(broken)).get();
            org.junit.jupiter.api.Assertions.fail("对无效令牌的刷新不应成功");
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            System.out.println("[IT] 无效令牌被拒：" + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            assertThat(cause).isInstanceOf(InjectorAuthException.class);
            assertThat(((InjectorAuthException) cause).getTranslationKey())
                    .isEqualTo("accountsx.account.fail.injector_invalid_token");
            assertThat(cause.getMessage()).doesNotContain(leakedMarker);
        }
    }
}
