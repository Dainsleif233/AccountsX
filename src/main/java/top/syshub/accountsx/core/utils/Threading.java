package top.syshub.accountsx.core.utils;

import top.syshub.accountsx.core.adapters.Adapters;
import top.syshub.accountsx.core.task.TaskScheduler;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class Threading {
    /**
     * 线程角色。CLIENT 与 WORKER 是不同的枚举常量（1.3 修复：原先两者都是字符串
     * "Account Worker"，导致 {@link Thread} 注解无法区分线程语义）。
     */
    public enum ThreadRole {
        CLIENT,
        WORKER
    }

    private Threading() {
    }

    public static void checkMinecraftClientThread() {
        if (Adapters.getMinecraftAdapter().getMinecraftClientThread() != java.lang.Thread.currentThread()) {
            throw new IllegalStateException("Should in Minecraft Client Thread.");
        }
    }

    public static void checkAccountWorkerThread() {
        if (!TaskScheduler.isWorkerThread(java.lang.Thread.currentThread())) {
            throw new IllegalStateException("Should in Account Worker Thread.");
        }
    }

    @Documented
    @Retention(RetentionPolicy.CLASS)
    public @interface Thread {
        ThreadRole value();
    }
}
