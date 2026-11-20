package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.utils.image.AvatarCache;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.List;

/** 将 v2 的内联账号或 v3 的独立载荷转换为 v4。全局配置的提交和旧文件清理由存储层负责。 */
final class UnifyAndDecouple implements Migration {
    private static final Logger LOGGER = LoggerFactory.getLogger(UnifyAndDecouple.class);
    private static final String OAUTH_PREFIX = "OAuth ";
    private static final String HEX = "0123456789abcdef";

    private final int version;

    UnifyAndDecouple(int version) {
        this.version = version;
    }

    @Override
    public int fromVersion() {
        return version;
    }

    @Override
    public int toVersion() {
        return 4;
    }

    /** 转换结果：v4 账号 + 它反推出的 server 实体信息（非 injector 类型为 null）。 */
    private record Converted(JsonObject account,
                             String serverType,
                             String apiRoot,
                             String displayName,
                             JsonObject oauthSeed) {
    }

    @Override
    public Result apply(JsonObject config, MigrationContext ctx) throws IOException {
        Path payload = null;
        JsonArray legacy;
        if (version == 3) {
            Path candidate = payloadPath(config, ctx.legacyHomeDir());
            JsonArray accounts = readPayload(candidate);
            legacy = accounts == null ? new JsonArray() : accounts;
            if (accounts != null)
                payload = candidate;
        } else {
            JsonElement accounts = config.get("accounts");
            if (accounts == null || accounts.isJsonNull())
                legacy = new JsonArray();
            else if (accounts instanceof JsonArray array)
                legacy = array;
            else
                throw new IllegalStateException("旧配置的 accounts 不是 JSON 数组，拒绝迁移以保护数据。");
        }

        JsonObject out = new JsonObject();
        JsonArray outAccounts = new JsonArray();
        JsonArray outServers = new JsonArray();
        for (JsonElement element : legacy) {
            if (!(element instanceof JsonObject account)) {
                LOGGER.warn("旧载荷含非对象条目，已忽略。");
                continue;
            }
            Converted converted = toV4(account, ctx);
            if (converted == null)
                continue;
            if (converted.serverType() != null)
                addServer(outServers, converted);
            outAccounts.add(converted.account());
        }
        LOGGER.info("已将 {} 个旧账号转换为 v4。", outAccounts.size());

        // mojang / offline 是 builtin kind，不写入 servers 列表。
        out.add("accounts", outAccounts);
        out.add("servers", outServers);
        return new Result(out, payload);
    }

    private static Path payloadPath(JsonObject config, Path home) {
        String id = str(config.get("id"));
        if (id == null || id.isBlank())
            throw new IllegalStateException("v3 配置缺少 id，无法定位账号载荷；拒绝迁移以保护数据。");
        if (!id.contains("/") && !id.contains("\\")) {
            try {
                Path name = home.getFileSystem().getPath(id + ".json");
                if (name.getRoot() == null && name.getNameCount() == 1)
                    return home.resolve(name);
            } catch (InvalidPathException ignored) {
                // 原生异常保留输入内容，不应带到日志。
            }
        }
        throw new IllegalStateException("v3 配置的 id 不是有效的载荷文件名，拒绝迁移以保护数据。");
    }

    private static JsonArray readPayload(Path payload) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(payload, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            // 旧版只有在存在内联账号数组时才会创建载荷。
            return null;
        }
        if (attrs.isSymbolicLink())
            attrs = Files.readAttributes(payload, BasicFileAttributes.class);
        if (!attrs.isRegularFile())
            throw new IOException("旧载荷不是普通文件，拒绝迁移以保护数据: " + payload);
        try (Reader reader = Files.newBufferedReader(payload, StandardCharsets.UTF_8)) {
            if (JsonParser.parseReader(reader) instanceof JsonArray accounts)
                return accounts;
            throw new IllegalStateException("旧载荷不是 JSON 数组，拒绝迁移以保护数据: " + payload);
        } catch (JsonParseException e) {
            throw new IllegalStateException("旧载荷解析失败: " + payload);
        }
    }

    // ── 单账号转换 ────────────────────────────────────────────────

    private Converted toV4(JsonObject legacy, MigrationContext ctx) {
        String legacyType = str(legacy.get("type"));
        String type = switch (legacyType == null ? "" : legacyType) {
            case "offline" -> "offline";
            case "microsoft" -> "microsoft";
            case "injector.authlib-injector" -> "yggdrasil";
            default -> null;
        };
        if (type == null) {
            LOGGER.warn("旧载荷含未知账号类型，已跳过该账号。");
            return null;
        }

        JsonObject account = new JsonObject();
        account.addProperty("type", type);

        JsonObject storage = legacy.get("storage") instanceof JsonObject s ? s : new JsonObject();
        String name = str(storage.get("playerName"));
        String uuid = normalizeUuid(str(storage.get("playerUUID")));
        if (name != null || uuid != null) {
            JsonObject profile = new JsonObject();
            if (name != null)
                profile.addProperty("name", name);
            if (uuid != null)
                profile.addProperty("uuid", uuid);
            account.add("profile", profile);
        }

        String accessToken = str(storage.get("accessToken"));
        String serverType = null;
        String apiRoot = null;
        String displayName = null;
        JsonObject oauthSeed = null;
        JsonObject credentials = null;

        boolean injector = "yggdrasil".equals(type);
        if (injector) {
            apiRoot = str(legacy.get("server"));
            if (apiRoot == null || apiRoot.isBlank()) {
                LOGGER.warn("injector 账号缺少 server 字段，无法归入服务器，已跳过该账号。");
                return null;
            }
            account.addProperty("server", apiRoot);
            serverType = type;
            displayName = str(legacy.get("accountName"));

            String loginToken = str(legacy.get("loginToken"));
            if (loginToken != null && loginToken.startsWith(OAUTH_PREFIX)) {
                JsonObject envelope = parseEnvelope(loginToken);
                if (envelope == null) {
                    // 降级："需要重新登录"——清空 credentials 但保留账号条目与 profile（08 §3→4.4）
                    LOGGER.warn("OAuth 登录信封解析失败，账号 {} 需要重新登录（条目已保留）。", name);
                } else {
                    credentials = new JsonObject();
                    credentials.addProperty("oauth", true);
                    if (accessToken != null)
                        credentials.addProperty("accessToken", accessToken);
                    credentials.addProperty("refreshToken", str(envelope.get("refresh_token")));
                    // token_endpoint / client_id 归服务器：先种进 servers 的 oauth 块，
                    // 首次探测（P3.1）再从 OIDC 元数据补全 device/userinfo 端点。
                    oauthSeed = new JsonObject();
                    oauthSeed.addProperty("clientId", str(envelope.get("client_id")));
                    oauthSeed.addProperty("tokenEndpoint", str(envelope.get("token_endpoint")));
                }
            } else if (accessToken != null || loginToken != null) {
                credentials = new JsonObject();
                credentials.addProperty("accessToken", accessToken != null ? accessToken : loginToken);
            }
        } else if (type.equals("microsoft")) {
            String msRefresh = str(legacy.get("microsoftAccountRefreshToken"));
            if (accessToken != null || msRefresh != null) {
                credentials = new JsonObject();
                if (accessToken != null)
                    credentials.addProperty("accessToken", accessToken);
                if (msRefresh != null)
                    credentials.addProperty("refreshToken", msRefresh);
            }
            // microsoftAccountAccessToken（XBL/XSTS 链中间 token）不进 v4 模型：
            // refreshToken 足以重建它，留着只会扩大明文 token 落地面。
        }

        if (credentials != null && credentials.size() > 0)
            account.add("credentials", credentials);

        String avatarKey = migrateAvatar(legacy, ctx);
        if (avatarKey != null)
            account.addProperty("avatarKey", avatarKey);

        return new Converted(account, serverType, apiRoot, displayName, oauthSeed);
    }

    private static JsonObject parseEnvelope(String loginToken) {
        try {
            if (!(JsonParser.parseString(loginToken.substring(OAUTH_PREFIX.length())) instanceof JsonObject envelope))
                return null;
            for (String field : List.of("token_endpoint", "refresh_token", "client_id")) {
                if (str(envelope.get(field)) == null) {
                    LOGGER.warn("OAuth 登录信封缺字段 {}。", field);
                    return null;
                }
            }
            return envelope;
        } catch (RuntimeException e) {
            LOGGER.debug("OAuth 登录信封不是合法 JSON。");
            return null;
        }
    }

    /** 已有 avatarKey 直接沿用；旧 base64 头像解码后按内容哈希落盘（D4），失败不阻塞迁移。 */
    private static String migrateAvatar(JsonObject legacy, MigrationContext ctx) {
        String existing = str(legacy.get("avatarKey"));
        if (existing != null && !existing.isBlank())
            return existing;
        String base64 = str(legacy.get("avatar"));
        if (base64 == null || base64.isBlank())
            return null;
        try {
            byte[] png = Base64.getMimeDecoder().decode(base64);
            if (png.length == 0)
                return null;
            String key = AvatarCache.hash(png);
            Path dir = ctx.avatarCacheDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve(key + ".png"), png);
            return key;
        } catch (IllegalArgumentException | IOException e) {
            LOGGER.warn("旧头像迁移失败，忽略（头像可重建）。", e);
            return null;
        }
    }

    // ── servers ──────────────────────────────────────────────────

    /**
     * 同一服务器的多个账号只留一个条目：建过就在已有条目上补缺，不再新建。
     *
     * <p>OAuth 信封只挂在部分账号上（密码登录的账号没有），所以条目第一次建出来时可能没有
     * {@code oauth}。后续带信封的账号必须能把它补进去——{@code servers[].oauth} 是整台服务器
     * 共用的，凭据在账号自己的 {@code credentials} 里；漏了种子，读回时这些账号会降级成
     * "需重登"。补缺与账号在载荷里的先后顺序无关，两种顺序结果一致。</p>
     */
    private static void addServer(JsonArray servers, Converted converted) {
        for (JsonElement e : servers) {
            if (e instanceof JsonObject so
                    && converted.serverType().equals(str(so.get("type")))
                    && converted.apiRoot().equals(str(so.get("apiRoot")))) {
                fillServer(so, converted);
                return;
            }
        }
        JsonObject so = new JsonObject();
        so.addProperty("type", converted.serverType());
        so.addProperty("apiRoot", converted.apiRoot());
        fillServer(so, converted);
        servers.add(so);
    }

    /**
     * 补齐条目里缺的字段，已有的不覆盖：同一 apiRoot 上第二个账号的 {@code accountName} 或
     * 第二份 OAuth 信封（client_id 不同的情况）都以先到者为准——servers 条目只有一份 oauth，
     * 这是数据模型当前的边界，不值得为冲突刷告警。
     */
    private static void fillServer(JsonObject server, Converted converted) {
        if (!server.has("displayName") && converted.displayName() != null)
            server.addProperty("displayName", converted.displayName());
        if (!server.has("oauth") && converted.oauthSeed() != null)
            server.add("oauth", converted.oauthSeed());
    }

    // ── 小工具 ───────────────────────────────────────────────────

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** v4 统一存无横线小写 32 位 hex（定稿 v4.json 格式）；非 UUID 形状原样保留。 */
    private static String normalizeUuid(String raw) {
        if (raw == null)
            return null;
        String s = raw.trim().toLowerCase();
        String hex = s.replace("-", "");
        if (hex.length() == 32 && hex.chars().allMatch(c -> HEX.indexOf(c) >= 0))
            return hex;
        return raw.trim();
    }
}