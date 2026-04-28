# 云端插件数据结构和协议

## 概述

ZUtils 支持通过云端分发 DEX 插件来扩展功能。客户端在运行工作流时，如果遇到未注册的函数名，会向云端请求对应的 DEX 插件，下载后通过 `InMemoryDexClassLoader` 动态加载并执行。

---

## 1. 云端 manifest 接口

客户端启动时（或首次需要插件时）向云端请求插件列表。云端返回一个 JSON 格式的 manifest，描述所有可用插件。

### 请求

```
GET /api/v1/plugins/manifest
```

### 响应格式

```json
{
  "plugins": [
    {
      "functionName": "getWeather",
      "description": "Query real-time weather for a city. Returns temperature, weather condition, humidity, wind speed.",
      "version": "1.0.0",
      "dexUrl": "https://cdn.example.com/plugins/weather_v1.0.0.dex",
      "className": "com.zhoulesin.zutils.functions.weather.GetWeatherFunction",
      "checksum": "sha256:abcdef123456...",
      "size": 8060,
      "parameters": [
        {
          "name": "city",
          "description": "City name preferably in English, e.g. Beijing, Tokyo, London, Wuhan.",
          "type": "STRING",
          "required": true
        }
      ],
      "requiredPermissions": [],
      "dependencies": [
        {
          "name": "zxing-core",
          "version": "3.5.3",
          "dexUrl": "https://cdn.example.com/plugins/lib_zxing_v3.5.3.dex",
          "checksum": "sha256:fedcba654321..."
        }
      ]
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `functionName` | string | 是 | 函数名，与 LLM tool_calls 中的 name 对应 |
| `description` | string | 否 | 函数功能描述，用于 LLM 的 tools 参数 |
| `version` | string | 是 | 语义化版本号 |
| `dexUrl` | string | 是 | DEX 文件下载地址 |
| `className` | string | 是 | 实现类的全限定名，必须实现 `ZFunction` 接口 |
| `checksum` | string | 否 | 文件校验和（格式 `sha256:xxx`），用于完整性校验 |
| `size` | number | 否 | DEX 文件大小（字节） |
| `parameters` | array | 否 | 函数参数定义，用于 LLM 的 tools 参数 |
| `requiredPermissions` | array | 否 | 运行时需要的 Android 权限列表 |
| `dependencies` | array | 否 | 依赖的其他 DEX 文件（如第三方库） |

### Parameter 定义

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 参数名 |
| `description` | string | 否 | 参数描述 |
| `type` | string | 否 | 参数类型：`STRING`/`NUMBER`/`INTEGER`/`BOOLEAN`/`ARRAY`/`OBJECT` |
| `required` | boolean | 否 | 是否必填，默认 false |

---

## 2. DEX 下载接口

### 请求

```
GET https://cdn.example.com/plugins/{filename}
```

### 响应

二进制 DEX 文件（Content-Type: application/octet-stream）。

客户端校验：
- 根据 manifest 中的 `size` 校验文件大小
- 根据 `checksum`（sha256）校验文件完整性

### 缓存策略

- 客户端根据 `version` 判断是否需要更新
- 已缓存的 DEX 文件在版本不变时不重复下载
- 缓存路径：`Context.cacheDir/dex_cache/`

---

## 3. 本地 manifest 镜像

云端 manifest 下载后会缓存在本地，格式与云端一致。当前开发阶段使用本地 assets 作为模拟源：

```
plugin-manager/src/main/assets/dex/
├── dex_manifest.json
├── plugin_qrcode_v1.0.0.dex
├── lib_zxing_v3.5.3.dex
└── weather_v1.0.0.dex
```

---

## 4. 核心数据模型（服务端侧）

### PluginDefinition — 插件定义

```json
{
  "id": "plugin_weather",
  "name": "getWeather",
  "description": "实时天气查询",
  "icon": "https://cdn.example.com/icons/weather.png",
  "category": "utility",
  "author": "ZUtils Team",
  "version": "1.0.0",
  "minAppVersion": "1.0.0",
  "downloads": 1280,
  "rating": 4.5,
  "createdAt": "2026-04-25T00:00:00Z",
  "updatedAt": "2026-04-25T00:00:00Z"
}
```

### PluginVersion — 插件版本

```json
{
  "pluginId": "plugin_weather",
  "version": "1.0.0",
  "dexUrl": "https://cdn.example.com/plugins/weather_v1.0.0.dex",
  "dexSize": 8060,
  "checksum": "sha256:abcdef123456...",
  "className": "com.zhoulesin.zutils.functions.weather.GetWeatherFunction",
  "parameters": [...],
  "requiredPermissions": [],
  "dependencies": [],
  "changelog": "Initial release",
  "publishedAt": "2026-04-25T00:00:00Z"
}
```

---

## 5. 客户端加载流程

```
LLM 返回 tool_calls →
Engine.execute(workflow) →
resolveMissingFunctions():
  1. registry.contains("getWeather")? → 否
  2. dexLoader.resolve("getWeather") → 从 manifest 查找 DexSpec
  3. dexLoader.download(spec) → HTTP GET dexUrl → ByteArray
  4. dexLoader.load(bytes, spec) → InMemoryDexClassLoader → 反射实例化 ZFunction
  5. registry.register(function)
  6. workflowEngine.execute(step) → function.execute(context, args)
```

---

## 6. 安全考虑

- DEX 文件通过 checksum 校验完整性，防止篡改
- manifest 应通过 HTTPS 传输
- 插件声明 `requiredPermissions`，执行前由 PermissionChecker 检查授权
- 每个插件运行在宿主进程内，共享宿主 ClassLoader 的沙箱隔离
