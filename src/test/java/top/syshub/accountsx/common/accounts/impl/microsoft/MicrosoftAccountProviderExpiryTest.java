package top.syshub.accountsx.common.accounts.impl.microsoft;

import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.net.JdkHttpGateway;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微软令牌按 {@code expires_in} 复用：到期前跳过整条 Xbox → Minecraft 换票。
 *
 * <p>这里只验证判定本身，真正的换票链路需要网络，不做单测。</p>
 */
class MicrosoftAccountProviderExpiryTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final MicrosoftAccountProvider provider = new MicrosoftAccountProvider(JdkHttpGateway.INSTANCE);

    private static MicrosoftAccount account(long expiresAt) {
        return new MicrosoftAccount("fake-access", "Alex", PLAYER, "fake-ms-access", "fake-ms-refresh",
                null, 0L, expiresAt);
    }

    @Test
    void canReuseToken_beforeExpiry_returnsTrue() {
        assertThat(this.provider.canReuseToken(account(System.currentTimeMillis() + 600_000L))).isEqualTo(TokenReuse.YES);
    }

    @Test
    void canReuseToken_afterExpiry_returnsNo() {
        assertThat(this.provider.canReuseToken(account(System.currentTimeMillis() - 1_000L))).isEqualTo(TokenReuse.NO);
    }

    @Test
    void canReuseToken_unknownExpiry_returnsNo() {
        // 0 = 未知（老配置、解析不出 expires_in）：宁可刷新一次，也不要把死令牌带进游戏。
        assertThat(this.provider.canReuseToken(account(0L))).isEqualTo(TokenReuse.NO);
    }
}

