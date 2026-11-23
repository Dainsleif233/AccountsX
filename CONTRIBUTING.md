# 贡献指南

感谢你对 Accounts X 的关注！本文档帮助你快速上手开发和贡献。

## 环境要求

- **JDK 25**（common 编译目标 Java 17，各适配器按矩阵为 17 / 21 / 25）
- **Gradle wrapper** — 项目自带 `gradlew` / `gradlew.bat`，不要使用系统全局 Gradle
- 构建脚本默认使用国内镜像（BMCLAPI / 阿里云 / 腾讯），配置在 `gradle.properties` 的 `loom_*` 属性里

## 分支策略

- `dev` — 开发/发布分支，PR 目标

## 开发流程

1. Fork 仓库，基于 `dev` 创建特性分支
2. 修改代码，确保对应模块编译通过
3. 提交 PR 到 `dev` 分支

### 构建验证

```bash
# 改了 common 代码
./gradlew --no-daemon --stacktrace :build

# 改了某个 MC 适配器
./gradlew --no-daemon --stacktrace :adapters:mc:1.21.4:build

# 改了 authlib 适配器
./gradlew --no-daemon --stacktrace :adapters:authlib:6.0.54:jar

# 改了共享代码 / 构建脚本
./gradlew --no-daemon --stacktrace build

# 只跑校验任务
./gradlew --no-daemon --stacktrace :test :checkArchitecture :validateAdapterMatrix :checkAdapterSourceResolver
```

所有 Gradle 调用都带 `--no-daemon --stacktrace`：失败时能拿到完整堆栈，也不会在后台留下 daemon。

**不要**只改一个适配器就跑 `./gradlew build` — 那会编译全部 Loom 适配器，非常耗时。

### 官方源

需要走官方 Maven（例如国内镜像 403 / 401）时，用命令行属性覆盖，不要改 `gradle.properties`：

```bash
./gradlew --no-daemon --stacktrace build \
  -Ploom_libraries_base=https://libraries.minecraft.net/ \
  -Ploom_resources_base=https://resources.download.minecraft.net/ \
  -Ploom_version_manifests=https://piston-meta.mojang.com/mc/game/version_manifest.json \
  -Ploom_fabric_repository=https://maven.fabricmc.net/
```

### 版本号写在哪

- 第三方依赖版本 → `gradle/libs.versions.toml`
- 适配器矩阵（每个 MC 版本用哪个 authlib / Fabric API、是否混淆）→ `gradle/adapters.toml`

适配器目录下的 `build.gradle.kts` 只声明插件，不含任何版本号；新增适配器版本必须先在 `gradle/adapters.toml` 加条目，否则该子项目不会被 `include`。

MC 适配器源码按版本回落：每个版本目录只放与近邻**不同**的 Java 文件，构建期沿矩阵继承，详见 [AGENTS.md](AGENTS.md) 的「MC 适配器源码回落」。移动包路径时记得在 `source-removals.txt` 删掉旧路径。

### PR 要求

- PR 前确保 `./gradlew --no-daemon --stacktrace :build` 通过
- 按「人工测试清单」说明手测了哪些 MC 版本与哪些项（登录 / 切换 / 重启后账号仍在）；改了适配器代码必须真机手测，不得以 CI 绿灯代替
- 新 MC 版本的 PR 需说明：改了哪些文件、在哪个版本上验证过

## 测试

分三层，各自只守自己那一段：

| 层 | 跑什么 | 命令 | 覆盖 |
|----|--------|------|------|
| 单元测试 | common + authlib 的纯 JUnit（假 HttpGateway / 临时目录，不起游戏） | `./gradlew --no-daemon --stacktrace test` | 仓储对账、迁移、令牌复用三态、认证流程、只读降级 |
| 客户端游戏测试 | 真起一个 Minecraft 客户端，点界面、断言控件与 `getUser()` | `./gradlew --no-daemon --stacktrace :adapters:mc:<版本>:runClientGameTest` | 7 个版本：1.21.4 / 1.21.6 / 1.21.9 / 1.21.11 / 26.1 / 26.2 / 26.3 |
| 人工 | 自动化够不到的出口 | 见下方清单 | 微软登录、真实外置服务器、浏览器、多实例、老 6 版本、视觉 |

客户端游戏测试的源码共享在 `src/client-gametest/`，由 `gradle/adapters.toml` 的 `clientGametest = true` 决定哪些版本接入。每个版本跑两组：

- `runClientGameTest` — 标题屏入口、离线账号全生命周期、外置服务器与账号全生命周期、服务器移位（内置假 Yggdrasil 服务器，校验协议与落盘）。
- `runClientAuthlibInjectorGameTest` — 挂 authlib-injector agent 再跑一遍外置全流程，验证兼容（首次运行会从 GitHub 下载 agent jar）。

截图与日志落在 `adapters/mc/<版本>/build/run/<run 名>/` 下。**1.20–1.21.2 这 6 个版本没有客户端游戏测试 API**（Fabric 只给它们服务端 gametest，而本模组是纯客户端），只能人工测。

CI：PR 跑 3 个代表版本（1.21.4 混淆 / 26.1 首个非混淆 / 26.3 最新），nightly 扫全 7 个（`.github/workflows/gametest-nightly.yml`）。Linux runner 上由 CI 显式用 `xvfb-run -a -s "-screen 0 1280x720x24"` 包 Gradle（装 `xvfb libgl1-mesa-dri libegl1 libegl-mesa0`，env `SDL_VIDEO_FORCE_EGL=1` + `LIBGL_ALWAYS_SOFTWARE=1`），buildSrc 已关掉 Loom 自动 xvfb 以免双层嵌套；断言是控件级而非像素级，软件渲染不会抖。

### 人工测试清单（按改动范围）

自动化不能代替手测。改了哪一块，就在 PR 里写明手测了对应项：

- **改 `adapters/mc/<版本>/`**：该版本真机启动 → 标题屏入口图标位置正确 → 打开账户列表 → 添加/切换/删除账号 → 重启后账号仍在。CI 绿灯**不算**手测。
- **改 common**：至少 1 个混淆版本（如 1.21.4）+ 1 个非混淆版本（如 26.3）各跑一遍上述流程。
- **改 auth / storage**：追加真实外置服务器的登录与刷新（含重启后复用令牌）。
- **改 sync（多实例）**：追加双客户端同时运行，验证令牌共享、删除保护、对端变更同步。
- **改登录界面文案 / 布局**：附截图。
- **微软账号**：设备码登录要真人输码，任何改动涉及微软流程都需真机走一遍。
- **打开浏览器 / 复制剪贴板**：浏览器是 OS 出口，自动化观测不到，需手点确认。
- **拖拽加服（26.3+）**：从浏览器把 authlib-injector 规范链接拖进游戏窗口，确认后台加服并弹 toast。SDL 文本事件路由自动化模拟不了（TestInput 无 drop-text），需真机拖一次；解析与后台加服接线已由游戏测试覆盖。

## Commit 规范

```
<type>(<scope>): 中文摘要
```

| type       | 含义               |
|------------|--------------------|
| `feat`     | 新功能             |
| `fix`      | 修复 bug           |
| `refactor` | 重构               |
| `chore`    | 构建/工具/配置变更 |
| `docs`     | 文档               |
| `style`    | 代码格式           |
| `perf`     | 性能优化           |
| `test`     | 测试               |

scope 参照模块名：`build`、`ci`、`common`、`storage`、`auth`、`ui`、`mc`、`authlib`、`docs`、`test`

先 `git log --oneline -20` 查看历史提交，保持风格一致。

## 安全

- **不要**提交 `~/.config/accountsx.json`、`~/.accountsx/` 或任何含 token 的文件
- **不要**在代码、日志或提交信息中暴露 access token
- 测试 fixture 使用 `fake-` 前缀

## 项目结构

详见 [AGENTS.md](AGENTS.md) 的项目布局章节。
