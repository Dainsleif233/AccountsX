package top.syshub.accountsx.common.auth.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthlibInjectorUriTest {

    @Test
    void decodesEncodedApiRoot() {
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:yggdrasil-server:https%3A%2F%2Fexample.com%2Fapi"))
                .isEqualTo("https://example.com/api");
    }

    @Test
    void decodesPortAndQuery() {
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:yggdrasil-server:https%3A%2F%2Fauth.example.org%3A8443%2Fyggdrasil"))
                .isEqualTo("https://auth.example.org:8443/yggdrasil");
    }

    @Test
    void acceptsPlainHttpUrl() {
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:yggdrasil-server:http://127.0.0.1:25599"))
                .isEqualTo("http://127.0.0.1:25599");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(AuthlibInjectorUri.parseApiRoot("  authlib-injector:yggdrasil-server:https%3A%2F%2Fx.com  "))
                .isEqualTo("https://x.com");
    }

    @Test
    void rejectsUnrelatedText() {
        assertThat(AuthlibInjectorUri.parseApiRoot("hello world")).isNull();
        assertThat(AuthlibInjectorUri.parseApiRoot("https://example.com")).isNull();
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:other:https%3A%2F%2Fx.com")).isNull();
    }

    @Test
    void rejectsNullAndEmptyPayload() {
        assertThat(AuthlibInjectorUri.parseApiRoot(null)).isNull();
        assertThat(AuthlibInjectorUri.parseApiRoot("")).isNull();
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:yggdrasil-server:")).isNull();
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:yggdrasil-server:ftp%3A%2F%2Fx.com")).isNull();
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:yggdrasil-server:javascript%3Aalert(1)")).isNull();
    }

    @Test
    void rejectsMalformedEscape() {
        assertThat(AuthlibInjectorUri.parseApiRoot("authlib-injector:yggdrasil-server:%ZZ")).isNull();
    }

    @Test
    void prefixIsCaseSensitive() {
        assertThat(AuthlibInjectorUri.parseApiRoot("Authlib-Injector:yggdrasil-server:https%3A%2F%2Fx.com")).isNull();
    }
}
