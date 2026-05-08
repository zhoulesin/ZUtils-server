# ZUtils Server

云端插件市场后端，为 Android App、Web 前端和管理后台提供 REST API。

## 功能

- **插件市场 API** — 插件 CRUD、版本管理、DEX 文件分发
- **开发者认证** — JWT 登录/注册、BCrypt 密码加密、登录失败限制（5次/15分钟锁定）、登录日志
- **开发者系统** — 个人资料管理（昵称/邮箱/头像/简介）、公开主页 API、memberUid 公开标识
- **管理后台 API** — 用户管理（列表/禁用/启用/软删除）、插件审核、统计看板
- **客户端 Manifest** — Android 端专用的轻量级插件清单
- **Kotlin Playground** — 在线编译执行 Kotlin 代码、从 Playground 直接发布插件
- **DEX 生成** — Playground 代码 → d8 转换 → GitHub 存储、DEX 签名（RSA）
- **LLM 编排** — 服务端 LLM 意图解析（Function Calling，火山引擎豆包）
- **GitHub 集成** — 自动上传 DEX + 更新 manifest.json
- **邮件服务** — 密码重置、账号安全通知（预留）

## 技术栈

| 技术 | 版本 |
|------|------|
| Spring Boot | 3.2.5 |
| Java | 17 |
| H2 / PostgreSQL | H2 2.x / PG 可切换 |
| Spring Security + JWT | 6.2 / jjwt 0.12.5 |
| JPA / Hibernate | — |
| Kotlin Compiler | 2.1.10 |
| Android SDK (d8) | 35+ |
| Spring Mail | — |
| ZXing (二维码) | 3.5.3 |
| MCP SDK | 1.1.2 |
| Lombok | 1.18.46 |
| SpringDoc OpenAPI | 2.5.0 |

## 启动

```bash
cd ZUtils-server
mvn spring-boot:run
```

服务运行在 `http://localhost:8080`，Swagger UI: `http://localhost:8080/swagger-ui.html`

## 配置

主要配置在 `src/main/resources/application.yml`：

### 数据库
- 开发：H2 file-based（`jdbc:h2:file:./data/zutils`，`ddl-auto: update`）
- 生产：PostgreSQL（修改 `spring.datasource.*` 并切换 `ddl-auto: validate`）

### JWT
- `app.jwt.secret` — JWT 签名密钥（生产环境必须更换，建议 `openssl rand -hex 64`）
- `app.jwt.expiration-ms` — Token 有效期（默认 24h = 86400000）

### 存储
- `app.storage.dir` — DEX 文件本地存储目录（默认 `uploads/dex/`）
- `app.storage.cdn-base-url` — CDN 地址（默认 `http://localhost:8080/api/v1/files`）

### LLM（火山引擎豆包）
- `app.llm.api-key` — API Key
- `app.llm.base-url` — `https://ark.cn-beijing.volces.com/api/v3`
- `app.llm.model` — `Doubao-Seed-2.0-lite`

### DEX 签名
- `app.dex.sign.enabled` — 是否启用签名（默认 true）
- `app.dex.sign.private-key-path` — RSA 私钥路径（默认 `keys/private_key.pem`）

### 邮件（SMTP）
- `app.mail.host` — SMTP 服务器（默认 `smtp.qq.com`）
- `app.mail.port` — 端口（默认 587）
- `app.mail.username` / `app.mail.password` — 通过环境变量 `MAIL_HOST` / `MAIL_PASSWORD` 注入

### GitHub 集成
- `app.github.token` — Personal Access Token（需 repo 权限，通过环境变量 `GITHUB_TOKEN` 注入）
- `app.github.owner` — 仓库所有者（默认 `zhoulesin`）
- `app.github.repo` — 仓库名（默认 `zutils-plugins`）
- `app.github.cdn-base-url` — `https://raw.githubusercontent.com`

## 种子账号

| 用户名 | 密码 | 角色 | memberUid |
|--------|------|------|-----------|
| admin | admin123 | ADMIN | admin-001 |
| zutils-team | admin123 | DEVELOPER | zutils-team-001 |

## 项目结构

```
ZUtils-server/
├── pom.xml
├── uploads/dex/          # DEX 文件存储
├── keys/                 # RSA 密钥对（DEX 签名）
└── src/main/
    ├── java/com/zutils/server/
    │   ├── ZUtilsServerApplication.java
    │   ├── config/      # SecurityConfig, StorageConfig, OpenApiConfig, WebMvcConfig
    │   ├── controller/  # AuthController, PluginController, ManifestController,
    │   │               # FileController, TestController, AdminController, DeveloperController
    │   ├── service/    # AuthService, PluginService, AdminService, DeveloperService,
    │   │               # StorageService, GithubStorageService, DexGenerationService,
    │   │               # KotlinSandboxService
    │   ├── security/   # JwtTokenProvider, JwtAuthenticationFilter,
    │   │               # DeveloperDetails, DeveloperDetailsService
    │   ├── repository/ # DeveloperRepository, PluginRepository, PluginVersionRepository,
    │   │               # LoginLogRepository
    │   ├── model/
    │   │   ├── entity/    # Developer, Plugin, PluginVersion, LoginLog
    │   │   ├── dto/
    │   │   │   ├── request/  # RegisterRequest, LoginRequest, UpdateProfileRequest,
    │   │   │   │          # CreatePluginRequest, CreateVersionRequest
    │   │   │   └── response/ # ApiResponse, AuthResponse, PluginListResponse,
    │   │   │              # PluginDetailResponse, PluginManifestResponse,
    │   │   │              # VersionResponse
    │   │   └── enums/   # Role, PluginCategory, VersionStatus, ParameterType
    │   └── exception/ # BusinessException, GlobalExceptionHandler, 等
    └── resources/
        ├── application.yml
        └── data.sql    # 种子数据（开发者 + 示例插件）
```
