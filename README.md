# ZUtils Server

云端的插件市场后端，为 Android App 和 Web 前端提供 REST API。

## 功能

- **插件市场 API** — 插件 CRUD、版本管理、DEX 文件分发
- **开发者认证** — JWT 登录/注册、BCrypt 密码加密
- **客户端 Manifest** — Android 端专用的轻量级插件清单
- **Kotlin Playground** — 在线编译执行 Kotlin 代码
- **DEX 生成** — Playground 代码 → d8 转换 → GitHub 存储
- **LLM 编排** — 服务端 LLM 意图解析（Function Calling）
- **GitHub 集成** — 自动上传 DEX + 更新 manifest.json
- **管理后台 API** — 插件审核、用户管理、统计

## 技术栈

| 技术 | 版本 |
|------|------|
| Spring Boot | 3.2.5 |
| Java | 17 |
| H2 / PostgreSQL | — |
| Spring Security + JWT | 6.2 / 0.12.5 |
| JPA / Hibernate | — |
| Kotlin Compiler | 2.1.10 |
| Android SDK (d8) | 35+ |

## 启动

```bash
cd ZUtils-server
mvn spring-boot:run
```

服务运行在 `http://localhost:8080`，Swagger UI: `http://localhost:8080/swagger-ui.html`

## 配置

主要配置在 `src/main/resources/application.yml`：

- `app.jwt.secret` — JWT 签名密钥（生产环境必须更换）
- `app.github.token` — GitHub Personal Access Token（需要 repo 权限）
- `app.llm.api-key` — 火山引擎 API Key

## 种子账号

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | ADMIN |
| zutils-team | admin123 | DEVELOPER |
