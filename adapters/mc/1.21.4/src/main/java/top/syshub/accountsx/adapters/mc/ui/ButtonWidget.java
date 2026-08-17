package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.network.chat.Component;

public class ButtonWidget extends net.minecraft.client.gui.components.Button {
    public ButtonWidget(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, textSupplier -> Component.empty());
    }
}
