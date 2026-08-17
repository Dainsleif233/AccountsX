package top.syshub.accountsx.adapters.mc.mixins;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

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
    @Accessor("userApiService")
    void setUserAPIService(UserApiService service);

    @Mutable
    @Accessor("playerSocialManager")
    void setSocialInteractionManager(PlayerSocialManager manager);

    @Mutable
    @Accessor("skinManager")
    void setSkinProvider(SkinManager skinProvider);

    @Mutable
    @Accessor("profileKeyPairManager")
    void setProfileKeys(ProfileKeyPairManager value);

    @Accessor("gameThread")
    Thread getThread();

    @Accessor("profileProperties")
    PropertyMap getSessionPropertyMap();
}
