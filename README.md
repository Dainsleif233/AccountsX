# Accounts X

Minecraft 多账号切换 Fabric 模组（Forge/NeoForge请使用 [Sinytra Connector](https://modrinth.com/mod/connector)）。  
支持在游戏内快速切换不同账号，无需重启客户端。  
支持的游戏版本：1.20 - 26.3。

## 功能

- **环境账号** — 使用启动器提供的当前会话
- **离线账号** — 自定义玩家名，无需联网
- **Microsoft 账号** — 通过 device-code OAuth 登录正版
- **Authlib-Injector** — 第三方 Yggdrasil 认证服务器

## 构建

### 环境要求

- JDK 25
- Gradle

### 构建命令

```bash
./gradlew build
```

构建产物在 `build/libs/` 下。`AccountsX-<version>.jar` 是最终可分发的 universal jar。

### 国内镜像

构建脚本默认使用国内镜像（BMCLAPI / 阿里云 / 腾讯）。如需使用官方源，覆盖 Gradle 属性：

```bash
./gradlew build \
  -Ploom_libraries_base=https://libraries.minecraft.net/ \
  -Ploom_resources_base=https://resources.download.minecraft.net/ \
  -Ploom_version_manifests=https://piston-meta.mojang.com/mc/game/version_manifest.json \
  -Ploom_fabric_repository=https://maven.fabricmc.net/
```

## 配置文件

| 文件     | 位置                       | 说明             |
|----------|----------------------------|------------------|
| 全局配置 | `~/.config/accountsx.json` | 账号与认证服务器 |

> ⚠️ 配置文件包含明文 token，请勿分享或提交到版本控制。

## 提交 OAuth Client ID

OAuth Client ID 常量位于 [OAuthClientIds](https://github.com/Dainsleif233/AccountsX/blob/dev/src/main/java/top/syshub/accountsx/common/auth/oauth/OAuthClientIds.java) 的 `BY_API_ROOT`：

| 项 | 取值                     |
|----|--------------------------|
| 键 | 完整 API 根地址          |
| 值 | 服务器注册的 `client_id` |

探测顺序：常量表 → `shared_client_id` → 视为不支持 OAuth。

## 许可

[GPL-3.0](LICENSE)

## 致谢

- [IAFEnvoy](https://github.com/IAFEnvoy)
- [Burning_TNT](https://github.com/burningtnt)
- [SITMC](https://github.com/SIT-Minecraft-Axolotl-Club)
