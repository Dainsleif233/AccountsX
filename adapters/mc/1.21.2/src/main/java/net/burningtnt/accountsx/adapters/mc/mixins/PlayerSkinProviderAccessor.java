package net.burningtnt.accountsx.adapters.mc.mixins;

import org.spongepowered.asm.mixin.Unique;

import java.nio.file.Path;
import java.util.concurrent.Executor;

public interface PlayerSkinProviderAccessor {
    @Unique
    Path accountsX$getDirectory();

    Executor accountsX$getExecutor();
}
