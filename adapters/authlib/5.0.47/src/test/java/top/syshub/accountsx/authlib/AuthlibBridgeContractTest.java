package top.syshub.accountsx.authlib;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the authlib API shapes this adapter compiles against but cannot express in types. */
class AuthlibBridgeContractTest {
    @Test
    void environmentShape() throws Exception {
        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        assertThat(env.isRecord()).isTrue();
        assertThat(env.getRecordComponents()).hasSize(4);
    }
}
