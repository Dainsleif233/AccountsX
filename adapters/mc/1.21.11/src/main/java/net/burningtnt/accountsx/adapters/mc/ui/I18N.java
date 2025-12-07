package net.burningtnt.accountsx.adapters.mc.ui;

import net.burningtnt.accountsx.core.ui.Translator;
import net.minecraft.text.Text;

public final class I18N {
    private I18N() {
    }

    public static final Translator<Text> TRANSLATOR = new Translator<>(Text::translatable);
}
