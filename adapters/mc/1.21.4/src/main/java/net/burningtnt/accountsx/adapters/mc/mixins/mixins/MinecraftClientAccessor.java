package net.burningtnt.accountsx.adapters.mc.mixins.mixins;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.client.session.report.AbuseReportContext;
import net.minecraft.client.session.telemetry.TelemetryManager;
import net.minecraft.client.texture.PlayerSkinProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Mutable
    @Accessor("authenticationService")
    void setAuthenticationService(YggdrasilAuthenticationService value);

    @Mutable
    @Accessor("sessionService")
    void setSessionService(MinecraftSessionService service);

    @Mutable
    @Accessor("session")
    void setSession(Session session);

    @Mutable
    @Accessor("gameProfileFuture")
    void setGameProfileFuture(CompletableFuture<ProfileResult> result);

    @Mutable
    @Accessor("userApiService")
    void setUserAPIService(UserApiService service);

    @Mutable
    @Accessor("userPropertiesFuture")
    void setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> properties);

    @Mutable
    @Accessor("socialInteractionsManager")
    void setSocialInteractionManager(SocialInteractionsManager manager);

    @Mutable
    @Accessor("skinProvider")
    void setSkinProvider(PlayerSkinProvider skinProvider);

    @Mutable
    @Accessor("profileKeys")
    void setProfileKeys(ProfileKeys value);

    @Mutable
    @Accessor("abuseReportContext")
    void setAbuseReportContext(AbuseReportContext value);

    @Mutable
    @Accessor("telemetryManager")
    void setTelemetryManager(TelemetryManager value);

    @Accessor("thread")
    Thread getThread();
}
