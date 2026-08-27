package top.syshub.accountsx.core.accounts.model.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对 {@link AuthSecurityContext#shouldBlockSkinUrl(String)} 的测试（1.12 重命名修复）。
 * 作为 P1–P3 重构的安全网：方法名与语义一致——返回 true 表示应拦截。
 */
class AuthSecurityContextTest {

    // Mojang 默认白名单皮肤上下文（无公钥、MOJANG_DEFAULT 校验器）
    private static final AuthSecurityContext MOJANG =
            new AuthSecurityContext(List.of(), List.of());

    @Test
    void mojangDefault_minecraftNetNotBlocked() {
        assertThat(MOJANG.shouldBlockSkinUrl("https://textures.minecraft.net/texture/abc123"))
                .isFalse();
    }

    @Test
    void mojangDefault_mojangComNotBlocked() {
        assertThat(MOJANG.shouldBlockSkinUrl("https://skins.mojang.com/skin/abc123"))
                .isFalse();
    }

    @Test
    void mojangDefault_externalDomainBlocked() {
        assertThat(MOJANG.shouldBlockSkinUrl("https://evil.example.com/skin.png"))
                .isTrue();
    }

    @Test
    void malformedUrlTreatedAsBlocked() {
        // 1.12 修复：URI 解析失败时视为拦截（true），且不抛异常
        assertThat(MOJANG.shouldBlockSkinUrl("this is not a url ://"))
                .isTrue();
        assertThat(MOJANG.shouldBlockSkinUrl(""))
                .isTrue();
    }

    @Test
    void customWhitelist_blocksOutsideDomain() {
        AuthSecurityContext ctx = new AuthSecurityContext(
                List.of(),
                List.of(),
                SkinURLVerifier.ofDomainVerifier(List.of(".custom.example"), List.of())
        );

        assertThat(ctx.shouldBlockSkinUrl("https://skin.custom.example/img.png")).isFalse();
        assertThat(ctx.shouldBlockSkinUrl("https://other.example/img.png")).isTrue();
    }
}
