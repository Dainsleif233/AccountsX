package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.task.TaskScheduler;

/**
 * 离线账号登录界面。
 *
 * <p>玩家名必填，UUID 可留空（留空时按玩家名推导）。校验与建号都在 provider 里，这里只负责收集输入。</p>
 */
public class OfflineLoginScreen extends LoginScreen {
    private final OfflineAccountProvider provider;

    private EditBox nameBox;
    private EditBox uuidBox;

    public OfflineLoginScreen(Screen parent) {
        super(parent, "accountsx.account.general.login",
                Component.translatable("accountsx.account.type.offline.name"));
        this.provider = (OfflineAccountProvider) AccountType.OFFLINE.getAccountProvider();
    }

    @Override
    protected void updateWidgets() {
        this.clearWidgets();

        int x = this.contentX();
        int y = this.contentTop();

        this.nameBox = new EditBox(this.font, x, y, INPUT_W, BTN_H,
                Component.translatable("accountsx.account.objects.player_name"));
        this.nameBox.setHint(Component.translatable("accountsx.account.objects.player_name"));
        this.nameBox.setMaxLength(16);
        this.addField(this.nameBox);
        y += BTN_H + PAD;

        this.uuidBox = new EditBox(this.font, x, y, INPUT_W, BTN_H,
                Component.translatable("accountsx.account.objects.player_uuid"));
        this.uuidBox.setHint(Component.translatable("accountsx.account.objects.player_uuid"));
        this.uuidBox.setMaxLength(36);
        this.addField(this.uuidBox);
        y += BTN_H + PAD;

        ButtonWidget login = new ButtonWidget(x, y, INPUT_W, BTN_H,
                Component.translatable("accountsx.account.general.login"),
                button -> this.login());
        login.active = !this.busy;
        this.addField(login);

        this.addBackButton();
    }

    private void login() {
        EditBox nameInput = this.nameBox;
        EditBox uuidInput = this.uuidBox;
        if (this.busy || nameInput == null || uuidInput == null) {
            return;
        }
        String name = nameInput.getValue().trim();
        if (name.isEmpty()) {
            this.status = Component.translatable("accountsx.account.external.error.player_name");
            return;
        }
        String uuid = uuidInput.getValue().trim();

        this.busy = true;
        this.status = Component.translatable("accountsx.account.general.operating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            BaseAccount account = null;
            Component failure = null;
            try {
                account = this.provider.login(name, uuid);
            } catch (Exception e) {
                failure = failText(e);
            }

            BaseAccount created = account;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (created == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.finish(created);
            });
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        this.drawHeader(context);
        super.extractRenderState(context, mouseX, mouseY, delta);
        this.drawStatus(context);
    }
}
