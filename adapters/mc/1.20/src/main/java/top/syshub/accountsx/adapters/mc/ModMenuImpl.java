package top.syshub.accountsx.adapters.mc;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import java.util.Map;

public class ModMenuImpl implements ModMenuApi {
    @Override
    public Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
        return Map.of("accountsx", AccountScreen::new);
    }
}
