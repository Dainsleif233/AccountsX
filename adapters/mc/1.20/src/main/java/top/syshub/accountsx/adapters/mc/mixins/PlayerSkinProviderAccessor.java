package top.syshub.accountsx.adapters.mc.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;
import net.minecraft.client.resources.SkinManager;

@Mixin(SkinManager.class)
public interface PlayerSkinProviderAccessor {
    @Accessor("skinsDirectory")
    File getSkinCacheDir();
}
