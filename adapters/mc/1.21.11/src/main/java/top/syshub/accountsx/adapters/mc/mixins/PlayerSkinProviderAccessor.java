package top.syshub.accountsx.adapters.mc.mixins;

import org.spongepowered.asm.mixin.Unique;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;

public interface PlayerSkinProviderAccessor {
    @Unique
    Path accountsX$getDirectory();

    SkinTextureDownloader accountsX$getDownloader();

    Executor accountsX$getExecutor();
}
