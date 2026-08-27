package top.syshub.accountsx.core.accounts.impl.injector;

import com.google.gson.*;
import top.syshub.accountsx.core.accounts.AccountProvider;
import top.syshub.accountsx.core.accounts.AccountUUID;
import top.syshub.accountsx.core.accounts.model.PlayerNoLongerExistedException;
import top.syshub.accountsx.core.accounts.model.context.*;
import top.syshub.accountsx.core.adapters.Adapters;
import top.syshub.accountsx.core.ui.Memory;
import top.syshub.accountsx.core.ui.UIScreen;
import top.syshub.accountsx.core.utils.AvatarUtils;
import top.syshub.accountsx.core.utils.NetworkUtils;

import java.io.IOException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

public abstract class AbstractInjectorAccountProvider<T extends AbstractInjectorAccount> implements AccountProvider<T> {
    private static final String GUID_SERVER_BASE = "guid:as.login.injector.widgets.server_url";
    private static final String GUID_USER_NAME = "guid:as.login.injector.widgets.user_name";
    private static final String GUID_PASSWORD = "guid:as.login.injector.widgets.user_password";
    private static final String GUID_PLAYER_NAME = "guid:as.login.injector.widgets.player_name";

    private final String serverBaseTranslationKey;

    private final String userBaseTranslationKey;

    private final String accountContextName;

    protected AbstractInjectorAccountProvider(String serverBaseTranslationKey, String userBaseTranslationKey, String accountContextName) {
        this.serverBaseTranslationKey = serverBaseTranslationKey;
        this.accountContextName = accountContextName;
        this.userBaseTranslationKey = userBaseTranslationKey;
    }

    protected void validateServerBaseURL(String server) throws IllegalArgumentException {}

    protected abstract String transformServerBaseURL(String server);

    protected abstract T createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatar);

    @Override
    public final AccountContext createAccountContext(T account) throws IOException {
        String url = account.getServer();

        List<PublicKey> publicKeys;
        List<String> skinDomains = new ArrayList<>();

        JsonObject response = NetworkUtils.postRequest(NetworkUtils.buildGet(url));
        if (response.get("signaturePublickey") instanceof JsonPrimitive jp && jp.isString()) {
            try {
                publicKeys = List.of(parseSignaturePublicKey(jp.getAsString()));
            } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IOException("Invalid yggdrasil public key!", e);
            }
        } else {
            throw new IOException("Invalid yggdrasil public key!");
        }

        if (response.get("skinDomains") instanceof JsonArray ja) {
            for (JsonElement je : ja) {
                if (je instanceof JsonPrimitive domain && domain.isString()) {
                    skinDomains.add(domain.getAsString());
                } else {
                    throw new IOException("Invalid yggdrasil public key!");
                }
            }
        } else {
            throw new IOException("Invalid yggdrasil public key!");
        }

        return new AccountContext(new AuthServerContext(
                url + "/authserver",
                url + "/api",
                url + "/sessionserver",
                url + "/minecraftservices",
                accountContextName
        ), new AuthSecurityContext(
                publicKeys, publicKeys,
                SkinURLVerifier.ofOperationOR(SkinURLVerifier.ofDomainVerifier(skinDomains, List.of()), SkinURLVerifier.MOJANG_DEFAULT)
        ), AuthPolicy.TRY);
    }

    private static PublicKey parseSignaturePublicKey(String pem) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        pem = pem.replace("\n", "").replace("\r", "");

        String header = "-----BEGIN PUBLIC KEY-----", end = "-----END PUBLIC KEY-----";
        if (!pem.startsWith(header) || !pem.endsWith(end)) {
            throw new IOException("Bad key format");
        }

        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(pem.substring(header.length(), pem.length() - end.length()))
        ));
    }

    @Override
    public final void configure(UIScreen screen) {
        screen.setTitle("accountsx.account.general.login");
        screen.putTextInput(GUID_SERVER_BASE, serverBaseTranslationKey);
        screen.putTextInput(GUID_USER_NAME, userBaseTranslationKey);
        screen.putTextInput(GUID_PASSWORD, "accountsx.account.objects.user_password");
        screen.putTextInput(GUID_PLAYER_NAME, "accountsx.account.objects.player_name");
    }

    @Override
    public final int validate(UIScreen screen, Memory memory) throws IllegalArgumentException {
        String serverBase = screen.getTextInput(GUID_SERVER_BASE);
        validateServerBaseURL(serverBase);
        memory.set(GUID_SERVER_BASE, serverBase);
        memory.set(GUID_USER_NAME, screen.getTextInput(GUID_USER_NAME));
        memory.set(GUID_PASSWORD, screen.getTextInput(GUID_PASSWORD));
        memory.set(GUID_PLAYER_NAME, screen.getTextInput(GUID_PLAYER_NAME));

        return STATE_IMMEDIATE_CLOSE;
    }

    @Override
    public final T login(Memory memory) throws IOException {
        if (memory.get(GUID_USER_NAME, String.class).isEmpty()) return loginOAuth(memory.get(GUID_SERVER_BASE, String.class));
        String baseUrl = transformServerBaseURL(memory.get(GUID_SERVER_BASE, String.class));
        String loginUrl = baseUrl + "/authserver/authenticate";
        String profileUrl = baseUrl + "/sessionserver/session/minecraft/profile/";

        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);

        JsonObject root = new JsonObject();
        root.add("agent", agent);
        root.addProperty("username", memory.get(GUID_USER_NAME, String.class));
        root.addProperty("password", memory.get(GUID_PASSWORD, String.class));

        JsonObject json = NetworkUtils.postRequest(loginUrl, root);
        if (json.has("error")) {
            throw new IOException("Cannot auth this injector: " + json.get("errorMessage").getAsString());
        }

        String accessToken = json.get("accessToken").getAsString();

        String playerName = memory.get(GUID_PLAYER_NAME, String.class);
        List<Profile> profiles = readProfiles(json);
        if (profiles.size() == 1) {
            Profile profile = profiles.get(0);

            if (!playerName.isEmpty()) {
                if (!playerName.equals(profile.playerName)) {
                    throw new IOException("Player not found.");
                }
            }

            return createAccount(
                    accessToken, profile.playerName,
                    AccountUUID.parse(profile.playerUUID),
                    baseUrl,
                    profile.playerUUID,
                    getAccountName(baseUrl),
                    AvatarUtils.getAvatar(profileUrl, profile.playerUUID)
            );
        } else {
            for (Profile profile : profiles) {
                if (playerName.equals(profile.playerName)) {
                    return createAccount(
                            accessToken,
                            profile.playerName,
                            AccountUUID.parse(profile.playerUUID),
                            baseUrl,
                            profile.playerUUID,
                            getAccountName(baseUrl),
                            AvatarUtils.getAvatar(profileUrl, profile.playerUUID)
                    );
                }
            }

            throw new PlayerNoLongerExistedException("Cannot find player which match " + playerName);
        }
    }

    @Override
    public final void refresh(T account) throws IOException {
        if (account.getLoginToken().startsWith("OAuth ")) {
            refreshOAuth(account);
            return;
        }
        String baseUrl = account.getServer();
        String refreshUrl = baseUrl + "/authserver/refresh";
        String profileUrl = baseUrl + "/sessionserver/session/minecraft/profile/";

        JsonObject root = new JsonObject();
        root.addProperty("accessToken", account.getLoginToken());

        JsonObject json = NetworkUtils.postRequest(refreshUrl, root);
        if (json.has("error")) {
            throw new IOException("Cannot auth this injector: " + json.get("errorMessage").getAsString());
        }

        String accessToken = json.get("accessToken").getAsString();

        List<Profile> profiles = readProfiles(json);
        if (profiles.size() == 1) {
            Profile profile = profiles.get(0);
            account.setLoginProfile(accessToken, profile.playerUUID);
            account.setProfile(accessToken, profile.playerName, AccountUUID.parse(profile.playerUUID));
        } else {
            String preferredPlayerUUID = account.getPreferredPlayerUUID();

            for (Profile profile : profiles) {
                if (profile.playerUUID.equals(preferredPlayerUUID)) {
                    account.setLoginProfile(accessToken, profile.playerUUID);
                    account.setProfile(accessToken, profile.playerName, AccountUUID.parse(profile.playerUUID));
                    account.setAvatar(AvatarUtils.getAvatar(profileUrl, profile.playerUUID));
                    return;
                }
            }

            throw new PlayerNoLongerExistedException("Cannot find player which match " + preferredPlayerUUID);
        }
    }

    private String getAccountName(String baseUrl) {
        try {
            JsonObject ygg = NetworkUtils.postRequest(NetworkUtils.buildGet(baseUrl));
            return ygg.get("meta").getAsJsonObject().get("serverName").getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record Profile(String playerName, String playerUUID) {}

    private static List<Profile> readProfiles(JsonObject json) {
        JsonElement selectedProfile = json.get("selectedProfile");
        if (selectedProfile != null) {
            JsonObject jo = selectedProfile.getAsJsonObject();
            String playerName = jo.get("name").getAsString();
            String playerUUID = jo.get("id").getAsString();

            return List.of(new Profile(playerName, playerUUID));
        } else {
            JsonArray availableProfiles = json.get("availableProfiles").getAsJsonArray();
            List<Profile> results = new ArrayList<>(availableProfiles.size());

            for (JsonElement availableProfile : availableProfiles) {
                JsonObject jo = availableProfile.getAsJsonObject();
                String playerName = jo.get("name").getAsString();
                String playerUUID = jo.get("id").getAsString();
                results.add(new Profile(playerName, playerUUID));
            }

            return results;
        }
    }

    private T loginOAuth(String server) throws IOException {
        String yggUrl = transformServerBaseURL(server);
        String profileUrl = yggUrl + "/sessionserver/session/minecraft/profile/";

        String openidConfigurationUrl;
        JsonObject ygg = NetworkUtils.postRequest(NetworkUtils.buildGet(yggUrl));
        if (ygg.get("meta") instanceof JsonObject meta &&
                meta.get("feature.openid_configuration_url") instanceof JsonPrimitive jp1 &&
                jp1.isString()) openidConfigurationUrl = jp1.getAsString();
        else throw new IOException("Invalid openid configuration url!");

        JsonObject config = NetworkUtils.postRequest(NetworkUtils.buildGet(openidConfigurationUrl));
        String deviceAuthorizationEndpoint = config.get("device_authorization_endpoint").getAsString();
        String tokenEndpoint = config.get("token_endpoint").getAsString();
        String clientId;

        String host = URI.create(yggUrl).getHost();
        if (OAuthConstants.list.containsKey(host)) clientId = OAuthConstants.list.get(host);
        else if (config.get("shared_client_id") instanceof JsonPrimitive jp2 &&
                jp2.isString()) clientId = jp2.getAsString();
        else throw new IOException("Invalid client id!");

        Adapters.getMinecraftAdapter().showToast("accountsx.account.oauth2.code.generating", null);

        Map<String, String> form1 = Map.of(
                "client_id", clientId,
                "scope", "openid offline_access Yggdrasil.PlayerProfiles.Select Yggdrasil.Server.Join"
        );
        JsonObject device = NetworkUtils.postRequest(deviceAuthorizationEndpoint, form1);
        String deviceCode = device.get("device_code").getAsString();
        String userCode = device.get("user_code").getAsString();
        int interval;
        if (device.get("interval") instanceof JsonPrimitive jp &&
                jp.isNumber()) interval = jp.getAsInt();
        else interval = 5;
        int expires;
        if (device.get("expires_in") instanceof JsonPrimitive jp &&
                jp.isNumber()) expires = jp.getAsInt();
        else expires = 300;
        if (device.get("verification_uri_complete") instanceof JsonPrimitive jp && jp.isString()) {
            Adapters.getMinecraftAdapter().openBrowser(jp.getAsString());
            Adapters.getMinecraftAdapter().copyText(jp.getAsString());
        } else {
            Adapters.getMinecraftAdapter().copyText(userCode);
            // 1.10 修复：原为取出来即丢弃的死语句，导致无 verification_uri_complete 时不打开浏览器
            Adapters.getMinecraftAdapter().openBrowser(device.get("verification_uri").getAsString());
        }
        Adapters.getMinecraftAdapter().showToast("accountsx.account.oauth2.code.title", "accountsx.account.oauth2.code.desc", userCode);

        String accessToken = null, refreshToken = null, idToken = null;
        for (int i = 0; i < expires; i += interval) {
            try {
                Thread.sleep(Math.max(interval, 1) * 1000L);
            } catch (InterruptedException e) {
                throw new IOException("Interrupted.", e);
            }

            Map<String, String> form2 = Map.of(
                    "client_id", clientId,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", deviceCode
            );
            JsonObject token = NetworkUtils.postRequest(tokenEndpoint, form2, true);

            JsonElement err = token.get("error");
            if (err == null) {
                accessToken = token.get("access_token").getAsString();
                refreshToken = token.get("refresh_token").getAsString();
                idToken = token.get("id_token").getAsString();
                break;
            }

            String error = err.getAsString();
            if (error.equals("authorization_pending")) continue;
            if (error.equals("expired_token")) throw new IOException("No character detected.");
            throw new IOException("Unknown error: " + error);
        }

        if (accessToken == null || refreshToken == null || idToken == null) throw new IOException("Invalid token.");

        String[] parts = idToken.split("\\.");
        if (parts.length < 2) throw new IOException("Invalid id token.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String payload = new String(decoder.decode(parts[1]));
        JsonObject userinfo = JsonParser.parseString(payload).getAsJsonObject();
        Profile profile = readProfiles(userinfo).get(0);

        JsonObject OAuth = new JsonObject();
        OAuth.addProperty("token_endpoint", tokenEndpoint);
        OAuth.addProperty("refresh_token", refreshToken);
        OAuth.addProperty("client_id", clientId);

        T account = createAccount(
                accessToken,
                profile.playerName,
                AccountUUID.parse(profile.playerUUID),
                yggUrl,
                profile.playerUUID,
                getAccountName(yggUrl),
                AvatarUtils.getAvatar(profileUrl, profile.playerUUID)
        );
        account.setLoginProfile("OAuth " + OAuth, profile.playerUUID);
        return account;
    }

    private void refreshOAuth(T account) throws IOException {
        String OAuthStr = account.getLoginToken().substring(6);
        JsonObject OAuth = JsonParser.parseString(OAuthStr).getAsJsonObject();

        String tokenEndpoint = OAuth.get("token_endpoint").getAsString();
        String refreshToken = OAuth.get("refresh_token").getAsString();
        String clientId = OAuth.get("client_id").getAsString();

        Map<String, String> form = Map.of(
                "client_id", clientId,
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
        );
        JsonObject token = NetworkUtils.postRequest(tokenEndpoint, form);

        JsonElement err = token.get("error");
        if (err !=null) throw new IOException("Unknown error: " + err.getAsString());
        String accessToken = token.get("access_token").getAsString();
        refreshToken = token.get("refresh_token").getAsString();
        OAuth.addProperty("refresh_token", refreshToken);
        String idToken = token.get("id_token").getAsString();

        String[] parts = idToken.split("\\.");
        if (parts.length < 2) throw new IOException("Invalid id token.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String payload = new String(decoder.decode(parts[1]));
        JsonObject userinfo = JsonParser.parseString(payload).getAsJsonObject();
        Profile profile = readProfiles(userinfo).get(0);
        String profileUrl = account.getServer() + "/sessionserver/session/minecraft/profile/";

        account.setProfile(accessToken, profile.playerName, AccountUUID.parse(profile.playerUUID));
        account.setLoginProfile("OAuth " + OAuth, profile.playerUUID);
        account.setAvatar(AvatarUtils.getAvatar(profileUrl, profile.playerUUID));
    }
}
