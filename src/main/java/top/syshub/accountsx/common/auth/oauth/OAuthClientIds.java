package top.syshub.accountsx.common.auth.oauth;

import java.util.Map;

/** 内置 OAuth client_id 表：键是规范化后的 apiRoot（见 ApiRootKey.of），值是服务器注册的 client_id。 */
public final class OAuthClientIds {
    public static final Map<String, String> BY_API_ROOT = Map.of(
            "https://skin.jsumc.fun/api/yggdrasil", "3",
            "https://skin.mualliance.ltd/api/yggdrasil", "28",
            "https://littleskin.cn/api/yggdrasil", "1214",
            "https://mcskin.ecustvr.top/api/yggdrasil", "6"
    );
}
