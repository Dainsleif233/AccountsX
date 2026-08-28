package top.syshub.accountsx.core.accounts.impl.microsoft;

import com.google.gson.annotations.SerializedName;
import top.syshub.accountsx.core.AccountsX;
import top.syshub.accountsx.core.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.core.accounts.model.context.AuthServerContext;
import top.syshub.accountsx.core.net.HttpGateway;
import top.syshub.accountsx.core.net.JdkHttpGateway;
import top.syshub.accountsx.core.utils.NetworkUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class MicrosoftConstants {
    // https://authserver.mojang.com
    public static final String AUTH = decode("aHR0cHM6Ly9hdXRoc2VydmVyLm1vamFuZy5jb20=");

    // https://api.mojang.com
    public static final String ACCOUNT = decode("aHR0cHM6Ly9hcGkubW9qYW5nLmNvbQ==");

    // https://sessionserver.mojang.com
    public static final String SESSION = decode("aHR0cHM6Ly9zZXNzaW9uc2VydmVyLm1vamFuZy5jb20=");

    // https://api.minecraftservices.com
    public static final String SERVICES = decode("aHR0cHM6Ly9hcGkubWluZWNyYWZ0c2VydmljZXMuY29t");

    public static final AuthServerContext SERVER_CONTEXT = new AuthServerContext(
            AUTH, ACCOUNT, SESSION, SERVICES, "PROD"
    );

    // https://api.minecraftservices.com/authentication/login_with_xbox
    public static final String MS_LOGIN_XBOX = decode("aHR0cHM6Ly9hcGkubWluZWNyYWZ0c2VydmljZXMuY29tL2F1dGhlbnRpY2F0aW9uL2xvZ2luX3dpdGhfeGJveA==");

    // https://api.minecraftservices.com/minecraft/profile
    public static final String MS_GAME_PROFILE = decode("aHR0cHM6Ly9hcGkubWluZWNyYWZ0c2VydmljZXMuY29tL21pbmVjcmFmdC9wcm9maWxl");

    public static void initialize() {
        if (!AUTH.equals("https://authserver.mojang.com")) {
            AccountsX.LOGGER.warn("authlib-injector is detected! The compatibility between AccountsX and authlib-injector is an experimental feature.");
        }
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.ISO_8859_1);
    }

    /**
     * 默认实现，使用生产网关 {@link JdkHttpGateway#INSTANCE}。
     * 供 authlib 适配器（7.0.61）原样调用，适配器不在 P1 改动范围内。
     */
    public static AuthSecurityContext computeMicrosoftPublicKeys() throws IOException {
        return computeMicrosoftPublicKeys(JdkHttpGateway.INSTANCE);
    }

    /**
     * 可注入实现（P1.3）：从 {@link HttpGateway} 取 Microsoft 公钥，使认证流程可在单测中用假网关驱动。
     */
    public static AuthSecurityContext computeMicrosoftPublicKeys(HttpGateway http) throws IOException {
        KeySetResponse response = NetworkUtils.GSON.fromJson(
                http.get(SERVICES + "/publickeys"),
                KeySetResponse.class
        );

        if (response == null) {
            throw new IOException("Received malformed yggdrasil public key data: null.");
        }

        try {
            // 1.2 修复：第二个参数应为 playerCertificateKeys（聊天签名密钥校验），而非再次传 profilePropertyKeys
            return new AuthSecurityContext(parsePublicKeys(response.profilePropertyKeys), parsePublicKeys(response.playerCertificateKeys));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IOException("Received malformed yggdrasil public key data.", e);
        }
    }

    private static List<PublicKey> parsePublicKeys(List<KeyData> data) throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (data == null || data.isEmpty()) {
            return List.of();
        }

        List<PublicKey> r = new ArrayList<>(data.size());
        for (KeyData kd : data) {
            r.add(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(kd.publicKey))));
        }

        return r;
    }

    private record KeySetResponse(
            @SerializedName("profilePropertyKeys")
            List<KeyData> profilePropertyKeys,
            @SerializedName("playerCertificateKeys")
            List<KeyData> playerCertificateKeys
    ) {
    }

    private record KeyData(
            @SerializedName("publicKey")
            String publicKey
    ) {
    }
}
