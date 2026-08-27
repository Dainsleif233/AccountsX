package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import top.syshub.accountsx.adapters.mc.ui.impl.UIScreenImpl;
import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.manager.AccountManager;
import top.syshub.accountsx.core.task.TaskScheduler;

public class AccountScreen extends Screen {
    private static final int LAYOUT_HORIZONTAL_SPACING = 16;
    private static final int LAYOUT_VERTICAL_SPACING = 32;

    private static final int LAYOUT_BUTTON_H = 20;

    private static final int LAYOUT_TOOL_BAR_W = 150;
    private static final int LAYOUT_TOOL_BAR_SPACING = 20;
    private static final int LAYOUT_TOOL_BAR_TEXT_CENTER_X = LAYOUT_HORIZONTAL_SPACING + LAYOUT_TOOL_BAR_W / 2;
    private static final int LAYOUT_TOOL_BAR_ADD_ACCOUNT_Y = LAYOUT_VERTICAL_SPACING + LAYOUT_BUTTON_H + LAYOUT_BUTTON_H;

    private static final int LAYOUT_ENTRY_X = LAYOUT_HORIZONTAL_SPACING + LAYOUT_TOOL_BAR_W + LAYOUT_TOOL_BAR_SPACING / 2 + 10;
    private static final int LAYOUT_ENTRY_H = 36;

    private final Component WORKING = Component.translatable("accountsx.account.general.operating");
    private final Component ACCOUNT_LIST = Component.translatable("accountsx.account.general.account_list");

    private final Screen parent;
    private AccountListWidget accountListWidget;

    public AccountScreen(Screen parent) {
        super(Component.translatable("accountsx.account.general.add_account"));
        this.parent = parent;
    }

    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    public void syncAccounts() {
        this.accountListWidget.syncAccounts();
    }

    @Override
    protected void init() {
        super.init();
        if (this.accountListWidget != null) {
            this.accountListWidget.updateSize(
                    LAYOUT_ENTRY_X, this.width - LAYOUT_HORIZONTAL_SPACING,
                    LAYOUT_VERTICAL_SPACING + 20, this.height - LAYOUT_VERTICAL_SPACING - 20
            );
        } else {
            this.accountListWidget = new AccountListWidget(this.minecraft,
                    LAYOUT_ENTRY_X, this.width - LAYOUT_HORIZONTAL_SPACING,
                    LAYOUT_VERTICAL_SPACING + 20, this.height - LAYOUT_VERTICAL_SPACING - 20,
                    LAYOUT_ENTRY_H
            );
        }

        this.addWidget(this.accountListWidget);

        this.addField(new ButtonWidget(
                LAYOUT_HORIZONTAL_SPACING, LAYOUT_VERTICAL_SPACING,
                LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()
        ));

        int y = LAYOUT_TOOL_BAR_ADD_ACCOUNT_Y + 10;
        for (AccountType type : AccountType.CONFIGURABLE_VALUES) {
            this.addField(new ButtonWidget(
                    LAYOUT_HORIZONTAL_SPACING, y,
                    LAYOUT_TOOL_BAR_W, LAYOUT_BUTTON_H,
                    I18N.TRANSLATOR.translate(type),
                    button -> UIScreenImpl.login(this.minecraft, this, type.getAccountProvider())
            ));

            y += LAYOUT_BUTTON_H;
        }
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        this.accountListWidget.extractRenderState(context, mouseX, mouseY, delta);

        context.centeredText(this.font, TaskScheduler.isRunning() ? WORKING : ACCOUNT_LIST, this.width / 2 + LAYOUT_ENTRY_X / 2, LAYOUT_VERTICAL_SPACING, 0xFFFFFFFF);
        context.centeredText(this.font, I18N.TRANSLATOR.translate(AccountManager.getCurrentAccount()), this.width / 2 + LAYOUT_ENTRY_X / 2, this.height - LAYOUT_VERTICAL_SPACING, 0xFFFFFFFF);

        context.centeredText(
                this.font, Component.translatable("accountsx.account.general.add_account"),
                LAYOUT_TOOL_BAR_TEXT_CENTER_X, LAYOUT_TOOL_BAR_ADD_ACCOUNT_Y,
                0xFFFFFFFF
        );
    }

    public void addField(AbstractWidget drawable) {
        this.addRenderableOnly(drawable);
        this.addWidget(drawable);
    }
}
