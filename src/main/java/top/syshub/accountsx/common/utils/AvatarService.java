package top.syshub.accountsx.common.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.image.AvatarCache;
import top.syshub.accountsx.common.utils.image.AwtAvatarRenderer;

import java.util.Base64;
import java.util.Optional;

import static top.syshub.accountsx.common.AccountsX.LOGGER;

/**
 * 头像获取与落盘编排（P1.4 / 决策 D4）。
 *
 * <p>把原 {@code AvatarUtils} 的「取 profile → 下载皮肤 → AWT 裁剪 → base64 存配置」
 * 流程改造为「取 profile → 下载皮肤字节 → 交给 {@code image} 包裁剪 → 按内容哈希
 * 落盘 {@code ~/.cache/accountsx/avatars/<hash>.png} → 返回哈希 key」。网络走可注入的
 * {@link HttpGateway}（P1.3），AWT 渲染与磁盘缓存在 core 的 {@code image} 包完成
 * （P1.4 曾迁出到独立 {@code core-image} 模块，后迁回 core）。</p>
 *
 * <p>本方法是「可重建缓存」的最佳努力路径：任何失败都 {@code LOGGER.error} 吞掉并返回
 * {@code null}，不影响登录 / 刷新主流程（原 {@code AvatarUtils.getAvatar} 行为一致）。</p>
 */
public final class AvatarService {

    private AvatarService() {
    }

    /**
     * 头像落盘后返回的内容哈希 key 与缓存时间戳。
     */
        public record AvatarKey(String key, long cachedAt) {
    }

    /**
     * 获取并缓存某 profile 的头像。
     *
     * @param profileUrl 形如 {@code <sessionserver>/session/minecraft/profile/}（不含 UUID）
     * @param playerUuid 玩家 UUID 字符串（含或不含连字符均可，内部统一去除）
     * @return 头像 key（内容哈希），失败返回 {@code null}
     */
    public static AvatarKey fetch(HttpGateway http, String profileUrl, String playerUuid) {
        try {
            String skinUrl = resolveSkinUrl(http, profileUrl + playerUuid.replace("-", ""));
            if (skinUrl == null) {
                return null;
            }

            byte[] skin = http.getBinary(skinUrl);
            if (skin == null || skin.length == 0) {
                return null;
            }

            byte[] avatarPng = AwtAvatarRenderer.INSTANCE.renderAvatar(skin);
            if (avatarPng.length == 0) {
                return null;
            }

            // AvatarCache.store 内部按内容哈希命名并返回 key。
            String key = AvatarCache.store(avatarPng);
            return new AvatarKey(key, System.currentTimeMillis());
        } catch (Exception e) {
            // 2.6 修复：异常作为 throwable 参数传入，保留堆栈（原写法把 e 当 {} 占位符丢失堆栈）
            LOGGER.error("Loading avatar for profile: {} error", profileUrl, e);
            return null;
        }
    }

    /** 解析 profile 的 SKIN 纹理 URL（从原 AvatarUtils.getSkinUrl 搬来，纯网络，无 AWT）。 */
    private static String resolveSkinUrl(HttpGateway http, String profileUrlWithUuid) {
        try {
            JsonObject skinJson = http.get(profileUrlWithUuid);
            JsonArray properties = skinJson.getAsJsonArray("properties");

            Optional<JsonObject> textureProperty = properties.asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .filter(obj -> obj.get("name").getAsString().equals("textures"))
                    .findFirst();

            String valueBase64 = textureProperty.map(jsonObject -> jsonObject.get("value").getAsString()).orElse(null);
            if (valueBase64 == null) {
                return null;
            }
            String valueJson = new String(Base64.getDecoder().decode(valueBase64), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject valueObject = NetworkUtils.GSON.fromJson(valueJson, JsonObject.class);

            return valueObject.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
