package top.syshub.accountsx.authlib;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the authlib API shapes this adapter compiles against but cannot express in types. */
class AuthlibBridgeContractTest {
    @Test
    void environmentContract() throws Exception {
        // v4 Environment is an interface with a 5-arg static create, not a record.
        Class<?> env = Class.forName("com.mojang.authlib.Environment");
        assertThat(env.isInterface()).isTrue();

        Method create = env.getMethod("create", String.class, String.class, String.class, String.class, String.class);
        assertThat(create.getModifiers() & Modifier.STATIC).isNotZero();
    }
}
