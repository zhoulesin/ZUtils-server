# ZUtils 用户管理系统设计

> 基于现有代码的完整规划

---

## 一、现状盘点

| 功能 | 状态 |
|------|------|
| 注册（默认 DEVELOPER） | ✅ |
| 登录（JWT） | ✅ |
| 登录失败次数限制（5 次/15 分钟锁定） | ✅ |
| 登录日志（IP/时间/成功失败） | ✅ |
| 密码强度校验（8 位+大小写+数字） | ✅ |
| 角色隔离（ADMIN/DEVELOPER） | ✅ |
| 修改个人信息（昵称/邮箱/密码/头像/简介） | ✅ |
| 管理员查看开发者列表 | ✅ |
| 管理员禁用/启用开发者 | ✅ |
| 管理员软删除开发者（需密码确认） | ✅ |
| nickname / memberUid | ✅ |
| 插件绑定到开发者 | ✅ |
| 开发者公开主页 `/developers/{memberUid}`（简介/头像/插件列表/下载量） | ✅ |

---

## 二、待补充

### 2.1 注册增强

| 需求 | 说明 |
|------|------|
| 邮箱验证 | 注册后发验证邮件，点击链接激活账号；未激活不允许登录 |
| 邀请码机制 | 管理员生成邀请码，限制注册来源 |
| 注册审核 | 管理员审核后才激活（与邀请码二选一） |

### 2.2 登录安全

| 需求 | 说明 |
|------|------|
| 设备管理 | 查看当前活跃 session，远程踢出 |

### 2.3 密码管理

| 需求 | 说明 |
|------|------|
| 忘记密码 | 输入邮箱 → 发重置链接 → 设置新密码 |

### 2.4 用户资料

| 需求 | 说明 |
|------|------|
| 头像上传 | avatarUrl 字段已有，上传接口待实现 |
| 开发者标签 | 官方认证 / 资深开发者 等徽章 |

### 2.5 管理员功能

| 需求 | 说明 |
|------|------|
| 用户搜索 | 按用户名/邮箱/昵称搜索 |
| 批量操作 | 批量启用/禁用/删除 |
| 操作日志 | 记录管理员的所有敏感操作 |
| 角色升级 | 将 DEVELOPER 升级为 ADMIN（需主管理员密码） |

### 2.6 通知

| 需求 | 说明 |
|------|------|
| 插件审核结果通知 | 邮件通知开发者 |
| 账号安全通知 | 异地登录提醒、密码修改通知 |

---

## 三、数据模型 ✅ 已完成

### 3.1 Developer（扩展完成）

```
developers
├── id (PK)                      ✅
├── username (唯一)               ✅
├── nickname                     ✅
├── member_uid (唯一, 公开 ID)    ✅
├── email (唯一)                  ✅
├── password (BCrypt)            ✅
├── role (ADMIN / DEVELOPER)     ✅
├── enabled                      ✅
├── email_verified               ✅
├── deleted (软删除)              ✅
├── avatar_url                   ✅
├── bio                          ✅
├── login_fail_count             ✅
├── locked_until                 ✅
├── created_at                   ✅
├── updated_at                   ✅
```

### 3.2 新增表

| 表 | 状态 |
|------|------|
| login_logs | ✅ 已完成 |
| audit_logs | ⏸ Phase 6 |
| verification_tokens | ⏸ Phase 2/3 |
| invite_codes | ⏸ Phase 5 |

---

## 四、API 设计

### 4.1 Auth

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 注册（已有） |
| POST | `/api/v1/auth/login` | 登录（已有） |
| PUT | `/api/v1/auth/profile` | 修改个人信息（已有） |
| POST | `/api/v1/auth/forgot-password` | 忘记密码 → 发重置邮件 |
| POST | `/api/v1/auth/reset-password` | 重置密码（带 token） |
| POST | `/api/v1/auth/verify-email` | 验证邮箱（带 token） |
| GET | `/api/v1/auth/sessions` | 查看当前登录设备 |
| DELETE | `/api/v1/auth/sessions/{id}` | 踢出某设备 |

### 4.2 Developer（公开）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/developers/{memberUid}` | 公开主页（memberUid/昵称/头像/简介/角色/插件列表/总下载量）✅ |

### 4.3 Admin

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/users` | 用户列表（已有，加搜索参数） |
| POST | `/api/v1/admin/users` | 创建用户（已有） |
| POST | `/api/v1/admin/users/{id}/disable` | 禁用（已有） |
| POST | `/api/v1/admin/users/{id}/enable` | 启用（已有） |
| DELETE | `/api/v1/admin/users/{id}` | 软删除（已有） |
| PUT | `/api/v1/admin/users/{id}/role` | 升级/降级角色 |
| GET | `/api/v1/admin/users/{id}/logs` | 查看该用户登录日志 |
| GET | `/api/v1/admin/audit` | 查看操作审计日志 |
| POST | `/api/v1/admin/invite-codes` | 生成邀请码 |
| GET | `/api/v1/admin/invite-codes` | 查看邀请码列表 |

---

## 五、实施顺序

### Phase 1 — 安全基线（P0）✅ 已完成

```
1. 登录失败次数限制 + 15 分钟锁定 ✅
2. 登录日志记录 ✅
3. 密码强度校验 ✅
```

### Phase 2 — 邮箱验证（P0）⏸ 暂缓

```
1. 注册后生成验证 token → 发邮件
2. 邮箱验证接口
3. 未验证状态不允许登录
```

### Phase 3 — 忘记密码（P1）⏸ 暂缓

```
1. 输入邮箱 → 生成重置 token → 发邮件
2. 带 token 设置新密码
```

### Phase 4 — 开发者公开主页（P1）✅ 已完成

```
1. GET /api/v1/developers/{memberUid} ✅
2. 返回：memberUid, nickname, bio, avatarUrl, role, pluginCount, totalDownloads, plugins[] ✅
3. 前端展示：开发者卡片 + 插件列表 ✅
```

### Phase 5 — 邀请码 + 审核（P2）

```
1. 管理员生成邀请码
2. 注册时校验邀请码
3. 注册审核流程
```

### Phase 6 — 操作日志 + 高级管理（P2）

```
1. 管理员操作审计
2. 角色管理
3. 批量操作
```

---

## 六、关键设计决策

### 6.1 memberUid 生成策略

```
注册时自动生成：uuid 前 8 位，如 "a3f2b1c0"
管理员可手动修改
全局唯一，用于公开 URL
```

### 6.2 邮箱验证 token

```
UUID 生成，24 小时有效
存在 verification_tokens 表
用过即销毁
```

### 6.3 密码强度

```
最少 8 位
至少包含大写字母 + 小写字母 + 数字
前端实时提示，后端 @Valid 校验
```

### 6.4 角色权限矩阵

| 操作 | 匿名 | DEVELOPER | ADMIN |
|------|------|-----------|-------|
| 注册 | ✅ | - | - |
| 登录 | ✅ | ✅ | ✅ |
| 修改自己信息 | - | ✅ | ✅ |
| 查看市场 | ✅ | ✅ | ✅ |
| 发布插件 | - | ✅ | ✅ |
| 管理自己插件 | - | ✅ | ✅ |
| 管理所有插件 | - | - | ✅ |
| 管理所有用户 | - | - | ✅ |
| 操作日志查看 | - | - | ✅ |
