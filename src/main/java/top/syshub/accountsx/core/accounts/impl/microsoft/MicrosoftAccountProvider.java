package top.syshub.accountsx.core.accounts.impl.microsoft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import top.syshub.accountsx.core.accounts.AccountProvider;
import top.syshub.accountsx.core.accounts.AccountUUID;
import top.syshub.accountsx.core.adapters.Adapters;
import top.syshub.accountsx.core.accounts.model.context.AccountContext;
import top.syshub.accountsx.core.accounts.model.context.AuthPolicy;
import top.syshub.accountsx.core.net.HttpGateway;
import top.syshub.accountsx.core.ui.Memory;
import top.syshub.accountsx.core.ui.UIScreen;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static top.syshub.accountsx.core.accounts.impl.microsoft.MicrosoftConstants.SESSION;
import top.syshub.accountsx.core.utils.AvatarService;

public class MicrosoftAccountProvider implements AccountProvider<MicrosoftAccount> {
    private static final String SCOPE = "XboxLive.signin offline_access";

    private static final String CLIENT_ID = "bcc75d9d-4d01-408a-ba7b-131e955e70f1";

    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";

    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    private final HttpGateway http;

    @Override
    public AccountContext createAccountContext(MicrosoftAccount account) throws IOException {
        return new AccountContext(MicrosoftConstants.SERVER_CONTEXT, MicrosoftConstants.computeMicrosoftPublicKeys(http), AuthPolicy.ONLINE);
    }

    public MicrosoftAccountProvider(HttpGateway http) {
        this.http = http;
    }

    @Override
    public void configure(UIScreen screen) {
        screen.setTitle("accountsx.account.general.external");
    }

    @Override
    public int validate(UIScreen screen, Memory memory) throws IllegalArgumentException {
        return STATE_HANDLE;
    }

    @Override
    public MicrosoftAccount login(Memory memory) throws IOException, CancellationException {
        if (memory.isScreenClosed()) {
            throw new CancellationException("Screen has been closed.");
        }

        Adapters.getMinecraftAdapter().showToast("accountsx.account.oauth2.code.generating", null);

        JsonObject device = http.postForm(DEVICE_CODE_URL, Map.of(
                "client_id", CLIENT_ID,
                "scope", SCOPE
        ));

        if (memory.isScreenClosed()) {
            throw new CancellationException("Screen has been closed.");
        }

        Adapters.getMinecraftAdapter().copyText(device.get("user_code").getAsString());

        String url = device.get("verification_uri").getAsString();
        Adapters.getMinecraftAdapter().openBrowser(url);

        String microsoftAccessToken = null, microsoftRefreshToken = null;

        int interval;
        if (device.get("interval") instanceof JsonPrimitive jp &&
                jp.isNumber()) interval = jp.getAsInt();
        else interval = 5;
        int expires;
        if (device.get("expires_in") instanceof JsonPrimitive jp &&
                jp.isNumber()) expires = jp.getAsInt();
        else expires = 300;

        for (int i = 0; i < expires; i += interval) {
            try {
                Thread.sleep(Math.max(interval, 1) * 1000L);
            } catch (InterruptedException e) {
                throw new IOException("Interrupted.", e);
            }

            if (memory.isScreenClosed()) {
                throw new CancellationException("Screen has been closed.");
            }
            Adapters.getMinecraftAdapter().showToast("accountsx.account.oauth2.code.title", "accountsx.account.oauth2.code.desc", device.get("user_code").getAsString());

            JsonObject token;
            token = http.postForm(TOKEN_URL, Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "code", device.get("device_code").getAsString(),
                    "client_id", CLIENT_ID
            ), true);

            JsonElement err = token.get("error");
            if (err == null) {
                microsoftAccessToken = token.get("access_token").getAsString();
                microsoftRefreshToken = token.get("refresh_token").getAsString();

                break;
            }

            String error = err.getAsString();

            if ("authorization_pending".equals(error)) {
                continue;
            }

            if ("expired_token".equals(error)) {
                throw new IOException("No character detected.");
            }

            if ("slow_down".equals(error)) {
                interval += 5;
                continue;
            }

            throw new IOException("Unknown error: " + error);
        }

        if (microsoftAccessToken == null || microsoftRefreshToken == null) throw new IOException("Invalid token.");

        String xblToken, userHash;
        {
            JsonObject properties = new JsonObject();
            properties.addProperty("AuthMethod", "RPS");
            properties.addProperty("SiteName", "user.auth.xboxlive.com");
            properties.addProperty("RpsTicket", "d=" + microsoftAccessToken);

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "http://auth.xboxlive.com");
            root.addProperty("TokenType", "JWT");

            JsonObject json = http.postJson("https://user.auth.xboxlive.com/user/authenticate", root);
            xblToken = json.get("Token").getAsString();
            userHash = json.get("DisplayClaims").getAsJsonObject().get("xui").getAsJsonArray().get(0).getAsJsonObject().get("uhs").getAsString();
        }

        String xstsToken;
        {
            JsonArray tokens = new JsonArray();
            tokens.add(xblToken);

            JsonObject properties = new JsonObject();
            properties.addProperty("SandboxId", "RETAIL");
            properties.add("UserTokens", tokens);

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
            root.addProperty("TokenType", "JWT");

            xstsToken = http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", root).get("Token").getAsString();
        }

        String accessToken;
        {
            JsonObject root = new JsonObject();
            root.addProperty("identityToken", String.format("XBL3.0 x=%s;%s", userHash, xstsToken));

            JsonObject json = http.postJson(MicrosoftConstants.MS_LOGIN_XBOX, root);
            accessToken = json.get("access_token").getAsString();
        }

        String playerName, playerUUID;
        {
            JsonObject json = http.get(MicrosoftConstants.MS_GAME_PROFILE, Map.of(
                    "Authorization", "Bearer " + accessToken
            ));

            if (json.has("error"))
                throw new IOException("Failed to get UUID");

            playerName = json.get("name").getAsString();
            playerUUID = json.get("id").getAsString();
        }

        AvatarService.AvatarKey avatar = AvatarService.fetch(http, SESSION + "/session/minecraft/profile/", playerUUID);
        return new MicrosoftAccount(
                accessToken,
                playerName,
                AccountUUID.parse(playerUUID),
                microsoftAccessToken,
                microsoftRefreshToken,
                avatar == null ? null : avatar.key,
                avatar == null ? 0L : avatar.cachedAt
        );
    }

    @Override
    public void refresh(MicrosoftAccount account) throws IOException {
        {
            JsonObject token = http.postForm(TOKEN_URL, Map.of(
                    "client_id", CLIENT_ID,
                    "refresh_token", account.getMicrosoftAccountRefreshToken(),
                    "grant_type", "refresh_token"
            ));

            account.setMicrosoftAccountToken(
                    token.get("access_token").getAsString(),
                    token.get("refresh_token").getAsString()
            );
        }

        String xblToken, userHash;
        {
            JsonObject properties = new JsonObject();
            properties.addProperty("AuthMethod", "RPS");
            properties.addProperty("SiteName", "user.auth.xboxlive.com");
            properties.addProperty("RpsTicket", "d=" + account.getMicrosoftAccountAccessToken());

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "http://auth.xboxlive.com");
            root.addProperty("TokenType", "JWT");

            JsonObject json = http.postJson("https://user.auth.xboxlive.com/user/authenticate", root);
            xblToken = json.get("Token").getAsString();
            userHash = json.get("DisplayClaims").getAsJsonObject().get("xui").getAsJsonArray().get(0).getAsJsonObject().get("uhs").getAsString();
        }

        String xstsToken;
        {
            JsonArray tokens = new JsonArray();
            tokens.add(xblToken);

            JsonObject properties = new JsonObject();
            properties.addProperty("SandboxId", "RETAIL");
            properties.add("UserTokens", tokens);

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
            root.addProperty("TokenType", "JWT");

            xstsToken = http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", root).get("Token").getAsString();
        }

        String accessToken;
        {
            JsonObject root = new JsonObject();
            root.addProperty("identityToken", String.format("XBL3.0 x=%s;%s", userHash, xstsToken));

            JsonObject json = http.postJson(MicrosoftConstants.MS_LOGIN_XBOX, root);
            accessToken = json.get("access_token").getAsString();
        }

        String playerName, playerUUID;
        {
            JsonObject json = http.get(MicrosoftConstants.MS_GAME_PROFILE, Map.of(
                    "Authorization", "Bearer " + accessToken
            ));

            if (json.has("error"))
                throw new IOException("Failed to get UUID");

            playerName = json.get("name").getAsString();
            playerUUID = json.get("id").getAsString();
        }

        account.setProfile(accessToken, playerName, AccountUUID.parse(playerUUID));
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, SESSION + "/session/minecraft/profile/", playerUUID);
        account.setAvatar(avatar == null ? null : avatar.key, avatar == null ? 0L : avatar.cachedAt);
    }
}
