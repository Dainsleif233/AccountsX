package top.syshub.accountsx.core.image;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.image.AvatarCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.4 安全网：验证头像落盘缓存（决策 D4）的内容哈希命名与读写往返。
 *
 * <p>用 {@code accountsx.cache.dir} 系统属性把缓存根指到临时目录，避免触碰真实
 * {@code ~/.cache/accountsx/avatars}。{@link AvatarCache} 本身不引用 AWT / ImageIO，
 * 故可在任意 headless 测试 JVM 中运行。</p>
 */
class AvatarCacheTest {

    private Path tmpRoot;

    private void useTmpRoot() throws IOException {
        tmpRoot = Files.createTempDirectory("accountsx-avatar-cache-test");
        System.setProperty(AvatarCache.DEFAULT_ROOT_PROPERTY, tmpRoot.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(AvatarCache.DEFAULT_ROOT_PROPERTY);
    }

    @Test
    void hash_isDeterministicLowercaseHex() {
        byte[] data = "hello-avatar".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String h1 = AvatarCache.hash(data);
        String h2 = AvatarCache.hash(data);

        assertThat(h1).isEqualTo(h2);
        // SHA-256 → 64 hex chars
        assertThat(h1).hasSize(64);
        assertThat(h1).matches("[0-9a-f]{64}");
    }

    @Test
    void hash_differsForDifferentContent() {
        byte[] a = "one".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = "two".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(AvatarCache.hash(a)).isNotEqualTo(AvatarCache.hash(b));
    }

    @Test
    void storeAndLoad_roundtrip() throws IOException {
        useTmpRoot();
        byte[] png = new byte[]{1, 2, 3, 4, 5};

        String key = AvatarCache.store(png);

        assertThat(key).hasSize(64);
        assertThat(AvatarCache.exists(key)).isTrue();
        assertThat(AvatarCache.pathFor(key)).isEqualTo(tmpRoot.resolve(key + ".png"));

        byte[] loaded = AvatarCache.load(key);
        assertThat(loaded).containsExactly(png);
    }

    @Test
    void load_returnsNullForMissingKey() throws IOException {
        useTmpRoot();
        assertThat(AvatarCache.load("nonexistent-key-0123456789abcdef")).isNull();
        assertThat(AvatarCache.exists("nonexistent-key-0123456789abcdef")).isFalse();
    }

    @Test
    void storeKeyEqualsContentHash() throws IOException {
        useTmpRoot();
        byte[] png = new byte[]{9, 8, 7, 6};
        String key = AvatarCache.store(png);
        assertThat(key).isEqualTo(AvatarCache.hash(png));
    }
}
