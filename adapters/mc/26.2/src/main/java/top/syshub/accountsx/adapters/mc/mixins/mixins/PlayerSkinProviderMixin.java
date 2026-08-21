package top.syshub.accountsx.adapters.mc.mixins.mixins;

import top.syshub.accountsx.adapters.mc.mixins.PlayerSkinProviderAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.server.Services;

@Mixin(SkinManager.class)
public class PlayerSkinProviderMixin implements PlayerSkinProviderAccessor {
    @Unique
    private Path accountsx$directory;

    @Unique
    private SkinTextureDownloader accountsX$downloader;

    @Unique
    private Executor accountsx$executor;

    @Inject(
            method = "<init>",
            at = @At("TAIL")
    )
    private void accountsx$init(Path skinsDirectory, Services services, SkinTextureDownloader skinTextureDownloader, Executor mainThreadExecutor, CallbackInfo ci) {
        accountsx$directory = skinsDirectory;
        accountsX$downloader = skinTextureDownloader;
        accountsx$executor = mainThreadExecutor;
    }

    @Unique
    public Path accountsX$getDirectory() {
        return accountsx$directory;
    }

    @Unique
    public SkinTextureDownloader accountsX$getDownloader() {
        return accountsX$downloader;
    }

    @Unique
    public Executor accountsX$getExecutor() {
        return accountsx$executor;
    }
}
