package net.burningtnt.accountsx.adapters.mc.ui.impl;

import net.burningtnt.accountsx.adapters.mc.ui.AccountScreen;
import net.burningtnt.accountsx.adapters.mc.ui.ButtonWidget;
import net.burningtnt.accountsx.core.AccountsX;
import net.burningtnt.accountsx.core.accounts.AccountProvider;
import net.burningtnt.accountsx.core.accounts.BaseAccount;
import net.burningtnt.accountsx.core.manager.AccountManager;
import net.burningtnt.accountsx.core.manager.AccountWorker;
import net.burningtnt.accountsx.core.ui.Memory;
import net.burningtnt.accountsx.core.ui.UIScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UIScreenImpl implements UIScreen {
    public static void login(MinecraftClient client, AccountScreen accountScreen, AccountProvider<?> provider) {
        UIScreenImpl screen = new UIScreenImpl();
        provider.configure(screen);

        client.setScreen(screen.bind(accountScreen, provider));
    }

    private static final class ValuedWidget<T extends Drawable> {
        private final String description;

        private T widget;

        public ValuedWidget(String description) {
            this.description = description;
        }
    }

    private boolean readonly = false;

    private String title;

    private final Map<String, ValuedWidget<TextFieldWidget>> inputs = new LinkedHashMap<>();

    @Override
    public void setTitle(String description) {
        if (readonly) {
            throw new IllegalStateException("UIScreen has been frozen.");
        }
        this.title = description;
    }

    @Override
    public void putTextInput(String guid, String description) {
        if (readonly) {
            throw new IllegalStateException("UIScreen has been frozen.");
        }
        this.inputs.put(guid, new ValuedWidget<>(description));
    }

    @Override
    public String getTextInput(String guid) {
        if (!readonly) {
            throw new IllegalStateException("UIScreen hasn't been frozen.");
        }
        return this.inputs.get(guid).widget.getText();
    }

    public Screen bind(AccountScreen parent, AccountProvider<?> provider) {
        readonly = true;

        return new LoginScreen(Text.translatable(this.title), parent, provider);
    }

    private final class LoginScreen extends Screen {
        private final AccountScreen parent;

        private final AccountProvider<?> provider;

        public LoginScreen(Text title, AccountScreen parent, AccountProvider<?> provider) {
            super(title);
            this.parent = parent;
            this.provider = provider;
        }

        @Override
        public void close() {
            assert this.client != null;

            this.client.setScreen(parent);
        }

        @Override
        protected void init() {
            assert this.client != null;

            super.init();

            int widgetsTop = this.height / 2 - (UIScreenImpl.this.inputs.size() + 1) * 25 / 2;
            int widgetsLeft = this.width / 2 - 50;

            for (ValuedWidget<TextFieldWidget> widget : UIScreenImpl.this.inputs.values()) {
                widget.widget = this.addField(
                        new TextFieldWidget(this.client.textRenderer, widgetsLeft, widgetsTop, 200, 20, Text.empty())
                );

                widgetsTop += 25;
            }

            boolean noInputs = UIScreenImpl.this.inputs.isEmpty();

            this.addField(new ButtonWidget(widgetsLeft, widgetsTop, noInputs ? 100 : 95, 20, Text.translatable("as.account.general.login"), widget -> {
                Memory memory = new DefaultMemory(this);

                int state;
                try {
                    state = this.provider.validate(UIScreenImpl.this, memory);
                } catch (IllegalArgumentException e) {
                    AccountsX.LOGGER.warn("Invalid account argument.", e);
                    return;
                }

                switch (state) {
                    case AccountProvider.STATE_IMMEDIATE_CLOSE -> this.close();
                    case AccountProvider.STATE_HANDLE -> {
                    }
                    default -> throw new IllegalArgumentException("Unknown state: " + state);
                }

                AccountWorker.submit(() -> {
                    BaseAccount account;
                    try {
                        account = this.provider.login(memory);
                    } catch (Throwable t) {
                        this.client.send(() -> {
                            if (this.client.currentScreen == this) {
                                this.close();
                            }
                        });

                        throw t;
                    }

                    this.client.send(() -> {
                        if (this.client.currentScreen == this) {
                            this.close();
                        }

                        AccountManager.addAccount(account);

                        this.parent.syncAccounts();
                    });
                });
            }));

            if (noInputs) {
                this.addField(new ButtonWidget(widgetsLeft, widgetsTop + 25, 100, 20, Text.translatable("as.general.action.close"), widget -> this.close()));
            } else {
                this.addField(new ButtonWidget(widgetsLeft + 105, widgetsTop, 95, 20, Text.translatable("as.general.action.close"), widget -> this.close()));
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            assert this.client != null;

            super.renderBackground(context);
            super.render(context, mouseX, mouseY, delta);

            int textTop = this.height / 2 - (UIScreenImpl.this.inputs.size() + 1) * 25 / 2 + 5;
            int textLeft = this.width / 2 - 170;

            client.textRenderer.draw(
                    this.title,
                    (float) this.width / 2 - (float) client.textRenderer.getWidth(this.title) / 2, textTop - 40,
                    0xFFFFFF, true,
                    context.getMatrices().peek().getPositionMatrix(), context.getVertexConsumers(), TextRenderer.TextLayerType.NORMAL,
                    0, 0xF000F0
            );

            for (ValuedWidget<TextFieldWidget> widget : UIScreenImpl.this.inputs.values()) {
                client.textRenderer.draw(
                        Text.translatable(widget.description), textLeft, textTop, 0xFFFFFF, true,
                        context.getMatrices().peek().getPositionMatrix(), context.getVertexConsumers(), TextRenderer.TextLayerType.NORMAL,
                        0, 0xF000F0
                );

                textTop += 25;
            }
        }

        public <T extends ClickableWidget> T addField(T drawable) {
            this.addDrawable(drawable);
            this.addSelectableChild(drawable);
            return drawable;
        }
    }
}
