# AGENTS.md

AI agent 和所有开发者的项目权威手册。保持与代码同步；任何约束变更必须同步更新此文件。

## 项目简介

Accounts X 是一个**客户端 Fabric 模组**，用于 Minecraft 多账号切换。支持离线账号、Microsoft（device-code OAuth）、Authlib-Injector（第三方 Yggdrasil）和 United-Injector 账号，以及启动器提供的环境账号。

- 基础包：`top.syshub.accountsx`
- Java **25**（`sourceCompatibility` / `targetCompatibility`）
- Gradle **9.7.0**（wrapper），Fabric Loom **1.17-SNAPSHOT**
- Fabric Loader：**0.19.3**（各适配器固定版本；common 使用 `compileOnly`）
- 版本号：`gradle.properties`
- 许可：GPL-3.0
- 仓库：https://github.com/Dainsleif233/AccountsX

### MC 版本支持矩阵

| Minecraft | authlib | Loom 插件         | Fabric API |
|-----------|---------|-------------------|------------|
| 1.20      | 4.0.43  | fabric-loom-remap | 0.83.0     |
| 1.20.2    | 5.0.47  | fabric-loom-remap | 0.91.6     |
| 1.20.3    | 6.0.52  | fabric-loom-remap | 0.91.1     |
| 1.20.5    | 6.0.54  | fabric-loom-remap | 0.97.8     |
| 1.21      | 6.0.54  | fabric-loom-remap | 0.102.0    |
| 1.21.2    | 6.0.54  | fabric-loom-remap | 0.106.1    |
| 1.21.4    | 6.0.54  | fabric-loom-remap | 0.118.5    |
| 1.21.6    | 6.0.54  | fabric-loom-remap | 0.128.0    |
| 1.21.9    | 7.0.61  | fabric-loom-remap | 0.134.0    |
| 1.21.11   | 7.0.61  | fabric-loom-remap | 0.139.4    |
| 26.1      | 7.0.61  | fabric-loom       | 0.145.1    |
| 26.2      | 7.0.61  | fabric-loom       | 0.158.0    |

每个 MC 适配器的 `depends.minecraft` 使用 `>=<版本>` 无上界，Fabric Loader 在所有满足的候选适配器中选版本号最大的那个。  
这是有意设计——新增更高版本 MC 时**不需要修改已有适配器的上界**。  
MC 26.1+ 是非混淆版本（无 ProGuard），Loom 没有 `remapJar` 任务，`officialMojangMappings()` 会抛异常。

上表是 `gradle/adapters.toml` 的人类可读副本；改矩阵改 toml，然后同步此表。

## 硬性不变量

| 不变量                                                                                                                                        | 为什么                                                                                                                                                                                                 | 违反后的症状                                                            | 强制机制                                                                                                    |
|-----------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| common 不得 import `net/minecraft/*` 或 `com/mojang/authlib/*`；`java/awt/*`、`javax/imageio/*` 已加入白名单（头像渲染位于 `image` 包，允许） | common 被 12 个不同 MC 版本共用编译；AWT 头像渲染已迁回 common 的 `image` 包（曾迁出到独立 `:core-image` 模块，P1.4 / 决策 D4），AWT 在 common 内白名单放行                                            | 编译失败（`checkArchitecture` 拒绝 net/minecraft / com/mojang/authlib） | `:checkArchitecture`（P0.5：net/minecraft、com/mojang/authlib 硬性禁止；java.awt/javax.imageio 白名单放行） |
| `MinecraftAdapterImpl` 类名是数据契约                                                                                                         | 写在各适配器 `fabric.mod.json` 的 `accountsx:adapter.mc.class` 里                                                                                                                                      | 12 个适配器中找不到实现，模组崩溃                                       | 人工校验 + fabric.mod.json 校验                                                                             |
| `~/.accountsx/` 下的文件含明文 token                                                                                                          | 所有日志、报错、提交都不得包含其内容                                                                                                                                                                   | token 泄露                                                              | 人工 review                                                                                                 |
| `depends.minecraft` 使用 `>=` 无上界                                                                                                          | Loader 在候选中取版本号最大者；适配器版本为 `${minecraft}-${version}`，MC 版本是最左的版本核心段（数字逐位比较，短者补 0），而 CI 注入的 `+build.N` 落在被忽略的 build 段，不会破坏排序                | 版本排序被破坏时选错适配器 → mixin 目标不存在 → 崩溃                    | `:validateAdapterMatrix`（P0.5）                                                                            |
| 载荷读失败时**不得**写回配置                                                                                                                  | `initialize()` 末尾的无条件 `save()` 会把文件覆写成 `[]`，静默丢失用户全部账号                                                                                                                         | 一次瞬时 I/O 错误永久删除所有账号                                       | P1 修复（只读降级模式）                                                                                     |
| 实例配置里的 `id` 是载荷文件的索引；账号数据在 `~/.accountsx/<id>.json`                                                                       | 实例目录可能被同步、打包、提交，明文 token 不宜放其中                                                                                                                                                  | 混淆配置与载荷的职责                                                    | 人工理解                                                                                                    |
| 26.1+ 使用 `fabric-loom`（非 `fabric-loom-remap`）                                                                                            | 非混淆版本没有 `remapJar` 任务                                                                                                                                                                         | 构建失败                                                                | `adapters.toml` 的 `obfuscated` + 插件断言                                                                  |
| `configId`（`AccountType` 枚举的第三个参数）是持久化契约                                                                                      | 写入 `accounts.json`，迁移时按此识别类型                                                                                                                                                               | 未知 type 导致整个账号集加载失败                                        | `ConfigVersion.RENAME_ACCOUNT_TYPE`                                                                         |
| i18n key 格式 `accountsx.account.type.<type 名小写>.name` / `.using`                                                                          | `Translator` 通过拼接 `AccountType.name().toLowerCase()` 生成 key（多数类型与 `configId` 相同，但 `AUTHLIB_INJECTOR` 的 enum name 是 `authlib_injector` 而 `configId` 是 `injector.authlib-injector`） | 缺译 key 显示原始 key 名                                                | `:test`（P0.4）                                                                                             |

## 项目布局

```
AccountsX（根项目 / common）
├── src/main/java/top/syshub/accountsx/
│   ├── common/
│   │   ├── AccountsX.java              # ClientModInitializer，入口
│   │   ├── accounts/                    # 账号模型 + 提供者
│   │   │   ├── AccountProvider.java     # 接口：configure / validate / login / refresh
│   │   │   ├── AccountUUID.java
│   │   │   ├── BaseAccount.java         # 基类，含嵌套 AccountStorage
│   │   │   ├── impl/env/                # 环境账号（启动器会话）
│   │   │   ├── impl/injector/           # Authlib-Injector + United-Injector
│   │   │   ├── impl/microsoft/          # Microsoft device-code OAuth
│   │   │   ├── impl/offline/            # 离线账号
│   │   │   └── model/                   # AccountType 枚举、AccountState、Auth* 上下文
│   │   ├── adapters/
│   │   │   ├── api/                     # MinecraftPlatform / AuthlibBridge / AccountSession 接口
│   │   │   ├── Adapters.java            # @Deprecated 转发壳（兼容旧适配器）
│   │   │   └── Platforms.java           # 反射加载适配器实现（memoize）
│   │   ├── manager/
│   │   │   ├── AccountManager.java      # 账号列表管理 + 刷新调度
│   │   │   ├── AccountWorker.java       # @Deprecated 转发到 TaskScheduler（兼容旧适配器）；register/unregister 已删除
│   │   │   └── config/
│   │   │       ├── ConfigHandle.java    # 配置读写（实例配置 + ~/.accountsx/ 载荷）
│   │   │       └── ConfigVersion.java   # 迁移链（当前 v0 → v3）
│   │   ├── ui/                          # 版本无关的 UIScreen / Memory / Translator
│   │   ├── net/                         # HttpGateway 接口 + JdkHttpGateway（可注入网络层，P1.3）
│   │   └── utils/                       # Threading / NetworkUtils / UnsafeVM / AvatarService（头像获取+落盘编排，无 AWT）
│   └── image/                           # 头像渲染+落盘（AvatarRenderer / AwtAvatarRenderer / AvatarCache；AWT/ImageIO 在 common 内白名单放行）
├── adapters/
│   ├── authlib/<ver>/               # authlib API 桥接（5 个版本）
│   ├── mc/<mc-ver>/                 # MC 客户端 API + UI + Mixins（12 个版本）
│   └── modmenu/<ver>/               # Mod Menu 配置入口
├── buildSrc/                        # 预编译 Gradle 插件（3 个）
└── src/main/resources/
    ├── fabric.mod.json              # 核心模组元数据
    ├── assets/accountsx/lang/       # i18n（en_us.json + zh_cn.json，41 个 key）
    └── assets/accountsx/textures/gui/  # GUI 资源单一数据源：account.png（切换图标）、
                                        #  sprites/icon/account.png（标题屏精灵）、alex_avatar.png（默认头像）
```

### Common vs Adapters

**根项目**持有所有认证流程、账号模型、持久化和工作线程。它仅 `compileOnly` 依赖 Fabric Loader / Gson / Guava / SLF4J / ASM——编译期不依赖 Minecraft 或 authlib。

运行时平台代码在 **adapters** 中，由 Fabric Loader 从已安装的适配器集中选择。Common 通过 `fabric.mod.json` 的自定义值发现实现：

| Mod ID                      | 自定义键                                           | 接口                |
|-----------------------------|----------------------------------------------------|---------------------|
| `accountsx-adapter-authlib` | `accountsx:adapter.authlib` → `{ "class": "..." }` | `AuthlibBridge`     |
| `accountsx-adapter-mc`      | `accountsx:adapter.mc` → `{ "class": "..." }`      | `MinecraftPlatform` |

`Platforms`（`common/adapters/Platforms.java`）通过反射加载两者，使用 `Suppliers.memoize` 缓存，并断言它们共享相同的 `AccountSession` 类型参数。始终通过 `Platforms.getMinecraftPlatform()` / `Platforms.authlibBridge()` 访问，不要直接引用 MC/authlib 类型。旧的 `Adapters` 类保留为 `@Deprecated` 转发壳，适配器不需改动。

Common `fabric.mod.json` 依赖全部三个适配器 mod id（`accountsx-adapter-mc`、`accountsx-adapter-authlib`、`accountsx-adapter-modmenu`），并注册 MethodHandles lookup accessor 到 `accountsx:impl-lookup-accessor` 供 `UnsafeVM` 使用。

### Universal 打包

`tasks.universal`（根 `build.gradle.kts`）复制 common jar 并将每个适配器 jar 嵌套到 `META-INF/jars/` 下，同时重写 `fabric.mod.json` 的 `jars` 字段，使一个产物包含完整的多版本适配器集。Fabric Loader 选择其 `depends` 匹配运行中游戏的适配器。  
universal 任务还会在嵌套的 common 元数据中添加 `fabric-api: *` 依赖。

### 账号模型

- `BaseAccount` + 嵌套 `AccountStorage`（token、name、UUID、state）
- `AccountType` 枚举注册 class + `AccountProvider` + configId：
  - `ENV_DEFAULT` — 当前启动器会话（不持久化）
  - `OFFLINE`（configId: `"offline"`）
  - `MICROSOFT`（configId: `"microsoft"`）— device-code OAuth
  - `AUTHLIB_INJECTOR`（configId: `"injector.authlib-injector"`）— 第三方 Yggdrasil
  - `UNITED_INJECTOR`（configId: `"injector.united"`）— 联合通行证
- Provider 实现 `configure`（UI 配置）/ `validate`（校验输入）/ `login` / `refresh` / `createAccountContext`
- 登录上下文是 `AccountContext(AuthServerContext, AuthSecurityContext, AuthPolicy)`，用于构建 authlib session

### 运行时流程

1. `AccountsX.onInitializeClient` → `AccountManager.initialize` + `MicrosoftConstants.initialize`
2. Manager 用环境账号 + `ConfigHandle.load()` 填充列表，在 worker 上刷新非授权账号
3. UI（各 MC 版本的 `AccountScreen`）在**客户端线程**上修改账号
4. 登录/刷新/保存在 **`TaskScheduler`**（`common/task/`：串行阻塞队列 + 上限 4 的有界并行池）上运行；并行刷新由 `TaskScheduler.runParallel` 调度，不再用全局注册表登记 worker 线程
5. `loginAccount` → authlib 适配器构建 `AccountSession` → 客户端线程 `switchAccount` 重连 MC session/sessionService/skin 等

`@Threading.Thread` 文档标注预期线程；`Threading.checkMinecraftClientThread()` / `checkAccountWorkerThread()` 强制执行。不要在 worker 线程外调用 profile 修改 API。

### 持久化

- 实例配置：`<game>/config/accountsx/accounts.json` — version + UUID `id`
- 账号载荷：`~/.accountsx/<id>.json`（用户主目录，跨实例共享）
- 迁移：`ConfigVersion` 在 version 较旧时就地升级 JSON
- `ENV_DEFAULT` 账号不写入磁盘

### MC 适配器（`adapters/mc/<version>`）

每个版本是独立的 Loom 项目。典型布局：

- `MinecraftAdapterImpl` — 实现 `MinecraftPlatform`
- `ui/` — `AccountScreen`、列表控件、`UIScreenImpl` / `DefaultMemory` 桥接 common `UIScreen`/`Memory`
- `mixins/` — 标题屏按钮、session/skin accessor、Yggdrasil hook；多数版本嵌套在 `mixins/mixins/` 下（1.20 较扁平）

GUI 资源（切换图标 `account.png`、标题屏精灵 `icon/account`、默认头像 `alex_avatar.png`）统一放在 common 的 `assets/accountsx/textures/gui/` 下，**各 MC 适配器不再携带副本**。`TitleScreenMixin` 的精灵 `ResourceLocation` 用 `MOD_ID`（`accountsx`）而非 `MC_ADAPTER_ID`，由 common 提供纹理；新增 MC 版本时无需复制这些资源。Mod Menu 图标位于 `accountsx-adapter-modmenu` 命名空间。

MC/loader/API/authlib 版本由 `gradle/adapters.toml` 的 `[[mc]]` 条目按目录名（= MC 版本）提供，适配器自己的 `build.gradle.kts` 只应用 Loom 插件 + `accountsx.mc.adapter`，没有 `adapter { }` 块。Mojang official mappings 由 `accountsx.mc.adapter` 集中添加（通过反射访问 Loom 的 `loom` 扩展，因为 Loom 类不在 buildSrc classpath 上）。

### Authlib 适配器（`adapters/authlib/<version>`）

薄桥接层：`AuthlibAdapterImpl` + `AccountSessionImpl`，针对特定 `com.mojang:authlib` 版本。包：`top.syshub.accountsx.authlib`。保持包/类名与 `accountsx:adapter.authlib` 自定义条目对齐（`top.syshub.accountsx.authlib.AuthlibAdapterImpl`）。

### Mod Menu 适配器

`adapters/modmenu/7.0.0` — `top.syshub.accountsx.adapters.modmenu.ModMenuApiImpl` 打开账号 UI。通过 `gradle/adapters.toml` 的 `[[modmenu]]` 条目关联到 MC `1.20`（Mod Menu 7.0.0）。

### 构建期单一数据源（P0.2）

| 文件                        | 内容                                                                                                | 消费者                                                                                                                                                                                                                                                                                 |
|-----------------------------|-----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `gradle/libs.versions.toml` | 与 MC 版本无关的依赖版本（gson / guava / slf4j / asm / fabric-loader）                              | 根项目用 `libs.*` 访问器；buildSrc 三个插件通过 `accountsx.build.Catalog` 读取（预编译脚本插件没有 `libs` 访问器）。mixin 系列（mixinextras ×2 / sponge-mixin）仅被 buildSrc 动态 `findLibrary` 消费、Gradle 静态检查误报为"未使用"，故集中放在 `accountsx.build.MixinDeps` 而非本目录 |
| `gradle/adapters.toml`      | 适配器矩阵：每个 MC 版本的 authlib / Fabric API / `obfuscated`，以及 authlib 与 Mod Menu 适配器清单 | `settings.gradle.kts`（决定 `include` 哪些子项目）、三个 buildSrc 插件（按目录名查条目）、根 `build.gradle.kts`（universal 打包的任务名）                                                                                                                                              |

后果性约束：

- 适配器的 `build.gradle.kts` **只有 `plugins { }` 块**，没有 `adapter { }`。版本改矩阵，不改脚本。
- 目录必须在 toml 里有对应条目，否则 `include` 不到（`settings.gradle.kts` 从 toml 列，不再列目录）。反过来 toml 里有而目录不存在会在配置阶段失败。
- `obfuscated` 字段替代了旧的"读适配器脚本文本匹配 `fabric-loom-remap`"，并由 `accountsx.mc.adapter` 断言与实际应用的 Loom 插件一致。
- buildSrc 的 `dependencies` 里声明了 Gson，因此根 `build.gradle.kts` 不再有 `buildscript { classpath }` 块。

### buildSrc 插件

- `accountsx.mc.adapter` — Loom 依赖（minecraft、mappings、loader、fabric-resource-loader、关联 authlib 适配器）；按矩阵区分混淆/非混淆路径
- `accountsx.authlib.adapter` — authlib + 根项目
- `accountsx.modmenu.adapter` — Mod Menu + 关联 MC 适配器

共享代码在 `buildSrc/src/main/kotlin/accountsx/build/`：

- `AdapterMatrix.kt` / `Toml.kt` — 矩阵模型与极简 TOML 解析（只支持 `[[表]]` + 引号字符串/布尔值，超出即报错）
- `Loom.kt` — 对 Loom 内部的两处反射（`officialMojangMappings`、`FabricApiVersions.module`），MC 与 Mod Menu 插件共用，注释说明为何必须反射以及为何必须惰性调用
- `Catalog.kt` — 版本目录访问
- `MixinDeps.kt` — mixin 系列依赖坐标（仅被 buildSrc 插件动态消费；不放进 `libs.versions.toml` 以免 Gradle 误报"未使用依赖项别名"）

新增适配器版本：先在 `gradle/adapters.toml` 加条目，再在 `adapters/<type>/<version>/` 添加只含 `plugins { }` 的 `build.gradle.kts`。

### i18n

Lang key 在 `src/main/resources/assets/accountsx/lang/`（`en_us.json`、`zh_cn.json`）。UI 字符串使用 `accountsx.account.*` 格式的 key。Type 相关 key 通过 `Translator` 拼接 `AccountType.name().toLowerCase()` 生成，格式为 `accountsx.account.type.<type 名小写>.name` / `.using`。注意多数类型与 `configId` 相同，但 `AUTHLIB_INJECTOR` 的 enum name 是 `authlib_injector` 而 `configId` 是 `injector.authlib-injector`。

## 命令与代价

> **所有 Gradle 调用都必须带 `--no-daemon --stacktrace`。**
> CI（`ci.yml` / `release.yml`）的构建命令已经带这两个参数；本地构建、调试构建失败时也应带上，以保证失败时打印完整堆栈，且不在后台残留 daemon 进程（CI runner 上残留 daemon 会拖慢后续步骤甚至卡死）。

```bash
# 全量构建
./gradlew --no-daemon --stacktrace build                    # 输出：build/libs/AccountsX-<version>.jar

# Common 模块（根项目）
./gradlew --no-daemon --stacktrace :build                  # ~1 min（热缓存）

# 单个适配器（冒号分隔的项目路径）
./gradlew --no-daemon --stacktrace :adapters:mc:1.21.4:remapJar       # ~4 min（热缓存）
./gradlew --no-daemon --stacktrace :adapters:authlib:6.0.54:jar
./gradlew --no-daemon --stacktrace :adapters:modmenu:7.0.0:remapJar

# 快速校验元数据/架构约束
./gradlew --no-daemon --stacktrace checkArchitecture validateAdapterMatrix

# 清理
./gradlew --no-daemon --stacktrace clean
```

- Windows 上等效使用 `gradlew.bat`。
- 当只改一个 MC/authlib 版本时，优先构建该适配器而非全量 `build`。
- 非混淆版本（26.1+）使用 `jar` 任务而非 `remapJar`。

## 常见任务操作手册

### 加一个 MC 版本

1. 确定该 MC 版本不能正常运行，非正式版的版本识别可能不正确，需要手动调整依赖再测试
2. 在 `gradle/adapters.toml` 追加 `[[mc]]` 条目（version / authlib / fabricApi / obfuscated），**漏这步子项目不会被 `include`**
3. 复制最接近的现有版本目录：`cp -r adapters/mc/<近邻> adapters/mc/<新版本>`
4. 改 `adapters/mc/<新版本>/build.gradle.kts` 里的 Loom 插件 id（26.1+ 用 `net.fabricmc.fabric-loom`，否则 `net.fabricmc.fabric-loom-remap`），版本号不写在这里
5. 按需修改 `MinecraftAdapterImpl` 和 `ui/` 以适配 API 变更
6. 若当前 MC 版本不适配 authlib 版本，则按「加 authlib 版本」步骤操作
7. `./gradlew :adapters:mc:<新版本>:build` 并修复编译错误
8. `./gradlew :validateAdapterMatrix`（模拟 Loader 选择，确认新版本选中新适配器）
9. 构建全量 jar 并在游戏内验证：`./gradlew build`
10. 同步 AGENTS.md 顶部的支持矩阵表
11. commit：`feat(mc): 新增 MC <版本> 适配器`

### 加一个 authlib 版本

1. 在 `gradle/adapters.toml` 追加 `[[authlib]]` 条目
2. 在 `adapters/authlib/<新版本>/` 创建 `build.gradle.kts`，只写 `plugins { java; id("accountsx.authlib.adapter") }`
3. 实现 `AuthlibAdapterImpl` + `AccountSessionImpl`
4. `./gradlew :adapters:authlib:<新版本>:build`

### 加一个账号类型

需要改动的 **6 个点**（漏任何一个都会静默失败）：

1. **`AccountType` 枚举**（`common/accounts/model/AccountType.java`）— 添加枚举值，传入 accountClass、provider 实例、configId 字符串（持久化契约，不可随意改）
2. **Provider 实现**（`common/accounts/impl/`）— 实现 `AccountProvider<T>` 的 `configure` / `validate` / `login` / `refresh` / `createAccountContext`
3. **账号模型**（`BaseAccount` 子类）— 定义账号数据结构，注册 Gson TypeAdapter（若需要自定义序列化）
4. **i18n key**（`en_us.json` + `zh_cn.json`）— 添加 `accountsx.account.type.<type 名小写>.name` 和 `.using`（注意是 enum name 小写，不是 configId）
5. **配置迁移**（`ConfigVersion.java`）— 如果 configId 是新增的，确保旧配置中的未知 type 不会导致整个加载失败
6. **适配器 UI**（各 `adapters/mc/<ver>/ui/AccountScreen.java`）— 确保 `configure()` / `validate()` 在 `UIScreen` SPI 下工作

### 改配置格式

- 必须加 `ConfigVersion` 迁移 + 迁移测试
- 迁移后回写 version 并更新 `CURRENT_VERSION`
- 详见 `docs/config-schema.md`（P2 阶段产出）

### 改 UI

- 先判断改动是否版本无关
- 版本无关改动改 `mc-shared`（P5 阶段产出），需全矩阵编译
- 版本相关改动改对应 `adapters/mc/<ver>/ui/`

## 安全约束

- `~/.accountsx/` 下的文件含明文 token，**任何日志、报错、提交都不得包含其内容**
- 不得在代码里硬编码真实 client_id 之外的凭据
- 测试 fixture 一律使用 `fake-` 前缀
- 不要提交 `~/.accountsx/` 或任何含 token 的文件

## 代码风格

- Java 17、UTF-8、4 空格缩进
- 注释解释「为什么」而非「做什么」
- 历史拼写错误保留以维持兼容性：
  - `getAuthlibAdpater()`（应为 `getAuthlibAdapter()`）— 纯 Java 符号，旧适配器仍引用；`Platforms` 已用正确方法名 `authlibBridge()`
- `AccountProvider.validate` 返回 `int` 常量（`STATE_IMMEDIATE_CLOSE=0` / `STATE_HANDLE=1`），而非枚举
- 非空不变量不要用 `assert x != null` 表达（静态分析无法据此推断回调线程/按钮 `OnPress` 中的引用安全，也无法满足 `@NotNull` 形参）；统一改为捕获到 `final` 局部变量后显式判空并 `return`

## Commit 规范

```
<type>(<scope>): 中文摘要
```

常用 type：`feat` / `fix` / `refactor` / `chore` / `docs` / `style` / `perf` / `test`

scope 参照项目已有模块名：`build`、`ci`、`common`、`storage`、`auth`、`ui`、`mc`、`authlib`、`modmenu`、`docs`、`test`

规则：
1. 先 `git log --oneline -20` 查看历史提交，参照已有风格
2. summary 用中文，简洁明了
3. 一个 commit 做一件事
4. **不要擅自 commit**：没有明确 commit 指令时不执行 `git commit`
