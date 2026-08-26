package top.syshub.accountsx.core.manager.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 现状快照测试：{@link ConfigVersion} 的每一跳迁移逻辑。
 * 作为 P1–P3 重构的安全网。
 *
 * <p>注意：SECURITY_STORAGE (v3) 的 {@code upgrade()} 会调用
 * {@code ConfigHandle.writeAccounts()} 写磁盘到 {@code ~/.accountsx/}，
 * 这在单元测试中无法安全执行。相关测试在 P2.3 重写迁移链时启用。</p>
 */
class ConfigVersionTest {

    // ── v0 → v1: INJECTOR_SAFETY ──────────────────────────────────────

    /**
     * INJECTOR 类型的账号被过滤，其余保留
     */
    @Test
    void v0ToV1_injectorAccountsRemoved() {
        JsonObject config = makeConfig(0, accountsArray(
                accountObj("INJECTOR", "player1"),
                accountObj("OFFLINE", "player2"),
                accountObj("INJECTOR", "player3"),
                accountObj("MICROSOFT", "player4")
        ));

        ConfigVersion.INJECTOR_SAFETY.upgrade(config);

        JsonArray accounts = config.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("OFFLINE");
        assertThat(accounts.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("MICROSOFT");
    }

    /**
     * 空 accounts 数组无报错
     */
    @Test
    void v0ToV1_emptyArray() {
        JsonObject config = makeConfig(0, new JsonArray());
        ConfigVersion.INJECTOR_SAFETY.upgrade(config);
        assertThat(config.getAsJsonArray("accounts")).isEmpty();
    }

    /**
     * 无 INJECTOR 时数组不变
     */
    @Test
    void v0ToV1_noInjectorAccounts() {
        JsonObject config = makeConfig(0, accountsArray(
                accountObj("OFFLINE", "player1"),
                accountObj("MICROSOFT", "player2")
        ));
        ConfigVersion.INJECTOR_SAFETY.upgrade(config);
        assertThat(config.getAsJsonArray("accounts")).hasSize(2);
    }

    // ── v1 → v2: RENAME_ACCOUNT_TYPE ──────────────────────────────────

    /**
     * 类型名重命名：OFFLINE→offline, MICROSOFT→microsoft, INJECTOR→injector.authlib-injector
     */
    @Test
    void v1ToV2_typeRenamesApplied() {
        JsonObject config = makeConfig(1, accountsArray(
                accountObj("OFFLINE", "p1"),
                accountObj("MICROSOFT", "p2"),
                accountObj("INJECTOR", "p3")
        ));

        ConfigVersion.RENAME_ACCOUNT_TYPE.upgrade(config);

        JsonArray accounts = config.getAsJsonArray("accounts");
        assertThat(accounts.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("offline");
        assertThat(accounts.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("microsoft");
        assertThat(accounts.get(2).getAsJsonObject().get("type").getAsString()).isEqualTo("injector.authlib-injector");
    }

    /**
     * 未知 type 抛 IllegalStateException。
     * <p>
     * 现状 bug：P1.1 修复后改为跳过+警告，不再抛异常。
     * </p>
     */
    @Disabled("bug: 未知 type 导致整个账号集加载失败; 修复见 P1.1")
    @Test
    void v1ToV2_unknownType_throws() {
        JsonObject config = makeConfig(1, accountsArray(
                accountObj("UNKNOWN_TYPE", "p1")
        ));
        assertThatThrownBy(() -> ConfigVersion.RENAME_ACCOUNT_TYPE.upgrade(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNKNOWN_TYPE");
    }

    /**
     * 空数组无报错
     */
    @Test
    void v1ToV2_emptyArray() {
        JsonObject config = makeConfig(1, new JsonArray());
        ConfigVersion.RENAME_ACCOUNT_TYPE.upgrade(config);
        assertThat(config.getAsJsonArray("accounts")).isEmpty();
    }

    // ── v2 → v3: SECURITY_STORAGE ─────────────────────────────────────

    /**
     * accounts 数组被移除，id 被添加为合法 UUID。
     * <p>
     * 注意：此测试跳过 I/O 部分（writeAccounts 写磁盘），
     * 仅验证 JSON 转换逻辑。完整迁移链测试在 P2.3。
     * </p>
     */
    @Disabled("side-effect: writeAccounts() writes to ~/.accountsx/; fix in P2.3")
    @Test
    void v2ToV3_accountsExtracted() {
        JsonObject config = makeConfig(2, accountsArray(
                accountObj("offline", "p1"),
                accountObj("microsoft", "p2")
        ));

        ConfigVersion.SECURITY_STORAGE.upgrade(config);

        // accounts 被移除
        assertThat(config.has("accounts")).isFalse();
        // id 被添加且是合法 UUID
        String id = config.get("id").getAsString();
        assertThat(UUID.fromString(id)).isNotNull();
    }

    // ── 完整迁移链 ────────────────────────────────────────────────────

    /**
     * 从 v0 开始走完整迁移链（不含 SECURITY_STORAGE 的 I/O 部分）。
     * 验证 v0→v1→v2 的转换链。
     */
    @Disabled("完整链需要 SECURITY_STORAGE; 修复见 P2.3")
    @Test
    void fullChain_v0ToV2() {
        JsonObject config = makeConfig(0, accountsArray(
                accountObj("INJECTOR", "p1"),
                accountObj("OFFLINE", "p2"),
                accountObj("MICROSOFT", "p3")
        ));

        // 模拟 ConfigHandle.load() 中的迁移逻辑
        for (ConfigVersion cv : ConfigVersion.VALUES) {
            if (cv == ConfigVersion.BASE) continue; // BASE.upgrade() 抛异常
            if (config.get("version") instanceof JsonPrimitive jp && jp.isNumber()) {
                if (jp.getAsNumber().intValue() < cv.getVersion()) {
                    cv.upgrade(config);
                }
            }
        }

        // 验证 v2 后的状态
        JsonArray accounts = config.getAsJsonArray("accounts");
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0).getAsJsonObject().get("type").getAsString()).isEqualTo("offline");
        assertThat(accounts.get(1).getAsJsonObject().get("type").getAsString()).isEqualTo("microsoft");
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    private static JsonObject makeConfig(int version, JsonArray accounts) {
        JsonObject config = new JsonObject();
        config.addProperty("version", version);
        config.add("accounts", accounts);
        return config;
    }

    private static JsonArray accountsArray(JsonObject... objs) {
        JsonArray arr = new JsonArray();
        for (JsonObject obj : objs) {
            arr.add(obj);
        }
        return arr;
    }

    private static JsonObject accountObj(String type, String playerName) {
        JsonObject obj = new JsonObject();
        obj.add("type", new JsonPrimitive(type));
        obj.add("playerName", new JsonPrimitive(playerName));
        return obj;
    }
}
