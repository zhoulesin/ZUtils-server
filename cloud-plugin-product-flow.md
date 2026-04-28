# 云端插件产品设计流程

## 1. 角色定义

| 角色 | 说明 |
|------|------|
| **插件开发者** | 编写 ZFunction 实现，编译为 DEX，上传到插件市场 |
| **插件市场（云端）** | 托管插件元数据和 DEX 文件，提供搜索/下载/版本管理 API |
| **终端用户** | 通过 ZUtils App 发现、安装、使用插件 |
| **ZUtils 客户端** | 内置引擎，运行时按需加载 DEX 插件 |

---

## 2. 全生命周期流程

```
开发阶段          发布阶段             发现阶段          使用阶段
┌─────────┐    ┌────────────┐    ┌────────────┐    ┌─────────────┐
│ 编写代码 │ →  │ 编译为 DEX │ →  │ 上传到市场  │ →  │ 查询天气 →  │
│ 实现接口 │    │ 生成 manifest│   │ 审核/上架  │    │ LLM 解析    │
└─────────┘    └────────────┘    └────────────┘    │ → getWeather│
       │              │                │           │ → DEX 下载  │
       ↓              ↓                ↓           │ → 加载执行   │
  ZFunction.kt    build-dex.sh    PluginCatalogue  │ → 展示结果   │
                                                    └─────────────┘
```

---

## 3. 插件开发流程

### 3.1 编写 ZFunction 实现

```kotlin
class MyFunction : ZFunction {
    override val info = FunctionInfo(
        name = "myFunction",
        description = "What this function does",
        parameters = listOf(
            Parameter(name = "input", description = "Input text", type = ParameterType.STRING, required = true)
        ),
        outputType = OutputType.TEXT,
    )

    override suspend fun execute(context: ExecutionContext, args: JsonObject): ZResult {
        // 实现逻辑
        return ZResult.Success(JsonPrimitive("result"))
    }
}
```

### 3.2 编译为 DEX

使用 `scripts/build-dex.sh` 自动化编译：

```bash
# 1. 编译 module → AAR
./gradlew :functions:weather:assembleDebug

# 2. 解压 classes.jar
unzip -qo functions/weather/build/outputs/aar/weather-debug.aar \
  classes.jar -d build/dex-workdir/plugin

# 3. d8 编译为 DEX
d8 --release --output build/dex-workdir/out \
  --lib $ANDROID_HOME/platforms/android-36/android.jar \
  build/dex-workdir/plugin/classes.jar

# 4. 产物: build/dex-workdir/out/classes.dex
```

### 3.3 生成插件元数据

随 DEX 一起提交的 `plugin.json`：

```json
{
  "functionName": "getWeather",
  "description": "Query real-time weather for a city.",
  "version": "1.0.0",
  "className": "com.zhoulesin.zutils.functions.weather.GetWeatherFunction",
  "parameters": [
    {
      "name": "city",
      "description": "City name",
      "type": "STRING",
      "required": true
    }
  ],
  "dependencies": [],
  "changelog": "Initial release"
}
```

---

## 4. 插件市场设计

### 4.1 云端 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/plugins/manifest` | GET | 获取全部可用插件列表（精简版，含 DexSpec） |
| `/api/v1/plugins` | GET | 插件市场首页列表（含评分、下载量、分类） |
| `/api/v1/plugins/:id` | GET | 插件详情 |
| `/api/v1/plugins/:id/versions` | GET | 版本历史 |
| `/api/v1/plugins/:id/versions/:version/dex` | GET | 下载 DEX 文件（重定向到 CDN） |
| `/api/v1/plugins` | POST | 开发者上传新插件 |
| `/api/v1/plugins/:id/versions` | POST | 开发者发布新版本 |

### 4.2 插件分类

| 分类 | 说明 | 示例 |
|------|------|------|
| utility | 实用工具 | base64, uuid, 二维码 |
| system | 系统管理 | 电量, 音量, 亮度, 设备信息 |
| network | 网络查询 | 天气, IP查询, 汇率 |
| media | 媒体处理 | 图片处理, 音频转换 |
| ai | AI 功能 | 翻译, 摘要 |

### 4.3 插件页面

```
┌─────────────────────────────┐
│  [图标] 实时天气查询          │
│  作者: ZUtils Team          │
│  ⭐ 4.5  │  📥 1.2k 下载     │
│─────────────────────────────│
│  Query real-time weather    │
│  for a city. Returns temp,  │
│  humidity, wind speed.      │
│─────────────────────────────│
│  版本: 1.0.0  │  8KB        │
│  更新: 2026-04-25           │
│  权限: 无                    │
│─────────────────────────────│
│  [安装]  [历史版本]          │
└─────────────────────────────┘
```

---

## 5. 客户端插件管理

### 5.1 插件发现

用户可通过两种方式发现插件：

**方式 A — 自动触发**：LLM 返回包含未注册函数的工作流 → 自动下载并安装

```
用户: "武汉天气"
LLM: → getWeather(city="武汉")
引擎: → 未注册 → 请求 manifest → 下载 DEX → 加载 → 执行
```

**方式 B — 插件市场浏览**：在 App 的"插件"Tab 中浏览、搜索、手动安装

```
插件列表 → 选择插件 → 查看详情 → [安装] → 下载 DEX + 注册
```

### 5.2 插件生命周期

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  未安装   │ →  │  已安装   │ →  │  已加载   │ →  │  已执行   │
│ (manifest │    │ (DEX     │    │ (类已加载 │    │ (函数调用) │
│  可见)    │    │  已缓存)  │    │  到 JVM)  │    │          │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                      │                              │
                      ↓                              ↓
                 ┌──────────┐                  ┌──────────┐
                 │  已卸载   │                  │  升级     │
                 │ (缓存清除)│                  │ (新版本   │
                 └──────────┘                  │  替换旧版)│
                                               └──────────┘
```

### 5.3 状态管理

| 状态 | 说明 | 触发条件 |
|------|------|----------|
| 未安装 | manifest 中有记录，但 DEX 未下载 | 初始状态 |
| 已安装 | DEX 已下载到本地缓存 | 自动触发或用户点击安装 |
| 已加载 | 类已通过 ClassLoader 加载到 JVM | 首次执行 workflow step 时 |
| 已卸载 | 从缓存中删除 DEX 文件 | 用户手动卸载 |
| 升级 | 下载新版本 DEX 替换旧版本 | manifest 版本号变更 |

### 5.4 离线策略

- 已安装的插件在无网络时仍可执行（DEX 缓存在本地）
- 启动时检查 manifest 是否有更新，有新版本时静默下载
- 执行时如果 DEX 不存在且无网络，工作流报错"插件未下载"

### 5.5 安全策略

| 层级 | 措施 |
|------|------|
| 传输 | HTTPS 下载 manifest 和 DEX |
| 校验 | sha256 checksum 校验 DEX 完整性 |
| 权限 | 插件声明 requiredPermissions，执行前检查 |
| 审核 | 人工审核后方可上架插件市场 |
| 沙箱 | DEX 通过独立 ClassLoader 加载，与宿主隔离 |
| 限制 | 插件不可访问宿主私有 API 和数据 |

---

## 6. 端到端用户故事

### 故事 1：用户自动发现并使用天气插件

1. 用户输入"武汉天气"
2. LLM 返回 `[getWeather(city="武汉")]`
3. 引擎未注册 `getWeather` → 触发 `resolveMissingFunctions`
4. 从 manifest 查到天气插件 → 下载 `weather_v1.0.0.dex`
5. 加载并注册 `GetWeatherFunction`
6. 执行成功，展示天气结果
7. 后续再次使用天气功能时，直接从已注册列表调用

### 故事 2：用户在插件市场浏览并安装

1. 用户打开"插件"Tab → 看到云端插件列表
2. 浏览评分、下载量、描述 → 点击安装
3. 下载 DEX 并注册 → 插件出现在已安装列表
4. 之后 LLM 即可识别并使用该插件功能

### 故事 3：插件开发者发布新版本

1. 开发者修改代码 → 编译新 DEX → 上传到插件市场
2. 等待审核通过 → 新版本上线
3. 用户客户端检测到 manifest 版本号变更
4. 静默下载新 DEX 替换缓存 → 下次执行使用新版本
