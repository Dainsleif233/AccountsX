package top.syshub.accountsx.core.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import top.syshub.accountsx.core.net.JdkHttpGateway;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static top.syshub.accountsx.core.AccountsX.LOGGER;

public class AvatarUtils {

    private static String drawAvatar(BufferedImage skin) throws IOException {
        int scale = Math.max(1, skin.getWidth() / 64);
        int faceOffset = (int) Math.round(64 / 18.0);

        // 创建目标图像（带透明通道）
        BufferedImage avatar = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = avatar.createGraphics();

        // 关闭平滑（保持像素风格）
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // 清空为透明
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, 64, 64);
        g.setComposite(AlphaComposite.SrcOver);

        // 从皮肤图中裁剪基础脸部（8,8,8,8）并绘制到带边距的目标区域
        int sxFace = 8 * scale, syFace = 8 * scale, swFace = 8 * scale, shFace = 8 * scale;
        int dwFace = 64 - 2 * faceOffset, dhFace = 64 - 2 * faceOffset;
        g.drawImage(skin, faceOffset, faceOffset, faceOffset + dwFace, faceOffset + dhFace,
                sxFace, syFace, sxFace + swFace, syFace + shFace, null);

        // 从皮肤图中裁剪覆盖层（帽子/发型）(40,8,8,8) 并绘制到整个头像区域，叠加在基础脸部之上
        int sxHat = 40 * scale, syHat = 8 * scale, swHat = 8 * scale, shHat = 8 * scale;
        g.drawImage(skin, 0, 0, 64, 64,
                sxHat, syHat, sxHat + swHat, syHat + shHat, null);

        g.dispose();

        // 写成 PNG 并 base64 编码返回
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(avatar, "PNG", out);
            byte[] png = out.toByteArray();
            return Base64.getEncoder().encodeToString(png);
        }
    }

    private static BufferedImage getSkin(String skinUrl) throws IOException {
        if (skinUrl == null) throw new IllegalArgumentException("skinUrl is null");

        BufferedImage skin = ImageIO.read(URI.create(skinUrl).toURL());
        if (skin == null) throw new IOException("Failed to decode skin image");

        return skin;
    }

    private static String getSkinUrl(String profileUrl) {
        // P1.3：头像网络请求走默认 JdkHttpGateway.INSTANCE，未做构造注入。
        // 这是刻意延后到 P1.4（头像迁出 core）的范围边界——届时整个头像逻辑
        // 会搬到非 core 模块，注入点随之消失；现在注入即会被 P1.4 推翻。
        // 头像加载失败被外层 catch 吞掉，不影响登录/刷新主路径，因此不阻塞 P1.3 的可测目标。
        try {
            JsonObject skinJson = JdkHttpGateway.INSTANCE.get(profileUrl);
            JsonArray properties = skinJson.getAsJsonArray("properties");

            Optional<JsonObject> textureProperty = properties.asList().stream()
                    .map(JsonElement::getAsJsonObject)
                    .filter(obj -> obj.get("name").getAsString().equals("textures"))
                    .findFirst();

            String valueBase64 = textureProperty.map(jsonObject -> jsonObject.get("value").getAsString()).orElse(null);
            String valueJson = new String(Base64.getDecoder().decode(valueBase64), StandardCharsets.UTF_8);
            JsonObject valueObject = new Gson().fromJson(valueJson, JsonObject.class);

            return valueObject.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String getAvatar(String profileUrl, String uuid) {
        try {
            String skinUrl = getSkinUrl(profileUrl + uuid.replace("-", ""));
            BufferedImage skin = getSkin(skinUrl);

            return drawAvatar(skin);
        } catch (Exception e) {
            // 2.6 修复：异常作为 throwable 参数传入，保留堆栈（原写法把 e 当 {} 占位符丢失堆栈）
            LOGGER.error("Loading avatar for profile: {} error", profileUrl, e);
            return null;
        }
    }
}
