package top.syshub.accountsx.adapters.mc.mixins;

import net.minecraft.client.texture.PlayerSkinTextureDownloader;
import org.spongepowered.asm.mixin.Unique;

import java.nio.file.Path;
import java.util.concurrent.Executor;

public interface PlayerSkinProviderAccessor {
    @Unique
    Path accountsX$getDirectory();

    PlayerSkinTextureDownloader accountsX$getDownloader();

    Executor accountsX$getExecutor();
}
