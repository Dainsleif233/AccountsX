package top.syshub.accountsx.core;

import top.syshub.accountsx.core.accounts.impl.microsoft.MicrosoftConstants;
import top.syshub.accountsx.core.manager.AccountManager;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AccountsX implements ClientModInitializer {
    public static final String MC_ADAPTER_ID = "accountsx-adapter-mc";
    public static final String AUTHLIB_ADAPTER_ID = "accountsx-adapter-authlib";
    public static final String MOD_ID = "accountsx";
    public static final String MOD_NAME = "Accounts X";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        AccountManager.initialize();
        MicrosoftConstants.initialize();
    }
}
