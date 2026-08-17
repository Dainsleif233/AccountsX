package top.syshub.accountsx.adapters.mc;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.blaze3d.platform.ClipboardManager;
import top.syshub.accountsx.adapters.mc.mixins.MinecraftClientAccessor;
import top.syshub.accountsx.adapters.mc.mixins.PlayerSkinProviderAccessor;
import top.syshub.accountsx.authlib.AccountSessionImpl;
import top.syshub.accountsx.core.accounts.AccountUUID;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.impl.env.EnvironmentAccount;
import top.syshub.accountsx.core.adapters.api.MinecraftAdapter;
import java.net.Proxy;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.network.chat.Component;

public class MinecraftAdapaterImpl implements MinecraftAdapter<AccountSessionImpl> {
    @Override
    public EnvironmentAccount fromCurrentClient() {
        User session = Minecraft.getInstance().getUser();
        return new EnvironmentAccount(session.getAccessToken(), session.getName(), session.getProfileId());
    }

    @Override
    public <T extends BaseAccount> void switchAccount(AccountSessionImpl session) {
        MinecraftSessionService sessionService = session.sessionService();
        UserApiService userAPIService = session.userAPIService();
        BaseAccount.AccountStorage storage = session.storage();
        YggdrasilAuthenticationService authenticationService = session.authenticationService();

        User s = new User(storage.getPlayerName(), AccountUUID.toMinecraftStyleString(storage.getPlayerUUID()), storage.getAccessToken(), Optional.empty(), Optional.empty(), User.Type.MOJANG);

        Minecraft client = Minecraft.getInstance();
        ((MinecraftClientAccessor) client).setSession(s);
        ((MinecraftClientAccessor) client).setAuthenticationService(authenticationService);
        ((MinecraftClientAccessor) client).setSessionService(sessionService);
        ((MinecraftClientAccessor) client).setUserAPIService(userAPIService);
        ((MinecraftClientAccessor) client).setSocialInteractionManager(new PlayerSocialManager(client, userAPIService));
        ((MinecraftClientAccessor) client).setProfileKeys(ProfileKeyPairManager.create(userAPIService, s, client.gameDirectory.toPath()));
        ((MinecraftClientAccessor) client).setSkinProvider(new SkinManager(
                client.getTextureManager(),
                ((PlayerSkinProviderAccessor) client.getSkinManager()).getSkinCacheDir(),
                sessionService
        ));

        PropertyMap propertyMap = ((MinecraftClientAccessor) client).getSessionPropertyMap();
        propertyMap.clear();
        propertyMap.putAll(session.gameProfile().getProperties());
    }

    @Override
    public Proxy getGameProxy() {
        return Minecraft.getInstance().getProxy();
    }

    @Override
    public void openBrowser(String url) {
        Util.getPlatform().openUri(url);
    }

    @Override
    public Thread getMinecraftClientThread() {
        return ((MinecraftClientAccessor) Minecraft.getInstance()).getThread();
    }

    @Override
    public void crash(RuntimeException e) {
        Minecraft.getInstance().tell(() -> {
            throw e;
        });
    }

    @Override
    public void copyText(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            new ClipboardManager().setClipboard(client.getWindow().getWindow(), text);
        } else {
            client.tell(() -> copyText(text));
        }
    }

    @Override
    public void showToast(String title, String description, Object... args) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            SystemToast.addOrUpdate(
                    Minecraft.getInstance().getToasts(),
                    SystemToast.SystemToastIds.NARRATOR_TOGGLE,
                    Component.translatable(title),
                    description == null ? null : Component.translatable(description, args)
            );
        } else {
            client.tell(() -> showToast(title, description, args));
        }
    }
}
