package top.syshub.accountsx.adapters.mc.ui;

import top.syshub.accountsx.core.ui.Translator;
import net.minecraft.text.Text;

public final class I18N {
    private I18N() {
    }

    public static final Translator<Text> TRANSLATOR = new Translator<>(Text::translatable);
}
