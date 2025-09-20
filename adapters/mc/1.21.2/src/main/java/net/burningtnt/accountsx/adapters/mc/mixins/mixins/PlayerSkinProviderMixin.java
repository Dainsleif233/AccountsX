package net.burningtnt.accountsx.adapters.mc.mixins.mixins;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.burningtnt.accountsx.adapters.mc.mixins.PlayerSkinProviderAccessor;
import net.minecraft.client.texture.PlayerSkinProvider;
import net.minecraft.client.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.concurrent.Executor;

@Mixin(PlayerSkinProvider.class)
public class PlayerSkinProviderMixin implements PlayerSkinProviderAccessor {
    @Unique
    private Path accountsx$directory;

    @Unique
    private Executor accountsx$executor;

    @Inject(
            method = "<init>",
            at = @At("TAIL")
    )
    private void accountsx$init(TextureManager textureManager, Path directory, final MinecraftSessionService sessionService, final Executor executor, CallbackInfo ci) {
        accountsx$directory = directory;
        accountsx$executor = executor;
    }

    @Unique
    public Path accountsX$getDirectory() {
        return accountsx$directory;
    }

    @Unique
    public Executor accountsX$getExecutor() {
        return accountsx$executor;
    }
}
