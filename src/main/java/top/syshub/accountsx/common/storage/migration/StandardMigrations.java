package top.syshub.accountsx.common.storage.migration;

import java.util.List;

/** 生产迁移链。v0–v2 使用内联账号，v3 则索引单独的账号载荷。 */
public final class StandardMigrations {
    private StandardMigrations() {
    }

    public static MigrationChain chain() {
        return new MigrationChain(List.of(
                new InjectorSafety(),
                new RenameAccountType(),
                new UnifyAndDecouple(2),
                new UnifyAndDecouple(3)
        ));
    }
}
