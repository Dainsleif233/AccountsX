package top.syshub.accountsx.adapters.mc.mixins.mixins;

import net.minecraft.client.User;
import net.minecraft.client.resources.SplashManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SplashManager.class)
public interface SplashTextResourceSupplierAccessor {
    @Accessor("user")
    @Mutable
    void setSession(User session);
}
