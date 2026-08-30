package top.syshub.accountsx.adapters.mc;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.blaze3d.platform.ClipboardManager;
import top.syshub.accountsx.adapters.mc.mixins.PlayerSkinProviderAccessor;
import top.syshub.accountsx.adapters.mc.mixins.mixins.MinecraftClientAccessor;
import top.syshub.accountsx.adapters.mc.mixins.mixins.SplashTextResourceSupplierAccessor;
import top.syshub.accountsx.authlib.AccountSessionImpl;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount;
import top.syshub.accountsx.common.adapters.api.MinecraftPlatform;
import java.net.Proxy;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.network.chat.Component;

public class MinecraftAdapterImpl implements MinecraftPlatform<AccountSessionImpl> {
    @Override
    public EnvironmentAccount fromCurrentClient() {
        User session = Minecraft.getInstance().getUser();
        return new EnvironmentAccount(session.getAccessToken(), session.getName(), session.getProfileId());
    }

    @Override
    public void switchAccount(AccountSessionImpl session) {
        MinecraftSessionService sessionService = session.sessionService();
        UserApiService userAPIService = session.userAPIService();
        BaseAccount.AccountStorage storage = session.storage();
        UserApiService.UserProperties properties = session.properties();
        ProfileResult profileResult = session.profileResult();
        YggdrasilAuthenticationService authenticationService = session.authenticationService();

        // switchAccount 只在已登录（AUTHORIZED）账号上调用，storage 三件套必然非空。
        // 用 requireNonNull 显式声明该不变量，同时满足 User 构造器对 @NotNull 形参的要求。
        String playerName = Objects.requireNonNull(storage.getPlayerName(), "playerName");
        UUID playerUUID = Objects.requireNonNull(storage.getPlayerUUID(), "playerUUID");
        String accessToken = Objects.requireNonNull(storage.getAccessToken(), "accessToken");
        User s = new User(playerName, playerUUID, accessToken, Optional.empty(), Optional.empty(), User.Type.MOJANG);

        Minecraft client = Minecraft.getInstance();
        ((MinecraftClientAccessor) client).setAuthenticationService(authenticationService);
        ((MinecraftClientAccessor) client).setSessionService(sessionService);
        ((MinecraftClientAccessor) client).setSession(s);
        ((MinecraftClientAccessor) client).setGameProfileFuture(CompletableFuture.completedFuture(profileResult));
        ((MinecraftClientAccessor) client).setUserAPIService(userAPIService);
        ((MinecraftClientAccessor) client).setUserPropertiesFuture(CompletableFuture.completedFuture(properties));
        ((SplashTextResourceSupplierAccessor) client.getSplashManager()).setSession(s);
        ((MinecraftClientAccessor) client).setSocialInteractionManager(new PlayerSocialManager(client, userAPIService));
        ((MinecraftClientAccessor) client).setTelemetryManager(new ClientTelemetryManager(client, userAPIService, s));
        ((MinecraftClientAccessor) client).setProfileKeys(ProfileKeyPairManager.create(userAPIService, s, client.gameDirectory.toPath()));
        ((MinecraftClientAccessor) client).setAbuseReportContext(ReportingContext.create(ReportEnvironment.local(), userAPIService));
        ((MinecraftClientAccessor) client).setSkinProvider(new SkinManager(
                client.getTextureManager(),
                ((PlayerSkinProviderAccessor) client.getSkinManager()).accountsX$getDirectory(),
                sessionService,
                ((PlayerSkinProviderAccessor) client.getSkinManager()).accountsX$getExecutor()
        ));
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
        Minecraft.getInstance().schedule(() -> {
            throw e;
        });
    }

    @Override
    public void copyText(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            new ClipboardManager().setClipboard(client.getWindow().getWindow(), text);
        } else {
            client.schedule(() -> copyText(text));
        }
    }

    @Override
    public void showToast(String title, String description, Object... args) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread()) {
            SystemToast.addOrUpdate(
                    Minecraft.getInstance().getToastManager(),
                    SystemToast.SystemToastId.NARRATOR_TOGGLE,
                    Component.translatable(title),
                    description == null ? null : Component.translatable(description, args)
            );
        } else {
            client.schedule(() -> showToast(title, description, args));
        }
    }
}
