package net.burningtnt.accountsx.adapters.mc.mixins.mixins;

import net.minecraft.client.resource.SplashTextResourceSupplier;
import net.minecraft.client.session.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SplashTextResourceSupplier.class)
public interface SplashTextResourceSupplierAccessor {
    @Accessor("session")
    @Mutable
    void setSession(Session session);
}
