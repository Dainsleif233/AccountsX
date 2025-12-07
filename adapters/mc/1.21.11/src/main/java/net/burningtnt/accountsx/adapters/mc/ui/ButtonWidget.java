package net.burningtnt.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.widget.ButtonWidget.Text;

public class ButtonWidget extends Text {
    public ButtonWidget(int x, int y, int width, int height, net.minecraft.text.Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, textSupplier -> net.minecraft.text.Text.empty());
    }
}
