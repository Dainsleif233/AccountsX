package top.syshub.accountsx.common.accounts.model.context;

public record AccountContext(
        AuthServerContext server, AuthSecurityContext security, AuthPolicy policy
) {
}
