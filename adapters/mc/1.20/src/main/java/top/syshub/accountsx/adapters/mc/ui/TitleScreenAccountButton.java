package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 标题屏入口按钮的定位。
 *
 * <p>26.1 及更早的主菜单是单人游戏 / 多人游戏 / Realms 一列 200x20 的按钮，入口图标贴在多人游戏那一行的
 * 右侧。位置按运行时找到的多人游戏按钮算，别的模组挪动菜单列（如 ModMenu 的 Classic 模式压缩 Realms
 * 那一行）时图标跟着走；找不到时退回原版布局的固定坐标。
 */
public final class TitleScreenAccountButton {
    private static final int GAP = 4;

    private TitleScreenAccountButton() {
    }

    public static void placeBesideMultiplayer(Screen screen, AbstractWidget button) {
        String multiplayer = Component.translatable("menu.multiplayer").getString();
        for (GuiEventListener child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget != button
                    && multiplayer.equals(widget.getMessage().getString())) {
                button.setX(widget.getX() + widget.getWidth() + GAP);
                button.setY(widget.getY());
                return;
            }
        }
        button.setX(screen.width / 2 + 104);
        button.setY(screen.height / 4 + 72);
    }
}
