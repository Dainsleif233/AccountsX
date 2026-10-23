package top.syshub.accountsx.common.manager.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 载荷解析的健壮性回归（AGENTS.md 不变量：载荷读失败时**不得**写回配置）。
 *
 * <p>触发场景：单个账号带未知 {@code type} 时 {@code AccountTypeAdapter.read} 抛 IOException，
 * 整个列表反序列化失败。若此时把结果当成「没有账号」并写回，{@code ~/.accountsx/<id>.json}
 * 会被覆写成 {@code []}，用户一次瞬时错误就丢掉全部账号。本测试锁住「解析失败必须抛」的行为，
 * 使调用方（{@code ConfigHandle.load}）能据此进入只读降级。</p>
 */
class ConfigHandlePayloadTest {

    private static final java.lang.reflect.Type ACCOUNT_LIST_TYPE =
            new com.google.gson.reflect.TypeToken<List<? extends BaseAccount>>() {}.getType();

    private static String payload(JsonObject... accounts) {
        JsonArray array = new JsonArray();
        for (JsonObject account : accounts) {
            array.add(account);
        }
        return array.toString();
    }

    /** 用真实账号对象生成载荷条目，保证结构与生产写入完全一致（含嵌套 storage）。 */
    private static JsonObject offlineAccount(String uuid) {
        OfflineAccount account = new OfflineAccount(
                "fake-token", "Tester", java.util.UUID.fromString(uuid));
        JsonObject json = NetworkUtils.GSON.toJsonTree(account).getAsJsonObject();
        json.addProperty("type", "offline");
        return json;
    }

    @Test
    void validPayload_parses() {
        List<? extends BaseAccount> accounts = NetworkUtils.GSON.fromJson(
                new StringReader(payload(offlineAccount("069a79f4-44e9-4726-a5be-fca90e38aaf5"))),
                ACCOUNT_LIST_TYPE);

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).getAccountStorage().getPlayerName()).isEqualTo("Tester");
    }

    /** 未知 type：必须抛（而不是静默丢弃该账号或返回空列表），否则调用方无从判断要不要降级。 */
    @Test
    void unknownAccountType_throwsInsteadOfBeingDropped() {
        assertThatThrownBy(() -> NetworkUtils.GSON.fromJson(
                new StringReader(payload(offlineAccount("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                        offlineAccountWithType("from-a-newer-version"))),
                ACCOUNT_LIST_TYPE))
                .hasMessageContaining("Unknown account type");
    }

    private static JsonObject offlineAccountWithType(String type) {
        JsonObject account = offlineAccount("11111111-2222-3333-4444-555555555555");
        account.addProperty("type", type);
        return account;
    }

    /** 一个账号坏掉不能连累其它账号的解析结果——这是当前的既定行为（整表失败），锁住以免无意改变。 */
    @Test
    void oneUnknownTypeFailsTheWholeList() {
        assertThatThrownBy(() -> NetworkUtils.GSON.fromJson(
                new StringReader(payload(offlineAccount("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                        offlineAccountWithType("nope"))),
                ACCOUNT_LIST_TYPE))
                .isNotNull();
    }

    /**
     * 载荷内容是 JSON {@code null}（或纯空白）时 Gson 返回 null。这不是「没有账号」，而是文件损坏，
     * 调用方必须据此进入只读降级，否则会把损坏文件覆写成空列表。
     */
    @Test
    void nullPayload_parsesAsNull() {
        List<? extends BaseAccount> fromNull = NetworkUtils.GSON.fromJson(new StringReader("null"), ACCOUNT_LIST_TYPE);
        List<? extends BaseAccount> fromBlank = NetworkUtils.GSON.fromJson(new StringReader("   "), ACCOUNT_LIST_TYPE);
        assertThat(fromNull).isNull();
        assertThat(fromBlank).isNull();
    }

    /** 顶层不是数组（如文件被写成对象）必须抛，不能当成空账号集。 */
    @Test
    void nonArrayPayload_throws() {
        assertThatThrownBy(() -> NetworkUtils.GSON.fromJson(new StringReader("{}"), ACCOUNT_LIST_TYPE))
                .isInstanceOf(RuntimeException.class);
    }

    /** 数组里出现空对象（缺 type）必须抛，而不是产出 storage 为空的坏账号。 */
    @Test
    void emptyObjectEntry_throws() {
        assertThatThrownBy(() -> NetworkUtils.GSON.fromJson(new StringReader("[{}]"), ACCOUNT_LIST_TYPE))
                .isInstanceOf(RuntimeException.class);
    }

    /** 尾部残留内容（如 {@code []garbage}）必须抛，避免把损坏文件误判为空账号集。 */
    @Test
    void trailingGarbage_throws() {
        assertThatThrownBy(() -> NetworkUtils.GSON.fromJson(new StringReader("[]garbage"), ACCOUNT_LIST_TYPE))
                .isInstanceOf(RuntimeException.class);
    }

    /** 截断的 JSON 必须抛，让 load() 走只读降级而不是清空账号。 */
    @Test
    void truncatedJson_throws() {
        assertThatThrownBy(() -> NetworkUtils.GSON.fromJson(new StringReader("[{\"type\":\"offline\""), ACCOUNT_LIST_TYPE))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * 只读降级的守卫必须出现在触碰任何文件/目录之前。
     *
     * <p>利用「测试环境没有可用的 Fabric 运行时」这一事实：未降级时 {@code write()} 会走到
     * {@code FabricLoader.getInstance().getConfigDir()} 并失败 —— 具体抛什么是环境相关的
     * （Gradle 测试运行时没有 fabric-loader 类 → {@code NoClassDefFoundError}；
     * 手写 javac 跑法把它放进 classpath → {@code NullPointerException}），
     * 因此这里只断言「抛了」而不绑定异常类型。关键是：<b>抛了</b>就证明守卫之后确实会去动文件系统，
     * 而<b>降级后不抛</b>就证明它在拿到路径之前就返回了，物理上不可能打开/截断/覆盖用户载荷。</p>
     */
    @Test
    void write_isSkippedBeforeTouchingAnyFileWhenDegraded() {
        if (!ConfigHandle.isReadOnly()) {
            // 未降级：必然会尝试访问文件系统（并因缺少 Fabric 运行时而失败）。
            assertThatThrownBy(ConfigHandle::write)
                    .as("未降级时 write() 应当去访问文件系统，而不是静默返回")
                    .isInstanceOf(Throwable.class);
        }

        ConfigHandle.enterReadOnlyMode("unit test");
        assertThat(ConfigHandle.isReadOnly()).isTrue();

        // 降级后不得触碰文件系统：必须静默返回，否则说明会在真实环境里覆写用户数据。
        assertThatCode(ConfigHandle::write).doesNotThrowAnyException();
    }
}
