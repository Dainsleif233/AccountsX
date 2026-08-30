package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.ui.Translator;

public final class I18N {
    private I18N() {
    }

    public static final Translator<Component> TRANSLATOR = new Translator<>(Component::translatable);
}
