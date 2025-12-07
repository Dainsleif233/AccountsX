package net.burningtnt.accountsx.core.utils;

import net.burningtnt.accountsx.core.adapters.Adapters;
import net.burningtnt.accountsx.core.manager.AccountWorker;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class Threading {
    public static final String WORKER = "Account Worker";
    public static final String CLIENT = "Account Worker";

    private Threading() {
    }

    public static void checkMinecraftClientThread() {
        if (Adapters.getMinecraftAdapter().getMinecraftClientThread() != java.lang.Thread.currentThread()) {
            throw new IllegalStateException("Should in Minecraft Client Thread.");
        }
    }

    public static void checkAccountWorkerThread() {
        if (!AccountWorker.isWorkerThread(java.lang.Thread.currentThread())) {
            throw new IllegalStateException("Should in Account Worker Thread.");
        }
    }

    @Documented
    @Retention(RetentionPolicy.CLASS)
    public @interface Thread {
        String value();
    }
}
