package net.burningtnt.accountsx.adapters.mc.mixins;

import java.nio.file.Path;
import java.util.concurrent.Executor;

public interface PlayerSkinProviderAccessor {
    Path accountsX$getDirectory();

    Executor accountsX$getExecutor();
}
