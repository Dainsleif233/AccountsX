package net.burningtnt.accountsx.adapters.mc;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.burningtnt.accountsx.adapters.mc.mixins.PlayerSkinProviderAccessor;
import net.burningtnt.accountsx.adapters.mc.mixins.mixins.MinecraftClientAccessor;
import net.burningtnt.accountsx.adapters.mc.mixins.mixins.SplashTextResourceSupplierAccessor;
import net.burningtnt.accountsx.authlib.AccountSessionImpl;
import net.burningtnt.accountsx.core.accounts.BaseAccount;
import net.burningtnt.accountsx.core.accounts.impl.env.EnvironmentAccount;
import net.burningtnt.accountsx.core.adapters.api.MinecraftAdapter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.client.session.report.AbuseReportContext;
import net.minecraft.client.session.report.ReporterEnvironment;
import net.minecraft.client.session.telemetry.TelemetryManager;
import net.minecraft.client.texture.PlayerSkinProvider;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.Clipboard;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.net.Proxy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class MinecraftAdapaterImpl implements MinecraftAdapter<AccountSessionImpl> {
    @Override
    public EnvironmentAccount fromCurrentClient() {
        Session session = MinecraftClient.getInstance().getSession();
        return new EnvironmentAccount(session.getAccessToken(), session.getUsername(), session.getUuidOrNull());
    }

    @Override
    public <T extends BaseAccount> void switchAccount(AccountSessionImpl session) {
        MinecraftSessionService sessionService = session.sessionService();
        UserApiService userAPIService = session.userAPIService();
        BaseAccount.AccountStorage storage = session.storage();
        UserApiService.UserProperties properties = session.properties();
        ProfileResult profileResult = session.profileResult();
        YggdrasilAuthenticationService authenticationService = session.authenticationService();

        Session s = new Session(storage.getPlayerName(), storage.getPlayerUUID(), storage.getAccessToken(), Optional.empty(), Optional.empty(), Session.AccountType.MOJANG);

        MinecraftClient client = MinecraftClient.getInstance();
        ((MinecraftClientAccessor) client).setAuthenticationService(authenticationService);
        ((MinecraftClientAccessor) client).setSessionService(sessionService);
        ((MinecraftClientAccessor) client).setSession(s);
        ((MinecraftClientAccessor) client).setGameProfileFuture(CompletableFuture.completedFuture(profileResult));
        ((MinecraftClientAccessor) client).setUserAPIService(userAPIService);
        ((MinecraftClientAccessor) client).setUserPropertiesFuture(CompletableFuture.completedFuture(properties));
        ((SplashTextResourceSupplierAccessor) client.getSplashTextLoader()).setSession(s);
        ((MinecraftClientAccessor) client).setSocialInteractionManager(new SocialInteractionsManager(client, userAPIService));
        ((MinecraftClientAccessor) client).setTelemetryManager(new TelemetryManager(client, userAPIService, s));
        ((MinecraftClientAccessor) client).setProfileKeys(ProfileKeys.create(userAPIService, s, client.runDirectory.toPath()));
        ((MinecraftClientAccessor) client).setAbuseReportContext(AbuseReportContext.create(ReporterEnvironment.ofIntegratedServer(), userAPIService));
        ((MinecraftClientAccessor) client).setSkinProvider(new PlayerSkinProvider(
                client.getTextureManager(),
                ((PlayerSkinProviderAccessor) client.getSkinProvider()).accountsX$getDirectory(),
                sessionService,
                ((PlayerSkinProviderAccessor) client.getSkinProvider()).accountsX$getExecutor()
        ));
    }

    @Override
    public Proxy getGameProxy() {
        return MinecraftClient.getInstance().getNetworkProxy();
    }

    @Override
    public void openBrowser(String url) {
        Util.getOperatingSystem().open(url);
    }

    @Override
    public Thread getMinecraftClientThread() {
        return ((MinecraftClientAccessor) MinecraftClient.getInstance()).getThread();
    }

    @Override
    public void crash(RuntimeException e) {
        MinecraftClient.getInstance().send(() -> {
            throw e;
        });
    }

    @Override
    public void copyText(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            new Clipboard().setClipboard(client.getWindow().getHandle(), text);
        } else {
            client.send(() -> copyText(text));
        }
    }

    @Override
    public void showToast(String title, String description, Object... args) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            SystemToast.show(
                    MinecraftClient.getInstance().getToastManager(),
                    SystemToast.Type.NARRATOR_TOGGLE,
                    Text.translatable(title),
                    description == null ? null : Text.translatable(description, args)
            );
        } else {
            client.send(() -> showToast(title, description, args));
        }
    }
}
