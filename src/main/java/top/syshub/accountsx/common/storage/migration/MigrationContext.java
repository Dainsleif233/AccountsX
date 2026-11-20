package top.syshub.accountsx.common.storage.migration;

import java.nio.file.Path;

/** 迁移所需的外部数据路径，可注入内存文件系统的路径用于测试。 */
public interface MigrationContext {
    /** 旧载荷目录 {@code ~/.accountsx/}。 */
    Path legacyHomeDir();

    /** 头像缓存目录 {@code ~/.cache/accountsx/avatars/}。 */
    Path avatarCacheDir();
}
