package top.syshub.accountsx.adapters.mc.ui;

public class ButtonWidget extends net.minecraft.client.gui.components.Button.Plain {
    public ButtonWidget(int x, int y, int width, int height, net.minecraft.network.chat.Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, textSupplier -> net.minecraft.network.chat.Component.empty());
    }
}
