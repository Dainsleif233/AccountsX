package top.syshub.accountsx.authlib;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the authlib API shapes this adapter compiles against but cannot express in types. */
class AuthlibBridgeContractTest {
    @Test
    void environmentShape() throws Exception {
        // 4 record components: sessionHost, servicesHost, profilesHost, name
        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        assertThat(env.isRecord()).isTrue();
        assertThat(env.getRecordComponents()).hasSize(4);
        assertThat(env.getRecordComponents()[0].getName()).isEqualTo("sessionHost");
        assertThat(env.getRecordComponents()[3].getName()).isEqualTo("name");
    }

    @Test
    void signatureStateReflectionTarget() throws Exception {
        // AuthlibAdapterImpl resolves this private method lazily via MethodHandles,
        // so compilation does not guard it — the lookup must match this exact shape.
        Class<?> session = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService");
        Method method = session.getDeclaredMethod("getPropertySignatureState",
                Class.forName("com.mojang.authlib.properties.Property"));
        assertThat(method.getReturnType().getName()).isEqualTo("com.mojang.authlib.SignatureState");
    }
}
