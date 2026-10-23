package top.syshub.accountsx.common.accounts.model.context;

public record AuthServerContext(
        String authURL, String accountURL, String sessionURL, String serviceURL,
        String name, boolean profileKeySupported
) {
    /** 兼容旧调用点：不声明档案密钥对签发能力的服务器一律走假密钥对回退。 */
    public AuthServerContext(String authURL, String accountURL, String sessionURL, String serviceURL, String name) {
        this(authURL, accountURL, sessionURL, serviceURL, name, false);
    }
}
