package top.syshub.accountsx.common.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;

import java.util.UUID;

/**
 * 账号的跨实例稳定标识。
 *
 * <p>同一账号在两个进程里是不同的 Java 对象、甚至可能没有相同的内存地址，
 * 但必须映射到同一个 key，否则租约与合并写都无从对账。规则沿用 3→4 迁移的去重键：
 * 类型 + 玩家 UUID，外置账号再加 apiRoot（同名玩家在不同服务器是不同账号）。</p>
 *
 * <p>形如 {@code offline:<uuid32>} / {@code microsoft:<uuid32>} /
 * {@code yggdrasil:<uuid32>#<apiRoot>}。环境账号不落盘，返回 {@code null}。</p>
 */
public final class AccountKey {
    private AccountKey() {
    }

    public static String of(BaseAccount account) {
        if (account == null) {
            return null;
        }
        String prefix = account.getAccountType().getKind();
        if (prefix == null) {
            // ENV_DEFAULT：由启动器提供，永不落盘，也就没有跨实例身份
            return null;
        }
        String uuid = uuid32(account.getAccountStorage().getPlayerUUID());
        if (uuid == null) {
            return null;
        }
        String base = prefix + ":" + uuid;
        if (account instanceof AbstractInjectorAccount injector && injector.getServer() != null) {
            return base + "#" + injector.getServer();
        }
        return base;
    }

    /** 磁盘条目的 key；认不出来（未知 type / 缺 uuid）返回 null，调用方按"原样保留"处理。 */
    public static String of(JsonObject entry) {
        if (entry == null) {
            return null;
        }
        String prefix = prefixOf(str(entry.get("type")));
        if (prefix == null) {
            return null;
        }
        JsonObject profile = entry.get("profile") instanceof JsonObject p ? p : null;
        String uuid = profile == null ? null : uuid32(str(profile.get("uuid")));
        if (uuid == null) {
            return null;
        }
        String base = prefix + ":" + uuid;
        String server = str(entry.get("server"));
        return server == null ? base : base + "#" + server;
    }

    /** v4 短 kind 与旧 configId 双认（与 {@code AccountRepository.fromJson} 同口径）。 */
    private static String prefixOf(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "offline" -> "offline";
            case "microsoft" -> "microsoft";
            case "yggdrasil", "injector.authlib-injector" -> "yggdrasil";
            default -> null;
        };
    }

    /** 无横线、小写：v4 存的就是这个形状，比较前统一。 */
    static String uuid32(UUID uuid) {
        return uuid == null ? null : uuid.toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    static String uuid32(String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return hex.length() == 32 ? hex : null;
    }

    private static String str(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }
}
