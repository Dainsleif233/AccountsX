package top.syshub.accountsx.core.accounts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 现状快照测试：{@link AccountUUID} 的解析与序列化行为。
 * 作为 P1–P3 重构的安全网。
 */
class AccountUUIDTest {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new AccountUUID.UUIDTypeAdapter())
            .create();

    /**
     * 标准 UUID 格式（36 字符，含 4 个 '-'）
     */
    @Test
    void parse_validDashed36() {
        UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID result = AccountUUID.parse("550e8400-e29b-41d4-a716-446655440000");
        assertThat(result).isEqualTo(expected);
    }

    /**
     * 无横线格式（32 字符）
     */
    @Test
    void parse_validUndashed32() {
        UUID expected = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID result = AccountUUID.parse("550e8400e29b41d4a716446655440000");
        assertThat(result).isEqualTo(expected);
    }

    /**
     * 大小写混合 hex 均能解析
     */
    @Test
    void parse_caseInsensitive() {
        UUID lower = AccountUUID.parse("550e8400-e29b-41d4-a716-446655440000");
        UUID upper = AccountUUID.parse("550E8400-E29B-41D4-A716-446655440000");
        UUID mixed = AccountUUID.parse("550e8400-E29b-41D4-a716-446655440000");
        assertThat(lower).isEqualTo(upper).isEqualTo(mixed);
    }

    /**
     * 长度非法抛 IllegalArgumentException
     */
    @Test
    void parse_illegalLength_throws() {
        assertThatThrownBy(() -> AccountUUID.parse("550e8400"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountUUID.parse("550e8400-e29b-41d4-a716-446655440000-extra"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 36 字符但 '-' 不在 8/13/18/23 位置（dash 位置错误），走 36 分支的 else 抛异常。
     * 输入是合法 UUID 把第一个 '-' 从位置 8 移到 9，长度仍为 36。
     */
    @Test
    void parse_illegalDashes_throws() {
        assertThatThrownBy(() -> AccountUUID.parse("550e8400e-29b-41d4-a716-446655440000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 非 hex 字符（如 'G'、'z'）在 hex 位时抛异常
     */
    @Test
    void parse_invalidHexChars_throws() {
        assertThatThrownBy(() -> AccountUUID.parse("550e8400-e29b-41d4-a716-44665544000g"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountUUID.parse("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 同一 playerName 返回相同 UUID（确定性）
     */
    @Test
    void ofPlayerName_deterministic() {
        UUID first = AccountUUID.ofPlayerName("TestPlayer");
        UUID second = AccountUUID.ofPlayerName("TestPlayer");
        assertThat(first).isEqualTo(second);
    }

    /**
     * 用已知 OfflinePlayer:Steve 向量校验
     * 预计算：UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(UTF_8))
     */
    @Test
    void ofPlayerName_knownValue() {
        UUID expected = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID result = AccountUUID.ofPlayerName("Steve");
        assertThat(result).isEqualTo(expected);
    }

    /**
     * 无横线输出
     */
    @Test
    void toMinecraftStyleString_noDashes() {
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String result = AccountUUID.toMinecraftStyleString(uuid);
        assertThat(result).isEqualTo("550e8400e29b41d4a716446655440000");
        assertThat(result).doesNotContain("-");
    }

    /**
     * Gson 序列化+反序列化往返一致
     */
    @Test
    void uuidTypeAdapter_roundTrip() {
        UUID original = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        String json = GSON.toJson(original, UUID.class);
        // 序列化为无横线的 Minecraft 风格字符串
        assertThat(json).isEqualTo("\"550e8400e29b41d4a716446655440000\"");
        UUID restored = GSON.fromJson(json, UUID.class);
        assertThat(restored).isEqualTo(original);
    }
}
