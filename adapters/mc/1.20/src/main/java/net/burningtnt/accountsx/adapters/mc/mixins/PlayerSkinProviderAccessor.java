package net.burningtnt.accountsx.adapters.mc.mixins;

import net.minecraft.client.texture.PlayerSkinProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.io.File;

@Mixin(PlayerSkinProvider.class)
public interface PlayerSkinProviderAccessor {
    @Accessor("skinCacheDir")
    File getSkinCacheDir();
}
