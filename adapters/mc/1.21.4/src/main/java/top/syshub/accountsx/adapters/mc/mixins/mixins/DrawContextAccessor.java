package top.syshub.accountsx.adapters.mc.mixins.mixins;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiGraphics.class)
public interface DrawContextAccessor {
    @Accessor("bufferSource")
    MultiBufferSource.BufferSource getVertexConsumerProvider();
}
