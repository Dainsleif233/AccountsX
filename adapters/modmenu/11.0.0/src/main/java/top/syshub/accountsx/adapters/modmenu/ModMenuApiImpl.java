package top.syshub.accountsx.adapters.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.OptionsScreen;

public class ModMenuApiImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AccountScreen::new;
    }

    @Override
    public Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
        return Map.of("minecraft", parent -> new OptionsScreen(parent, Minecraft.getInstance().options));
    }
}
