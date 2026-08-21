package top.syshub.accountsx.adapters.mc.ui.impl;

import top.syshub.accountsx.core.ui.Memory;
import top.syshub.accountsx.core.utils.Threading;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class DefaultMemory implements Memory {
    private final Screen loginScreen;

    public DefaultMemory(Screen loginScreen) {
        this.loginScreen = loginScreen;
    }

    private final Map<String, Object> objects = new ConcurrentHashMap<>();

    @Override
    public <T> void set(String guid, T value) {
        objects.put(guid, value);
    }

    @Override
    public <T> T get(String guid, Class<T> type) {
        return type.cast(objects.get(guid));
    }

    @Override
    @Threading.Thread(Threading.WORKER)
    public boolean isScreenClosed() {
        Threading.checkAccountWorkerThread();

        // TODO: currentScreen is not volatile.
        return Minecraft.getInstance().gui.screen() != loginScreen;
    }
}
