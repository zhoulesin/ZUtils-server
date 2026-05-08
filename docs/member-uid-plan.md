# memberUid 生成规范与全量关联方案

---

## 一、memberUid 生成规范

### 格式

```
{前缀}-{8位随机}

前缀规则：
  注册 → uuid 前 8 位
  种子 → admin-xxx, team-xxx

示例：a3f2b1c0, admin-001, zutils-team-001
```

### 生成时机

- 注册时自动生成（用户可选填）
- 管理员创建用户时可指定
- 种子数据固定写死
- 生成后不可修改

### 生成逻辑

```java
// 注册时
if (request.getMemberUid() == null || request.getMemberUid().isBlank()) {
    developer.setMemberUid(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
} else {
    // 校验唯一性
    if (developerRepository.existsByMemberUid(request.getMemberUid())) {
        throw new BusinessException("memberUid already exists");
    }
    developer.setMemberUid(request.getMemberUid());
}
```

---

## 二、全量关联方案

### 原则

> 所有对外接口使用 memberUid，内部数据库保留 id 做外键

| 层级 | 用 id | 用 memberUid |
|------|-------|-------------|
| 数据库外键 | ✅ | - |
| JWT token sub | ✅（已有，改代价大）| - |
| REST API 路径 | ❌ | ✅ |
| 公开页面 | ❌ | ✅ |
| 插件归属显示 | ❌ | ✅ |

---

## 三、需要改的地方

### 3.1 代码层（9 处）✅

| # | 文件 | 状态 |
|---|------|------|
| 1 | `RegisterRequest.java` | ✅ |
| 2 | `AuthService.register()` | ✅ 自动生成 |
| 3 | `DeveloperRepository.java` | ✅ 新增 existsByMemberUid |
| 4 | `PluginManifestResponse.java` | ✅ 加 memberUid |
| 5 | `PluginService.getManifest()` | ✅ 填 memberUid |
| 6 | `PluginCard.tsx`（前端） | ✅ 显示 @author · memberUid |
| 7 | `AuthController.updateProfile()` | ✅ 已有 |
| 8 | `DeveloperController.java` | ✅ 已有 |
| 9 | `GithubStorageService` addToManifest | ⏸ 暂缓（发布时 server 端 sync） |

### 3.2 数据库层 ✅

| # | 表 | 状态 |
|---|------|------|
| 10 | `data.sql` | ✅ 种子用户已补 memberUid |

### 3.3 文档层

| # | 文件 | 状态 |
|---|------|------|
| 11 | `user-system-plan.md` | ✅ 已同步 |

---

## 四、不改的地方

| # | 文件 | 原因 |
|---|------|------|
| - | `LoginLog.developerId` | 内部日志，id 够用 |
| - | `Plugin.developerId` | 数据库外键，性能优先 |
| - | `PluginRepository.findByDeveloperId` | Service 内部，不改 API 就用 id |
| - | `JWT token` sub 字段 | 改会破坏现有 token |
| - | `DeveloperDetails.id` | Spring Security 内部机制 |

---

## 五、执行顺序

> 全部 11 项已完成，memberUid 体系已完整上线。

```
1. 加 DeveloperRepository.existsByMemberUid + findByMemberUid  ✅
2. AuthService.register() 自动生成 memberUid                  ✅
3. PluginManifestResponse 加 memberUid                        ✅
4. PluginService.getManifest() 填 memberUid                   ✅
5. UpdateProfileRequest 已有                                   ✅
6. data.sql 补 memberUid                                      ✅
7. 前端 PluginCard 显示 memberUid                             ✅
8. DeveloperController GET /{memberUid} 公开主页              ✅
9. 文档同步                                                   ✅
```
