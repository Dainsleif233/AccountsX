package top.syshub.accountsx.common.accounts.model.context;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 现状快照测试：{@link SkinURLVerifier} 的域名白名单/黑名单逻辑。
 * 作为 P1–P3 重构的安全网。
 */
class SkinURLVerifierTest {

    private static final SkinURLVerifier MOJANG = SkinURLVerifier.MOJANG_DEFAULT;

    @Test
    void mojangDefault_minecraftNetAccepted() {
        assertThat(MOJANG.isSkinURLSecure(URI.create("https://textures.minecraft.net/texture/abc123")))
                .isTrue();
    }

    @Test
    void mojangDefault_mojangComAccepted() {
        assertThat(MOJANG.isSkinURLSecure(URI.create("https://skins.mojang.com/skin/abc123")))
                .isTrue();
    }

    @Test
    void mojangDefault_bugsMojangRejected() {
        assertThat(MOJANG.isSkinURLSecure(URI.create("https://bugs.mojang.com/issue/123")))
                .isFalse();
    }

    @Test
    void mojangDefault_educationRejected() {
        assertThat(MOJANG.isSkinURLSecure(URI.create("https://education.minecraft.net/skin")))
                .isFalse();
    }

    @Test
    void mojangDefault_feedbackRejected() {
        assertThat(MOJANG.isSkinURLSecure(URI.create("https://feedback.minecraft.net/vote/123")))
                .isFalse();
    }

    @Test
    void mojangDefault_httpSchemeAccepted() {
        // http 也在 ALLOWED_SCHEMES 中
        assertThat(MOJANG.isSkinURLSecure(URI.create("http://textures.minecraft.net/texture/abc")))
                .isTrue();
    }

    @Test
    void mojangDefault_noSchemeRejected() {
        // URI 没有 scheme 时 getScheme() 返回 null
        assertThat(MOJANG.isSkinURLSecure(URI.create("textures.minecraft.net/texture/abc")))
                .isFalse();
    }

    @Test
    void mojangDefault_externalDomainRejected() {
        assertThat(MOJANG.isSkinURLSecure(URI.create("https://example.com/skin.png")))
                .isFalse();
    }

    @Test
    void mojangDefault_punycodeRejected() {
        // 含非 ASCII 字符的域名（IDN 解码后大写）被拒
        // IDN.toUnicode("xn--...") 可能返回混合大小写
        assertThat(MOJANG.isSkinURLSecure(URI.create("https://Xn--Eiam.example.com/skin")))
                .isFalse();
    }

    @Test
    void mojangDefault_nullHostRejected() {
        // scheme-only URI，host 为 null
        assertThat(MOJANG.isSkinURLSecure(URI.create("https:///path")))
                .isFalse();
    }

    @Test
    void ofDomainVerifier_customDomains() {
        SkinURLVerifier custom = SkinURLVerifier.ofDomainVerifier(
                List.of(".custom.example"),
                List.of("blocked.custom.example")
        );
        assertThat(custom.isSkinURLSecure(URI.create("https://skin.custom.example/img.png")))
                .isTrue();
        assertThat(custom.isSkinURLSecure(URI.create("https://blocked.custom.example/img.png")))
                .isFalse();
        assertThat(custom.isSkinURLSecure(URI.create("https://other.example/img.png")))
                .isFalse();
    }

    @Test
    void ofOperationOR_combinesTwoVerifiers() {
        SkinURLVerifier v1 = SkinURLVerifier.ofDomainVerifier(List.of(".a.com"), List.of());
        SkinURLVerifier v2 = SkinURLVerifier.ofDomainVerifier(List.of(".b.com"), List.of());
        SkinURLVerifier combined = SkinURLVerifier.ofOperationOR(v1, v2);

        assertThat(combined.isSkinURLSecure(URI.create("https://x.a.com/skin")))
                .isTrue();
        assertThat(combined.isSkinURLSecure(URI.create("https://x.b.com/skin")))
                .isTrue();
        assertThat(combined.isSkinURLSecure(URI.create("https://x.c.com/skin")))
                .isFalse();
    }
}
