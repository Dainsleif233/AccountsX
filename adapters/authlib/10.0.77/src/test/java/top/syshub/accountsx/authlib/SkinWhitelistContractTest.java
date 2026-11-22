package top.syshub.accountsx.authlib;

import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.common.accounts.model.context.SkinURLVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行为契约：服务器根文档声明的 skinDomains 之外的贴图必须被丢弃。
 *
 * <p>只断言外部可观察的结果（贴图有没有被丢），不依赖 authlib 内部实现细节。</p>
 */
class SkinWhitelistContractTest {
    private static final UUID PROFILE_ID = new UUID(0L, 0L);
    private static final String HOST = "https://example.invalid";
    private static final AuthSecurityContext SECURITY = new AuthSecurityContext(
            List.of(), List.of(), SkinURLVerifier.ofDomainVerifier(List.of(".example.invalid"), List.of())
    );

    private static AuthlibAdapterImpl.PolicySessionService session() {
        return new AuthlibAdapterImpl.PolicySessionService(
                com.mojang.authlib.services.ServicesKeySet.EMPTY,
                java.net.Proxy.NO_PROXY,
                com.mojang.authlib.services.MinecraftServicesDiscoveryService.createOffline(java.net.Proxy.NO_PROXY),
                SECURITY,
                // 单测没有 Fabric 运行时，取不到 UnsafeVM 的 IMPL_LOOKUP，签名状态直接注入。
                property -> com.mojang.authlib.SignatureState.INVALID
        );
    }

    @Test
    void textureInsideDeclaredDomainsIsKept() {
        assertThat(session().unpackTextures(textures("https://skin.example.invalid/skin.png")).skin()).isNotNull();
    }

    @Test
    void textureOutsideDeclaredDomainsIsDropped() {
        assertThat(session().unpackTextures(textures("https://evil.example.org/skin.png")).skin()).isNull();
    }

    private static Property textures(String url) {
        String json = "{\"timestamp\":0,\"profileId\":\"" + PROFILE_ID
                + "\",\"profileName\":\"Tester\",\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return new Property("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
    }
}
