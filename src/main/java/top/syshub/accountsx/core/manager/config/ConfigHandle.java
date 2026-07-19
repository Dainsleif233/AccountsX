package top.syshub.accountsx.core.manager.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import top.syshub.accountsx.core.AccountsX;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.manager.AccountManager;
import top.syshub.accountsx.core.utils.NetworkUtils;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConfigHandle {
    private ConfigHandle() {}

    private static String id;

    private static final String CONFIG_LOCATION = "accountsx/accounts.json";

    private static final class Config {
        public static final int CURRENT_VERSION = ConfigVersion.VALUES[ConfigVersion.VALUES.length - 1].getVersion();

        private final int version;

        private final String id;

        private Config(List<BaseAccount> accounts) {
            this.version = CURRENT_VERSION;
            if (ConfigHandle.id == null)
                ConfigHandle.id = UUID.randomUUID().toString();

            id = ConfigHandle.id;
            writeAccounts(id, NetworkUtils.GSON.toJson(accounts));
        }
    }

    public static List<? extends BaseAccount> load() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_LOCATION);

        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(configFile.getParent());
                Files.writeString(configFile, NetworkUtils.GSON.toJson(new Config(List.of())));
                return List.of();
            }

            if (!Files.isRegularFile(configFile)) {
                Files.delete(configFile);
                Files.writeString(configFile, NetworkUtils.GSON.toJson(new Config(List.of())));
                return List.of();
            }

            JsonElement data;
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                data = NetworkUtils.GSON.fromJson(reader, JsonElement.class);
            }

            if (data instanceof JsonObject jo) {
                if (jo.get("version") instanceof JsonPrimitive versionJP && versionJP.isNumber()) {
                    int configVersion = versionJP.getAsNumber().intValue();

                    for (ConfigVersion value : ConfigVersion.VALUES) {
                        if (configVersion < value.getVersion()) {
                            value.upgrade(jo);
                        }
                    }

                    id = jo.get("id").getAsString();
                    try {
                        UUID.fromString(id);
                    } catch (Exception e) {
                        id = UUID.randomUUID().toString();
                    }
                    return getAccounts();
                }
            }

            throw new IllegalStateException("Illegal config.");
        } catch (Throwable t) {
            AccountsX.LOGGER.warn("Cannot load the config file.", t);
            return List.of();
        }
    }

    public static void write() throws IOException {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_LOCATION);

        List<BaseAccount> accounts = new ArrayList<>();

        for (BaseAccount account : AccountManager.getAccountsView()) {
            if (account.getAccountType() != AccountType.ENV_DEFAULT) {
                accounts.add(account);
            }
        }

        try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
            NetworkUtils.GSON.toJson(new Config(accounts), writer);
        }
    }

    private static List<? extends BaseAccount> getAccounts() {
        String userHome = System.getProperty("user.home");
        Path accountsFile = Path.of(userHome, ".accountsx", id + ".json");

        try {
            if (!Files.exists(accountsFile) || !Files.isRegularFile(accountsFile))
                return List.of();

            try (Reader reader = Files.newBufferedReader(accountsFile, StandardCharsets.UTF_8)) {
                return NetworkUtils.GSON.fromJson(
                        reader,
                        new TypeToken<List<? extends BaseAccount>>() {}.getType()
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load accounts file", e);
        }
    }

    static void writeAccounts(String id, String accountString) {
        String userHome = System.getProperty("user.home");
        Path accountsFile = Path.of(userHome, ".accountsx", id + ".json");

        try {
            Files.createDirectories(accountsFile.getParent());
            Files.writeString(accountsFile, accountString);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write accounts file", e);
        }
    }
}
