package top.syshub.accountsx.image;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * On-disk avatar cache (decision D4 / P1.4).
 *
 * <p>Avatar PNGs are written to {@code ~/.cache/accountsx/avatars/<hash>.png}
 * and the account config only stores the content hash plus a timestamp. The
 * PNG is rebuildable from the skin, so a missing or stale cache entry is never
 * fatal.</p>
 *
 * <p>The cache root can be overridden via the {@code accountsx.cache.dir}
 * system property — used by unit tests to avoid touching the real home
 * directory. This class intentionally references no AWT / ImageIO types so it
 * can be exercised in a headless test JVM.</p>
 */
public final class AvatarCache {

    /** Default root: {@code ~/.cache/accountsx/avatars}. */
    public static final String DEFAULT_ROOT_PROPERTY = "accountsx.cache.dir";

    private AvatarCache() {
    }

    /** Resolves the cache root directory, honoring the override property. */
    public static Path root() {
        String override = System.getProperty(DEFAULT_ROOT_PROPERTY);
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".cache", "accountsx", "avatars");
    }

    /** Path of the cached PNG for a given key. */
    public static Path pathFor(String key) {
        return root().resolve(key + ".png");
    }

    /** Whether a cached PNG exists for the key. */
    public static boolean exists(String key) {
        return Files.isRegularFile(pathFor(key));
    }

    /**
     * Loads the cached PNG bytes for the key, or {@code null} if absent / unreadable.
     * A read failure is swallowed (the avatar is rebuildable) and returns {@code null}.
     */
    public static byte[] load(String key) {
        try {
            Path p = pathFor(key);
            if (!Files.isRegularFile(p)) {
                return null;
            }
            return Files.readAllBytes(p);
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Stores a PNG under its content hash key, creating the cache directory if
     * needed. The key returned by {@link #hash(byte[])} is what callers persist.
     */
    public static String store(byte[] png) throws IOException {
        String key = hash(png);
        Path dir = root();
        Files.createDirectories(dir);
        Files.write(pathFor(key), png);
        return key;
    }

    /**
     * SHA-256 content hash as lowercase hex. Used as the avatar key / filename
     * so identical avatars naturally deduplicate on disk.
     */
    public static String hash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JDK; not recoverable if absent.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Bundled default avatar (the classic "Alex" silhouette) as raw PNG bytes,
     * loaded once from the module resource
     * {@code /assets/accountsx/textures/gui/alex_avatar.png} and cached so the 12
     * MC adapters no longer each hardcode the base64 string. Never {@code null};
     * an empty array is returned only if the resource is unexpectedly absent
     * (in which case the adapter's {@code loadAvatar} treats it as "no texture",
     * matching the existing null/empty guard).
     */
    private static final byte[] DEFAULT_AVATAR;
    static {
        byte[] tmp;
        try (InputStream in = AvatarCache.class.getResourceAsStream("/assets/accountsx/textures/gui/alex_avatar.png")) {
            tmp = (in == null) ? new byte[0] : in.readAllBytes();
        } catch (IOException ignored) {
            tmp = new byte[0];
        }
        DEFAULT_AVATAR = tmp;
    }

    /** Bundled default avatar PNG (Alex). Never {@code null}; empty if the resource is missing. */
    public static byte[] loadDefaultAvatar() {
        return DEFAULT_AVATAR;
    }
}
