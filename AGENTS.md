# AGENTS.md

AI agent 和所有开发者的项目权威手册。保持与代码同步；任何约束变更必须同步更新此文件。

## 项目简介

Accounts X 是一个**客户端 Fabric 模组**，用于 Minecraft 多账号切换。支持离线账号、Microsoft（device-code OAuth）、Authlib-Injector（第三方 Yggdrasil）账号，以及启动器提供的环境账号。

- 基础包：`top.syshub.accountsx`
- JDK **25** 构建；common 字节码目标 Java **17**，各 MC 适配器按 `gradle/adapters.toml` 的 `java` 字段取 17 / 21 / 25
- Gradle **9.8.2**（wrapper），Fabric Loom **1.17-SNAPSHOT**
- Fabric Loader：**0.19.5**（13 个 MC 适配器共用；common 使用 `compileOnly`）
- 版本号：`gradle.properties`
- 许可：GPL-3.0
- 仓库：https://github.com/Dainsleif233/AccountsX

### MC 版本支持矩阵

| Minecraft | authlib | Java | Loom 插件         | Fabric API |
|-----------|---------|------|-------------------|------------|
| 1.20      | 4.0.43  | 17   | fabric-loom-remap | 0.83.0     |
| 1.20.2    | 5.0.47  | 17   | fabric-loom-remap | 0.91.6     |
| 1.20.3    | 6.0.52  | 17   | fabric-loom-remap | 0.91.1     |
| 1.20.5    | 6.0.54  | 21   | fabric-loom-remap | 0.97.8     |
| 1.21      | 6.0.54  | 21   | fabric-loom-remap | 0.102.0    |
| 1.21.2    | 6.0.54  | 21   | fabric-loom-remap | 0.106.1    |
| 1.21.4    | 6.0.54  | 21   | fabric-loom-remap | 0.118.5    |
| 1.21.6    | 6.0.54  | 21   | fabric-loom-remap | 0.128.0    |
| 1.21.9    | 7.0.61  | 21   | fabric-loom-remap | 0.134.0    |
| 1.21.11   | 7.0.61  | 21   | fabric-loom-remap | 0.139.4    |
| 26.1      | 7.0.61  | 25   | fabric-loom       | 0.145.1    |
| 26.2      | 7.0.61  | 25   | fabric-loom       | 0.158.0    |
| 26.3      | 10.0.77 | 25   | fabric-loom       | 0.160.5    |

每个 MC 适配器的 `depends.minecraft` 使用 `>=<版本>` 无上界，Fabric Loader 在所有满足的候选适配器中选版本号最大的那个。  
这是有意设计——新增更高版本 MC 时**不需要修改已有适配器的上界**。  
MC 26.1+ 是非混淆版本（无 ProGuard），Loom 没有 `remapJar` 任务，`officialMojangMappings()` 会抛异常。

上表是 `gradle/adapters.toml` 的人类可读副本；改矩阵改 toml，然后同步此表。

## 硬性不变量

| 不变量                                                                                                                                                     | 为什么                                                                                                                                                                                                                                   | 违反后的症状                                                            | 强制机制                                                                                            |
|------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| common 不得 import `net/minecraft/*` 或 `com/mojang/authlib/*`；`java/awt/*`、`javax/imageio/*` 已加入白名单（头像渲染位于 `common/utils/image` 包，允许） | common 被 13 个不同 MC 版本共用编译；头像渲染位于 common 的 `utils/image` 包，AWT 在 common 内白名单放行                                                                                                                                 | 编译失败（`checkArchitecture` 拒绝 net/minecraft / com/mojang/authlib） | `:checkArchitecture`：net/minecraft、com/mojang/authlib 硬性禁止；java.awt/javax.imageio 白名单放行 |
| `MinecraftAdapterImpl` 类名是数据契约                                                                                                                      | 写在各适配器 `fabric.mod.json` 的 `accountsx:adapter.mc.class` 里                                                                                                                                                                        | 13 个适配器中找不到实现，模组崩溃                                       | 人工校验 + fabric.mod.json 校验                                                                     |
| 账号配置文件含明文 token                                                                                                                                   | 所有日志、报错、提交都不得包含其内容                                                                                                                                                                                                     | token 泄露                                                              | 人工 review                                                                                         |
| `depends.minecraft` 使用 `>=` 无上界                                                                                                                       | Loader 在候选中取版本号最大者；适配器版本为 `${minecraft}-${version}`，MC 版本是最左的版本核心段（数字逐位比较，短者补 0），而 CI 注入的 `+build.N` 落在被忽略的 build 段，不会破坏排序                                                  | 版本排序被破坏时选错适配器 → mixin 目标不存在 → 崩溃                    | `:validateAdapterMatrix`                                                                            |
| 读取失败时**不得**写回配置                                                                                                                                 | 读取失败后若仍执行写入，会静默丢失用户全部账号                                                                                                                                                                                           | 一次瞬时 I/O 错误永久删除所有账号                                       | `JsonConfigStore` 只读降级                                                                          |
| 外置登录账号必须持久化并回传 `clientToken`                                                                                                                 | Yggdrasil `/authserver/refresh` 要求请求里的 `clientToken` 与服务端记录一致（drasl 强制比对，不匹配即 403 `Invalid token`）；`/authserver/authenticate` 省略该字段时服务端每次随机生成且不会替客户端稳定下来，客户端一旦丢弃就无法再刷新 | 外置登录账号「只有第一次能用」，重开游戏后刷新必然失败并被打回未登录    | `HttpGatewayInjectionTest`（登录请求带 token、刷新回传持久化值、无 token 时直接给可操作提示）       |
| 26.1+ 使用 `fabric-loom`（非 `fabric-loom-remap`）                                                                                                         | 非混淆版本没有 `remapJar` 任务                                                                                                                                                                                                           | 构建失败                                                                | `adapters.toml` 的 `obfuscated` + 插件断言                                                          |
| `configId`（`AccountType.getId()`，枚举第三个构造参数）是持久化契约                                                                                        | 写入全局 `accounts` 数组的 `type`（v4 用短 kind，经 `getKind()`；读回对 configId/kind 双认），迁移时按此识别类型                                                                                                                         | 未知 type 的账号条目在 `snapshot()` 写回时原样保留（跳过但不删除）      | `AccountRepository.fromJson` 返回 null + `snapshot()` 带回                                          |
| i18n key 格式 `accountsx.account.type.<type 名小写>.name` / `.using`                                                                                       | `Translator` 通过拼接 `AccountType.name().toLowerCase()` 生成 key（多数类型与 `configId` 相同，但 `AUTHLIB_INJECTOR` 的 enum name 是 `authlib_injector` 而 `configId` 是 `injector.authlib-injector`）                                   | 缺译 key 显示原始 key 名                                                | `:test`                                                                                             |

## 项目布局

```
AccountsX（根项目 / common）
├── src/main/java/top/syshub/accountsx/
│   ├── common/
│   │   ├── AccountsX.java              # ClientModInitializer，入口
│   │   ├── accounts/                    # 账号模型 + 提供者
│   │   │   ├── AccountProvider.java     # 接口：createAccountContext / refresh（建号在各 provider 自己的方法）
│   │   │   ├── AccountUUID.java
│   │   │   ├── BaseAccount.java         # 基类，含嵌套 AccountStorage
│   │   │   ├── impl/env/                # 环境账号（启动器会话）
│   │   │   ├── impl/injector/           # Authlib-Injector
│   │   │   ├── impl/microsoft/          # Microsoft device-code OAuth
│   │   │   ├── impl/offline/            # 离线账号
│   │   │   └── model/                   # AccountType 枚举、AccountState、Auth* 上下文
│   │   ├── adapters/
│   │   │   ├── api/                     # MinecraftPlatform / AuthlibBridge / AccountSession 接口
│   │   │   ├── Adapters.java            # @Deprecated 转发壳（兼容旧适配器）
│   │   │   └── Platforms.java           # 反射加载适配器实现（memoize）
│   │   ├── auth/                        # 认证域，按职责分三个子包
│   │   │   ├── server/                  # AuthServer / AuthServerRegistry / AuthServerMetadata / YggdrasilServerProbe / ServerAddressSecurity / ApiRootKey（apiRoot 规范化）
│   │   │   ├── oauth/                   # AccountOAuth / OAuthClientIds（纯数据表：apiRoot → client_id）/ OAuthEndpoints / IdTokenVerifier
│   │   │   └── yggdrasil/               # YggdrasilPasswordFlow / YggdrasilDeviceCodeFlow / YggdrasilProfiles / YggdrasilAuthResult
│   │   ├── manager/
│   │   │   ├── AccountManager.java      # 编排（刷新/登录；集合与持久化走 AccountRepository）
│   │   │   ├── AccountRepository.java   # 账号仓储（集合+current+标脏合并写；装载并持有 AuthServerRegistry）
│   │   │   └── AccountWorker.java       # @Deprecated 转发到 TaskScheduler（兼容旧适配器）
│   │   ├── storage/                     # 全局单文件存储（ConfigStore / ConfigRoot / JsonConfigStore）
│   │   │   └── migration/               # Migration / MigrationChain + 标准迁移链（0→1→2→3→4，含 UNIFY_AND_DECOUPLE）
│   │   ├── sync/                        # 多实例协作（去中心化）：FileMutex / AccountKey / PeerRegistry / EventLog / CoordinationService
│   │   ├── ui/                          # 版本无关的 Translator / SelectionStyle / 列表模型
│   │   ├── net/                         # HttpGateway 接口 + JdkHttpGateway（可注入网络层）
│   │   └── utils/                       # Threading / NetworkUtils / UnsafeVM / AvatarService（头像获取+落盘编排，无 AWT）
│   │       └── image/                  # 头像渲染+落盘+像素解码（AvatarRenderer / AwtAvatarRenderer / AvatarCache / AvatarPixels；AWT/ImageIO 在 common 内白名单放行）
├── adapters/
│   ├── authlib/<ver>/               # authlib API 桥接（6 个版本）
│   └── mc/<mc-ver>/                 # MC 客户端 API + UI + Mixins（13 个版本）
│   └── ├── buildSrc/                        # 预编译 Gradle 插件（3 个）
└── src/main/resources/
    ├── fabric.mod.json              # 核心模组元数据
    ├── assets/accountsx/lang/       # i18n（en_us.json + zh_cn.json，各 69 个 key）
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

`Platforms`（`common/adapters/Platforms.java`）通过反射加载两者，使用 `Suppliers.memoize` 缓存，并断言它们共享相同的 `AccountSession` 类型参数。始终通过 `Platforms.getMinecraftPlatform()` / `Platforms.authlibBridge()` 访问，不要直接引用 MC/authlib 类型。`Adapters` 类保留为 `@Deprecated` 转发壳，适配器不需改动。

Common `fabric.mod.json` 依赖两个适配器 mod id（`accountsx-adapter-mc`、`accountsx-adapter-authlib`），并注册 MethodHandles lookup accessor 到 `accountsx:impl-lookup-accessor` 供 `UnsafeVM` 使用。

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
- Provider 实现 `refresh` / `createAccountContext`，以及该类型自己的建号方法（外置认证 `loginPasswordDraft` / `loginPasswordSelected` / `loginPassword` / `loginDeviceCode`、离线 `login(name, uuid)`、微软 `requestDeviceCode` / `loginDeviceCode`）
- 登录上下文是 `AccountContext(AuthServerContext, AuthSecurityContext, AuthPolicy)`，用于构建 authlib session

### 运行时流程

1. `AccountsX.onInitializeClient` → `AccountManager.initialize` + `MicrosoftConstants.initialize`
2. Manager 用环境账号 + `AccountRepository` 加载账号列表，在 worker 上刷新非授权账号；同时投递服务器元数据预热（`warmAuthServers` → 串行通道逐 server 探测一次，失败保留旧条目不阻塞）
3. UI（各 MC 版本的 `AccountScreen`）在**客户端线程**上修改账号
4. 登录/刷新/保存在 **`TaskScheduler`**（`common/task/`：串行阻塞队列 + 上限 4 的有界并行池）上运行；并行刷新由 `TaskScheduler.runParallel` 调度
5. `loginAccount` → authlib 适配器构建 `AccountSession` → 客户端线程 `switchAccount` 重连 MC session/sessionService/skin 等
6. 删除正在使用的账号：仓储把 current 指针落回默认账号（`ENV_DEFAULT`，即启动器会话），随后 Manager 在 worker 上登录它、在客户端线程上切回会话——与会话切换同一条链路；没有环境账号可回落时指针清空、游戏会话维持原样

启动刷新先问 `AccountProvider.canReuseToken`：`expiresAt` 在未来就直接复用（不发请求）；否则外置账号分两类——OAuth 信封账号（带 refresh_token 的设备码账号）按 OIDC 惯例 GET `userinfo` 端点（Bearer 头，端点未知时保守判不能复用），会话令牌账号发 `/authserver/validate`（204 空体才算仍有效）；微软账号按落盘的 `expiresAt` 判断。能复用就跳过 refresh，否则每次启动都会轮换令牌，把同一账号在别的实例里顶掉。`expiresAt` 未知（0）时一律按需刷新。

删除账号、或删除账号所属的认证服务器时，Manager 在 worker 上逐个异步调用 `AccountProvider.signOut`：外置账号发 `/authserver/invalidate` 让服务端作废令牌，网络失败只记 debug 日志（账号已经删了，撤销失败不能把异常抛回删除链路）；微软没有单令牌撤销端点，实现为空。

外置认证的密码登录分两段：先 `/authserver/authenticate` 只拿令牌与角色清单，用户在界面上勾选角色（单角色或服务端已 bound 时跳过）后，再对每个角色发一次带 `selectedProfile` 的 `/authserver/refresh` 完成绑定——一个账号有几个角色就建几个账号，全部入库后切换到服务端原本已绑定的那个角色（没有绑定记录则用第一个）。Yggdrasil 没有独立的「选角色」端点，绑定只能靠 refresh；而刷新时也带 `selectedProfile`（取账号的 preferredPlayerUUID），否则服务端不知道该把令牌绑回哪个角色。

多角色选择态会显示每个角色的头像：头像由 common 的 `AvatarPixels` 解码成 ARGB 网格，界面侧用 `fill` 逐像素画出——`blit` / `DynamicTexture` 的签名在 1.20 / 1.21.x / 26.x 三代之间各不相同，而这些界面只有 1.20 与 26.x 两份源码、其余版本靠源码回退共享，写死任一签名都会让继承链上的版本编译失败。

### 多实例协作（多个客户端同时运行）

同一份配置可能被多个游戏实例同时读写，而**同一账号不可能被两个进程各持一份有效令牌**：Yggdrasil 刷新即作废旧令牌，微软 `refresh_token` 是单次使用的。因此不做「实例隔离」，而是让所有实例共享令牌，并把「谁在换票」串行化。

- 权威是磁盘，串行点是文件锁，广播是追加日志；没有中心进程、没有监听端口、没有 leader（`common/sync/`）。
- 协调目录是配置文件旁的 `*.d/`（`SyncPaths`）：`config.lock`（全局写锁）、`instances/<id>.json`（pid + 当前账号 + 心跳）、`locks/<accountKey>.lock`（账号租约）、`events.log`（变更事件）。
- 写路径：`AccountRepository.markDirty` → 串行通道 → 持 `config.lock` → 读盘 → **先对账再合并** → 原子写 → 追加事件。合并规则：不认识且未被墓碑标记的条目原样带回；被墓碑标记的账号不写回；我们认识的账号按内存顺序写并戳更大的 `rev`。先对账是为了不把内存里的旧令牌或已删账号盖回去。
- 墓碑写在根节点的 `removed` 数组（`{key, at}`，最多保留 200 条）：删账号时追加，防止「另一个实例的旧快照把它写回来」。
- 墓碑只约束**别人**的旧快照：本实例重新添加同一个账号（同类型、同 UUID、同服务器）时撤销它的墓碑（`AccountRepository.revivedKeys`），否则删掉再加的账号永远写不进磁盘。
- 刷新路径：取账号租约 → 锁内先 `reconcileAccount`（采纳对方已换好的令牌）→ 再判 `canReuseToken` → 都不行才换票 → **立刻落盘**。拿不到租约（对面正在换票）就跳过本轮，改读盘复用。
- 对账（`reconcile`）：磁盘上更新的令牌直接采纳；磁盘上新增的账号收进内存；被墓碑标记、内存里还在的账号丢弃（若是当前账号则回落默认账号）；对端新增认证服务器时重载注册表。
- 发现延迟：`CoordinationService` 轮询 `events.log`（250ms）+ 心跳（2s）。日志只影响「多快发现」，正确性不依赖它——用令牌前一定先读盘。
- 删除保护：删除一个正被别的存活实例用着的账号时，第一次点击只弹警告，3 秒内再点一次才真删（`DefaultAccountListModel`）；被删方的界面经 `AccountManager.addChangeListener` 回调重同步，当前账号被删则回落到默认账号，且**不会**再调 `signOut`（删它的那个实例已经撤销过）。
- 崩溃处理：换票前写 `locks/<key>.intent`，成功落盘后清除；启动时发现属于已死进程的意图文件，说明令牌可能已被轮换却未落盘，提示用户重新登录。写到一半崩溃不会留下半个文件（临时文件带 pid+序号，只做原子替换）。

`@Threading.Thread` 文档标注预期线程；`Threading.checkMinecraftClientThread()` / `checkAccountWorkerThread()` 强制执行。不要在 worker 线程外调用 profile 修改 API。

### 持久化

- 配置文件：`~/.config/accountsx.json`（v4：`version` / `servers` / `accounts`；可用 `ACCOUNTSX_CONFIG` 覆盖，`$XDG_CONFIG_HOME` 次之）。
- `JsonConfigStore` 原子写；读取或迁移失败时备份并进入只读降级，保存被丢弃。
- `servers` 里的外置认证服务器条目是账号的登录端点、公钥与 skinDomains 来源；移除条目时连带删除属于它的账号（`AccountRepository.dropAccountsOn`），否则这些账号既登录不了也刷新不了。
- 旧版本按 `StandardMigrations` 链升级并回写；未知账号条目跳过但保留。
- 令牌过期时间写在 `credentials.expiresAt`（毫秒，可选）：缺失或 0 表示未知，老配置无需迁移即可读；启动时用它判断能否跳过刷新。
- `ENV_DEFAULT` 账号不写入磁盘
- 如存在待迁移的实例配置，先触发一次迁移再加载；全局已有账号时不再重复触发。
- `servers` 由 `AuthServerRegistry`（`common/auth/server/`）接管：登录/刷新/建会话只读内存缓存（公钥、skinDomains、OIDC 端点），仅在启动预热与加服（用户在「添加认证服务器」里显式提交）时探测；登录路径直接用现有条目、不再解析 locator；探测失败保留 stale 条目不阻塞；`mojang` / `offline` 为固定条目（无 `apiRoot`），装载时自动补齐。
- 探测 openid 配置时，若服务器发布了 `scopes_supported` 却不含 `openid` / `Yggdrasil.PlayerProfiles.Select`，就不记录 `deviceAuthorizationEndpoint`——设备码登录必然 `invalid_scope`，索性不给这个入口；密码登录不受影响。没发布该字段时不作判断。

### MC 适配器（`adapters/mc/<version>`）

每个版本是独立的 Loom 项目。典型布局：

- `MinecraftAdapterImpl` — 实现 `MinecraftPlatform`
- `ui/` — `AccountScreen`（筛选 + 列表）、`AccountListWidget`、登录界面（`LoginScreen` 骨架 + `MicrosoftLoginScreen` / `OfflineLoginScreen` / `ExternalLoginScreen`）、`ServerScreen`
- `mixins/` — 标题屏按钮、session/skin accessor、Yggdrasil hook；多数版本嵌套在 `mixins/mixins/` 下（1.20 较扁平）

GUI 资源（切换图标 `account.png`、标题屏精灵 `icon/account`、默认头像 `alex_avatar.png`）统一放在 common 的 `assets/accountsx/textures/gui/` 下。`TitleScreenMixin` 的精灵 `ResourceLocation` 用 `MOD_ID`（`accountsx`）而非 `MC_ADAPTER_ID`，由 common 提供纹理；新增 MC 版本时无需复制这些资源。Mod Menu 集成（配置入口 + 图标）由 mc 适配器通过 `fabric.mod.json` 的 `custom.modmenu.parent` 提供，不再有独立的 `accountsx-adapter-modmenu` 适配器。

MC/loader/API/authlib/java 版本由 `gradle/adapters.toml` 的 `[[mc]]` 条目按目录名（= MC 版本）提供，适配器自己的 `build.gradle.kts` 只应用 Loom 插件 + `accountsx.mc.adapter`，没有 `adapter { }` 块。Mojang official mappings 由 `accountsx.mc.adapter` 集中添加（通过反射访问 Loom 的 `loom` 扩展，因为 Loom 类不在 buildSrc classpath 上）。

### MC 适配器源码回落

13 个 MC 工程不各自保存完整源码：每个版本目录只放**新增或变化**的 Java 文件，缺失的同路径文件沿 `gradle/adapters.toml` 的 `[[mc]]` 顺序向更早版本回落，最迟到 1.20。回落发生在构建期，选中的源码针对目标版本重新编译——不复用旧 class，也没有跨版本的 Java 继承。

- 解析器：`buildSrc/src/main/kotlin/accountsx/build/AdapterSourceResolver.kt`；`prepareAdapterSources` 把有效源码同步到 `adapters/mc/<ver>/build/generated/adapter-sources/main/java`，`compileJava` 与 `sourcesJar` 共用这一份。
- 来源报告：`adapters/mc/<ver>/build/reports/adapter-sources`（有效路径 → 来源版本 → 原始相对路径，含删除记录）；`./gradlew :adapters:mc:<ver>:explainAdapterSources` 直接查看。
- 显式删除：`adapters/mc/<ver>/source-removals.txt`，逐行写 `src/main/java` 下的相对路径；每层先应用继承来的删除，再叠自己的文件，删除没命中即构建失败。
- 自检：`:checkAdapterSourceResolver`（fixture 覆盖最近文件胜出、多级回落、删除/重新引入、非法删除、命名冲突、无效链）。

后果性约束：

- 新增版本只写差异文件（见「加一个 MC 版本」）；与近邻完全相同的副本没有意义，回落会继承同一份内容。
- 移动或重命名包路径时，必须在该层 `source-removals.txt` 删掉旧路径，否则同一顶层类会出现两份。
- `fabric.mod.json` 与 `*.mixins.json` 仍逐版本维护，不参与回落；`:verifyUniversalJar` 会校验嵌套 jar 里的每个 mixin 类都存在。

### Authlib 适配器（`adapters/authlib/<version>`）

薄桥接层：`AuthlibAdapterImpl` + `AccountSessionImpl`，针对特定 `com.mojang:authlib` 版本。包：`top.syshub.accountsx.authlib`。保持包/类名与 `accountsx:adapter.authlib` 自定义条目对齐（`top.syshub.accountsx.authlib.AuthlibAdapterImpl`）。

### Mod Menu 集成

Mod Menu 的「配置入口」由各 mc 适配器 `fabric.mod.json` 的 `custom.modmenu.parent`（指向 `accountsx`）提供，UI 复用 mc 适配器的 `AccountScreen`。Mod Menu 本体（com.terraformersmc:modmenu）由 `accountsx.mc.adapter` 按 `gradle/adapters.toml` 每个 `[[mc]]` 的 `modmenu` 字段加入：混淆分支用 `modImplementation`，非混淆分支（26.1+）用 `implementation`。

### 构建期单一数据源

| 文件                        | 内容                                                                                                                                  | 消费者                                                                                                                                                                                                                                                                                 |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `gradle/libs.versions.toml` | 与 MC 版本无关的依赖版本（gson / guava / slf4j / asm / fabric-loader）                                                                | 根项目用 `libs.*` 访问器；buildSrc 两个插件通过 `accountsx.build.Catalog` 读取（预编译脚本插件没有 `libs` 访问器）。mixin 系列（mixinextras ×2 / sponge-mixin）仅被 buildSrc 动态 `findLibrary` 消费、Gradle 静态检查误报为"未使用"，故集中放在 `accountsx.build.MixinDeps` 而非本目录 |
| `gradle/adapters.toml`      | 适配器矩阵：每个 MC 版本的 authlib / Fabric API / Mod Menu / `obfuscated`、Java 字节码目标 `java`（缺省 17），以及 authlib 适配器清单 | `settings.gradle.kts`（决定 `include` 哪些子项目）、两个 buildSrc 插件（按目录名查条目）、根 `build.gradle.kts`（universal 打包的任务名）                                                                                                                                              |

后果性约束：

- 适配器的 `build.gradle.kts` **只有 `plugins { }` 块**，没有 `adapter { }`。版本改矩阵，不改脚本。
- 目录必须在 toml 里有对应条目，否则 `include` 不到（`settings.gradle.kts` 从 toml 列，不再列目录）。反过来 toml 里有而目录不存在会在配置阶段失败。
- `obfuscated` 字段替代了旧的"读适配器脚本文本匹配 `fabric-loom-remap`"，并由 `accountsx.mc.adapter` 断言与实际应用的 Loom 插件一致。
- buildSrc 的 `dependencies` 里声明了 Gson，因此根 `build.gradle.kts` 不再有 `buildscript { classpath }` 块。

### buildSrc 插件

- `accountsx.mc.adapter` — Loom 依赖（minecraft、mappings、loader、fabric-resource-loader、关联 authlib 适配器、Mod Menu）；按矩阵区分混淆/非混淆路径
- `accountsx.authlib.adapter` — authlib + 根项目

共享代码在 `buildSrc/src/main/kotlin/accountsx/build/`：

- `AdapterMatrix.kt` / `Toml.kt` — 矩阵模型与极简 TOML 解析（只支持 `[[表]]` + 引号字符串/布尔值，超出即报错）
- `Loom.kt` — 对 Loom 内部的两处反射（`officialMojangMappings`、`FabricApiVersions.module`），MC 适配器插件使用，注释说明为何必须反射以及为何必须惰性调用
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
2. 在 `gradle/adapters.toml` 追加 `[[mc]]` 条目（version / authlib / fabricApi / modmenu / java / obfuscated），**漏这步子项目不会被 `include`**
3. 只建 `build.gradle.kts`，不要整目录拷贝：新版本的 Java 文件放 `adapters/mc/<新版本>/src/main/java/...`，
   与近邻相同的文件一律不复制（回落会继承），编译报错再按缺口补文件
4. 改 `adapters/mc/<新版本>/build.gradle.kts` 里的 Loom 插件 id（26.1+ 用 `net.fabricmc.fabric-loom`，否则 `net.fabricmc.fabric-loom-remap`），版本号不写在这里
5. 按需修改 `MinecraftAdapterImpl` 和 `ui/` 以适配 API 变更
6. 若当前 MC 版本不适配 authlib 版本，则按「加 authlib 版本」步骤操作
7. `./gradlew --no-daemon --stacktrace :adapters:mc:<新版本>:build :checkAdapterSourceResolver` 并修复编译错误
8. `./gradlew --no-daemon --stacktrace :validateAdapterMatrix`（模拟 Loader 选择，确认新版本选中新适配器）
9. 构建全量 jar 并在游戏内验证：`./gradlew --no-daemon --stacktrace build`（含 `universal` + `verifyUniversalJar`）
10. 同步 AGENTS.md 顶部的支持矩阵表
11. commit：`feat(mc): 新增 MC <版本> 适配器`

### 加一个 authlib 版本

1. 在 `gradle/adapters.toml` 追加 `[[authlib]]` 条目
2. 在 `adapters/authlib/<新版本>/` 创建 `build.gradle.kts`，只写 `plugins { java; id("accountsx.authlib.adapter") }`
3. 实现 `AuthlibAdapterImpl` + `AccountSessionImpl`，贴图会话策略与签名状态解析走 `PolicySessionService`（其余版本已有同形实现，参照相邻版本）
4. `./gradlew --no-daemon --stacktrace :adapters:authlib:<新版本>:build`

### 加一个账号类型

需要改动的 **6 个点**（漏任何一个都会静默失败）：

1. **`AccountType` 枚举**（`common/accounts/model/AccountType.java`）— 添加枚举值，传入 accountClass、provider 实例、configId 字符串（持久化契约，不可随意改）
2. **Provider 实现**（`common/accounts/impl/`）— 实现 `AccountProvider<T>` 的 `refresh` / `createAccountContext`，并提供该类型的建号方法
3. **账号模型**（`BaseAccount` 子类）— 定义账号数据结构，注册 Gson TypeAdapter（若需要自定义序列化）
4. **i18n key**（`en_us.json` + `zh_cn.json`）— 添加 `accountsx.account.type.<type 名小写>.name` 和 `.using`（注意是 enum name 小写，不是 configId）
5. **配置迁移**（`storage/migration/` 新增一跳，注册进 `StandardMigrations`）— 如果 configId/kind 是新增的，确保旧配置中的未知 type 不会导致整个加载失败（`AccountType` 加枚举值时同步加 `kind`；仓储 `byKind` 加分支，`toJson` 无需改——走 `getKind()`）
6. **适配器 UI**（各 `adapters/mc/<ver>/ui/`）— 若登录方式不同于现有的外置/微软/离线，加一个 `LoginScreen` 子类，并在 `AccountScreen.openAddAccount` 里路由到它

### 改配置格式

- 必须加 `storage/migration` 迁移跳 + 迁移测试
- 迁移后回写 version 并更新 `CURRENT_VERSION`

### 改 UI

- 先判断改动是否版本无关
- 版本无关改动需全矩阵编译
- 版本相关改动改对应 `adapters/mc/<ver>/ui/`

## 安全约束

- 账号配置文件（含 `~/.config/accountsx.json`、`~/.accountsx/`）含明文 token，**任何日志、报错、提交都不得包含其内容**
- 不得在代码里硬编码真实 client_id 之外的凭据
- 测试 fixture 一律使用 `fake-` 前缀
- 不要提交 `~/.config/accountsx.json`、`~/.accountsx/` 或任何含 token 的文件

## 代码风格

- Java 17、UTF-8、4 空格缩进
- 注释解释「为什么」而非「做什么」
- 建号入口按账号类型各自成方法（`loginPassword` / `loginDeviceCode` / `login(name, uuid)`），不要重新引入「通用表单 + Memory 键值袋」这类跨类型抽象：各类型登录流程差别太大，抽象只会把差异挤进字符串键
- 非空不变量不要用 `assert x != null` 表达（静态分析无法据此推断回调线程/按钮 `OnPress` 中的引用安全，也无法满足 `@NotNull` 形参）；统一改为捕获到 `final` 局部变量后显式判空并 `return`

## Commit 规范

```
<type>(<scope>): 中文摘要
```

常用 type：`feat` / `fix` / `refactor` / `chore` / `docs` / `style` / `perf` / `test`

scope 参照项目已有模块名：`build`、`ci`、`common`、`storage`、`auth`、`ui`、`mc`、`authlib`、`docs`、`test`

规则：
1. 先 `git log --oneline -20` 查看历史提交，参照已有风格
2. summary 用中文，简洁明了
3. 一个 commit 做一件事
4. **不要擅自 commit**：没有明确 commit 指令时不执行 `git commit`
