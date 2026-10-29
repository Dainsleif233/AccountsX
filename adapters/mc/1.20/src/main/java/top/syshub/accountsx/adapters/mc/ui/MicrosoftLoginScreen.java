package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.task.TaskScheduler;

/**
 * 微软账号登录界面（device-code OAuth）。
 *
 * <p>同样分两步：先取登录码展示并复制，再打开浏览器轮询 token。端点与 client_id 是编译期常量，
 * 没有可配置项。关闭窗口即取消。</p>
 */
public class MicrosoftLoginScreen extends LoginScreen {
    private static final int HALF_W = (INPUT_W - PAD) / 2;

    private final MicrosoftAccountProvider provider;

    private MicrosoftAccountProvider.DeviceCode deviceCode;

    public MicrosoftLoginScreen(Screen parent) {
        super(parent, "accountsx.account.general.login",
                Component.translatable("accountsx.account.type.microsoft.name"));
        this.provider = (MicrosoftAccountProvider) AccountType.MICROSOFT.getAccountProvider();
    }

    @Override
    protected void updateWidgets() {
        this.clearWidgets();

        int x = this.contentX();
        int y = this.contentTop();

        if (this.deviceCode == null) {
            ButtonWidget fetch = new ButtonWidget(x, y, INPUT_W, BTN_H,
                    Component.translatable("accountsx.account.external.fetch_code"),
                    button -> this.fetchCode());
            fetch.active = !this.busy;
            this.addField(fetch);
        } else {
            ButtonWidget open = new ButtonWidget(x, y, HALF_W, BTN_H,
                    Component.translatable("accountsx.account.external.open_browser"),
                    button -> this.loginDeviceCode());
            open.active = !this.busy;
            this.addField(open);
            ButtonWidget copy = new ButtonWidget(x + HALF_W + PAD, y, INPUT_W - HALF_W - PAD, BTN_H,
                    Component.translatable("accountsx.account.external.copy_link"),
                    button -> this.copyLink());
            copy.active = !this.busy;
            this.addField(copy);
        }

        this.addBackButton();
    }

    private void fetchCode() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.status = Component.translatable("accountsx.account.external.code.generating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            MicrosoftAccountProvider.DeviceCode code = null;
            Component failure = null;
            try {
                code = this.provider.requestDeviceCode();
            } catch (Exception e) {
                failure = failText(e);
            }

            MicrosoftAccountProvider.DeviceCode result = code;
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
                if (result == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.deviceCode = result;
                this.status = null;
                // 登录码是给人看的短码，复制进剪贴板省一次手抄。
                Platforms.getMinecraftPlatform().copyText(result.userCode());
                this.updateWidgets();
            });
        });
    }

    private void loginDeviceCode() {
        MicrosoftAccountProvider.DeviceCode code = this.deviceCode;
        if (this.busy || code == null) {
            return;
        }
        Platforms.getMinecraftPlatform().openBrowser(code.verificationUri());

        this.busy = true;
        this.status = Component.translatable("accountsx.account.general.operating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            BaseAccount account = null;
            Component failure = null;
            try {
                account = this.provider.loginDeviceCode(code, () -> this.closed || rid != this.requestId);
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

    private void copyLink() {
        MicrosoftAccountProvider.DeviceCode code = this.deviceCode;
        if (code != null) {
            Platforms.getMinecraftPlatform().copyText(code.verificationUri());
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        this.drawHeader(context);
        super.render(context, mouseX, mouseY, delta);

        MicrosoftAccountProvider.DeviceCode code = this.deviceCode;
        if (code != null) {
            // 只画短码，地址交给下面两个按钮——地址挤进来会被截断，还和外置那屏的写法不一致。
            this.center(context, Component.translatable("accountsx.account.external.code", code.userCode()),
                    hintBelow(this.contentTop() + BTN_H), 0xFFE0E0E0);
        }

        this.drawStatus(context);
    }
}
