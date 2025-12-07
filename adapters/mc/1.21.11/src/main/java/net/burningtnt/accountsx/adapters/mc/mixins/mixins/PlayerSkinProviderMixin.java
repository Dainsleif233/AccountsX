package net.burningtnt.accountsx.adapters.mc.mixins.mixins;

import net.burningtnt.accountsx.adapters.mc.mixins.PlayerSkinProviderAccessor;
import net.minecraft.client.texture.PlayerSkinProvider;
import net.minecraft.client.texture.PlayerSkinTextureDownloader;
import net.minecraft.util.ApiServices;
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
    private PlayerSkinTextureDownloader accountsX$downloader;

    @Unique
    private Executor accountsx$executor;

    @Inject(
            method = "<init>",
            at = @At("TAIL")
    )
    private void accountsx$init(Path cacheDirectory, ApiServices apiServices, PlayerSkinTextureDownloader downloader, Executor executor, CallbackInfo ci) {
        accountsx$directory = cacheDirectory;
        accountsX$downloader = downloader;
        accountsx$executor = executor;
    }

    @Unique
    public Path accountsX$getDirectory() {
        return accountsx$directory;
    }

    @Unique
    public PlayerSkinTextureDownloader accountsX$getDownloader() {
        return accountsX$downloader;
    }

    @Unique
    public Executor accountsX$getExecutor() {
        return accountsx$executor;
    }
}
