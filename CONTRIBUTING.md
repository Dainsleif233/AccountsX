# 贡献指南

感谢你对 Accounts X 的关注！本文档帮助你快速上手开发和贡献。

## 环境要求

- **JDK 25**（编译目标为 Java 17）
- **Gradle wrapper** — 项目自带 `gradlew` / `gradlew.bat`，不要使用系统全局 Gradle
- 构建脚本默认使用国内镜像（BMCLAPI / 阿里云 / 腾讯），详见 README

## 分支策略

- `dev` — 开发/发布分支，PR 目标

## 开发流程

1. Fork 仓库，基于 `dev` 创建特性分支
2. 修改代码，确保对应模块编译通过
3. 提交 PR 到 `dev` 分支

### 构建验证

```bash
# 改了 core 代码
./gradlew :core:build

# 改了某个 MC 适配器
./gradlew :adapters:mc:1.21.4:build

# 改了 authlib 适配器
./gradlew :adapters:authlib:6.0.54:jar

# 改了共享代码
./gradlew build
```

**不要**只改一个适配器就跑 `./gradlew build` — 那会编译全部 Loom 适配器，非常耗时。

### PR 要求

- PR 前确保 `./gradlew :core:build` 通过
- 说明手测了哪些 MC 版本（登录 / 切换 / 重启后账号仍在）
- 新 MC 版本的 PR 需说明：改了哪些文件、在哪个版本上验证过

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

scope 参照模块名：`build`、`ci`、`core`、`storage`、`auth`、`ui`、`mc`、`authlib`、`modmenu`、`docs`、`test`

先 `git log --oneline -20` 查看历史提交，保持风格一致。

## 安全

- **不要**提交 `~/.accountsx/` 或任何含 token 的文件
- **不要**在代码、日志或提交信息中暴露 access token
- 测试 fixture 使用 `fake-` 前缀

## 项目结构

详见 [AGENTS.md](AGENTS.md) 的项目布局章节。
