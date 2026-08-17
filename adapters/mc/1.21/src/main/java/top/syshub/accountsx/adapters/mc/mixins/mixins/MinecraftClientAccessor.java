package top.syshub.accountsx.adapters.mc.mixins.mixins;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.client.telemetry.ClientTelemetryManager;

@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
    @Mutable
    @Accessor("authenticationService")
    void setAuthenticationService(YggdrasilAuthenticationService value);

    @Mutable
    @Accessor("minecraftSessionService")
    void setSessionService(MinecraftSessionService service);

    @Mutable
    @Accessor("user")
    void setSession(User session);

    @Mutable
    @Accessor("profileFuture")
    void setGameProfileFuture(CompletableFuture<ProfileResult> result);

    @Mutable
    @Accessor("userApiService")
    void setUserAPIService(UserApiService service);

    @Mutable
    @Accessor("userPropertiesFuture")
    void setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> properties);

    @Mutable
    @Accessor("playerSocialManager")
    void setSocialInteractionManager(PlayerSocialManager manager);

    @Mutable
    @Accessor("skinManager")
    void setSkinProvider(SkinManager skinProvider);

    @Mutable
    @Accessor("profileKeyPairManager")
    void setProfileKeys(ProfileKeyPairManager value);

    @Mutable
    @Accessor("reportingContext")
    void setAbuseReportContext(ReportingContext value);

    @Mutable
    @Accessor("telemetryManager")
    void setTelemetryManager(ClientTelemetryManager value);

    @Accessor("gameThread")
    Thread getThread();
}
