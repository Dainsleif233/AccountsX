package net.burningtnt.accountsx.core.accounts.impl.microsoft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.burningtnt.accountsx.core.accounts.AccountProvider;
import net.burningtnt.accountsx.core.accounts.AccountUUID;
import net.burningtnt.accountsx.core.adapters.Adapters;
import net.burningtnt.accountsx.core.accounts.model.context.AccountContext;
import net.burningtnt.accountsx.core.accounts.model.context.AuthPolicy;
import net.burningtnt.accountsx.core.ui.Memory;
import net.burningtnt.accountsx.core.ui.UIScreen;
import net.burningtnt.accountsx.core.utils.NetworkUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static net.burningtnt.accountsx.core.utils.AvatarUtils.getAvatar;
import static net.burningtnt.accountsx.core.accounts.impl.microsoft.MicrosoftConstants.SESSION;

public class MicrosoftAccountProvider implements AccountProvider<MicrosoftAccount> {
    private static final String SCOPE = "XboxLive.signin offline_access";

    private static final String CLIENT_ID = "bcc75d9d-4d01-408a-ba7b-131e955e70f1";

    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";

    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    @Override
    public AccountContext createAccountContext(MicrosoftAccount account) throws IOException {
        return new AccountContext(MicrosoftConstants.SERVER_CONTEXT, MicrosoftConstants.computeMicrosoftPublicKeys(), AuthPolicy.ONLINE);
    }

    public MicrosoftAccountProvider() {
    }

    @Override
    public void configure(UIScreen screen) {
        screen.setTitle("as.account.general.external");
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

        Adapters.getMinecraftAdapter().showToast("as.account.oauth2.code.generating", null);

        JsonObject device = NetworkUtils.postRequest(DEVICE_CODE_URL, Map.of(
                "client_id", CLIENT_ID,
                "scope", SCOPE
        ));

        if (memory.isScreenClosed()) {
            throw new CancellationException("Screen has been closed.");
        }

        Adapters.getMinecraftAdapter().copyText(device.get("user_code").getAsString());

        String url = device.get("verification_uri").getAsString();
        Adapters.getMinecraftAdapter().openBrowser(url);

        String microsoftAccessToken, microsoftRefreshToken;

        for (int interval = device.get("interval").getAsInt(); ; ) {
            try {
                Thread.sleep(Math.max(interval, 1));
            } catch (InterruptedException e) {
                throw new IOException("Interrupted.", e);
            }

            if (memory.isScreenClosed()) {
                throw new CancellationException("Screen has been closed.");
            }
            Adapters.getMinecraftAdapter().showToast("as.account.oauth2.code.title", "as.account.oauth2.code.desc", device.get("user_code").getAsString());

            JsonObject token;
            token = NetworkUtils.postRequest(TOKEN_URL, Map.of(
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

            JsonObject json = NetworkUtils.postRequest("https://user.auth.xboxlive.com/user/authenticate", root);
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

            xstsToken = NetworkUtils.postRequest("https://xsts.auth.xboxlive.com/xsts/authorize", root).get("Token").getAsString();
        }

        String accessToken;
        {
            JsonObject root = new JsonObject();
            root.addProperty("identityToken", String.format("XBL3.0 x=%s;%s", userHash, xstsToken));

            JsonObject json = NetworkUtils.postRequest(MicrosoftConstants.MS_LOGIN_XBOX, root);
            accessToken = json.get("access_token").getAsString();
        }

        String playerName, playerUUID;
        {
            JsonObject json = NetworkUtils.postRequest(NetworkUtils.buildGet(MicrosoftConstants.MS_GAME_PROFILE, Map.of(
                    "Authorization", "Bearer " + accessToken
            )));

            if (json.has("error"))
                throw new IOException("Failed to get UUID");

            playerName = json.get("name").getAsString();
            playerUUID = json.get("id").getAsString();
        }

        return new MicrosoftAccount(
                accessToken,
                playerName,
                AccountUUID.parse(playerUUID),
                microsoftAccessToken,
                microsoftRefreshToken,
                getAvatar(SESSION + "/session/minecraft/profile/", playerUUID)
        );
    }

    @Override
    public void refresh(MicrosoftAccount account) throws IOException {
        {
            JsonObject token = NetworkUtils.postRequest(TOKEN_URL, Map.of(
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

            JsonObject json = NetworkUtils.postRequest("https://user.auth.xboxlive.com/user/authenticate", root);
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

            xstsToken = NetworkUtils.postRequest("https://xsts.auth.xboxlive.com/xsts/authorize", root).get("Token").getAsString();
        }

        String accessToken;
        {
            JsonObject root = new JsonObject();
            root.addProperty("identityToken", String.format("XBL3.0 x=%s;%s", userHash, xstsToken));

            JsonObject json = NetworkUtils.postRequest(MicrosoftConstants.MS_LOGIN_XBOX, root);
            accessToken = json.get("access_token").getAsString();
        }

        String playerName, playerUUID;
        {
            JsonObject json = NetworkUtils.postRequest(NetworkUtils.buildGet(MicrosoftConstants.MS_GAME_PROFILE, Map.of(
                    "Authorization", "Bearer " + accessToken
            )));

            if (json.has("error"))
                throw new IOException("Failed to get UUID");

            playerName = json.get("name").getAsString();
            playerUUID = json.get("id").getAsString();
        }

        account.setProfile(accessToken, playerName, AccountUUID.parse(playerUUID));
        account.setAvatar(getAvatar(SESSION + "/session/minecraft/profile/", playerUUID));
    }
}
