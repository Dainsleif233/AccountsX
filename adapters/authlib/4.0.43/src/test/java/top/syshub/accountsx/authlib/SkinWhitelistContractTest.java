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
                new com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService(java.net.Proxy.NO_PROXY, "fake-client-token"),
                com.mojang.authlib.Environment.create(HOST, HOST, HOST, HOST, "Test"),
                SECURITY
        );
    }

    @Test
    void textureInsideDeclaredDomainsIsKept() throws Exception {
        var textures = session().getTextures(profile("https://skin.example.invalid/skin.png"), false);
        assertThat(textures).containsKey(com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN);
    }

    @Test
    void textureOutsideDeclaredDomainsIsDropped() throws Exception {
        var textures = session().getTextures(profile("https://evil.example.org/skin.png"), false);
        assertThat(textures).isEmpty();
    }

    private static com.mojang.authlib.GameProfile profile(String url) {
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(PROFILE_ID, "Tester");
        profile.getProperties().put("textures", textures(url));
        return profile;
    }

    private static Property textures(String url) {
        String json = "{\"timestamp\":0,\"profileId\":\"" + PROFILE_ID
                + "\",\"profileName\":\"Tester\",\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return new Property("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
    }
}
