# StartLux 4B Instant 决策 API

## 连接信息

| 项目 | 当前部署 |
|---|---|
| Base URL | `http://192.168.50.26:8094` |
| 模型 ID | `startlux-4b-instant` |
| 权重 | `StartLux-Decision-v1.1-4B-Instant-MLX-8bit` |
| 认证 | 无，省略 Authorization |
| 传输 | HTTP/1.1，支持长连接；未启用 HTTPS |
| 推理后端 | 本机 MLX，通过 loopback worker 转发 |
| 请求格式 | `Content-Type: application/json`，UTF-8 |

局域网 IP 改变后需同步修改客户端 URL。本 API 返回决策，手机动作仍由 WatcherRobotPhone 的 MCP 执行。

## 路由

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 网关及指定 Instant worker 的就绪状态 |
| GET | `/v1/models` | 原生 TypeSafe 风格模型清单 |
| POST | `/v1/systemone` | 推荐决策入口 |
| POST | `/v1/decisions` | 相同决策协议 |
| POST | `/api/alpha/decisions` | 项目现有 Jev 路径的兼容入口 |
| POST | `/api/v1/decisions` | 相同决策协议 |
| POST | `/api/v1/systemone` | 相同决策协议 |

决策入口不支持 query 参数。所有 POST 别名转到同一模型，不会按路径或 `model` 参数加载其他权重。

### GET /health

就绪时 HTTP 200：

```json
{
  "status": "ok",
  "model": "startlux-4b-instant",
  "backend": "mlx",
  "upstream_ready": true
}
```

指定 worker 未就绪、不可连接或模型不匹配时返回 HTTP 503，`status` 为 `unavailable`。未就绪响应的其他字段可能省略。

### GET /v1/models

返回 worker 的原生 `{"models": [...]}`，条目包括 `name`、`description`、`release_date`。这是决策 API 的清单格式，不是 chat completions 的 `data` 数组。

## POST 决策请求

| 字段 | 类型 | 说明 |
|---|---|---|
| `model` | string，可省略 | 推荐 `startlux-4b-instant`；另接受 `startlux-instant` 或完整权重名。其他值返回 422 |
| `state` | JSON | 当前任务、可见 UI 控件、历史动作等观察信息；文本控件树不需要 `images` |
| `questions` | object | 以调用方定义的问题 ID 为键；响应保留这些 ID |
| `questions.<id>.type` | string | 手机操作使用 `choice`；原生 worker 也支持 `noul` 和 `score` |
| `questions.<id>.instructions` | string | 选择题必须提供的问题说明 |
| `questions.<id>.criteria` | object | 选择题的候选 ID → 描述；项目客户端要求 1–255 个非空字符串 ID，描述为字符串 |
| `return_details` | boolean，可省略 | true 时返回各问题的原生计时等 details |
| `images` | array，可省略 | 原生模型支持的图片输入；本次 phone-use 接入与连通性验证使用纯文本 |

网关在请求顶层固定 `think_mode=never`、`none_check=false`、`allow_none=false`。不要在问题内部设置 `allow_none`：原生协议的问题级设置能够覆盖顶层设置，而项目的动作选择验证器只接受已提供的候选 ID。Instant 不返回生成式思考文本。

`state` 和候选描述是模型输入。一个问题可以直接使用 `CLICK:node-1`、`OPEN_APP:settings` 等动作 ID，调用方收到 ID 后自行映射到本轮观察中的真实控件与参数。

### 单问题示例

从仓库根目录执行：

```sh
curl --noproxy '*' http://192.168.50.26:8094/v1/systemone \
  -H 'Content-Type: application/json' \
  --data-binary @experiments/startlux-backend/request.json
```

请求文件内容：

```json
{
  "model": "startlux-4b-instant",
  "state": {
    "goal": "Open the Settings application.",
    "screen": {"app": "launcher", "visible_apps": ["Settings", "Clock"]},
    "history": []
  },
  "questions": {
    "action": {
      "type": "choice",
      "instructions": "Select the next offered action that advances the goal.",
      "criteria": {
        "OPEN_APP:settings": "Open the Settings application",
        "OPEN_APP:clock": "Open the Clock application",
        "WAIT": "Wait for loading",
        "BLOCKED": "Stop because no offered action can progress"
      }
    }
  },
  "return_details": true
}
```

响应结构示例，概率和 token 数仅用于说明字段：

```json
{
  "model": "startlux-4b-instant",
  "answers": {
    "action": {
      "type": "choice",
      "choice": "OPEN_APP:settings",
      "confidence": 0.92,
      "probabilities": {
        "OPEN_APP:settings": 0.94,
        "OPEN_APP:clock": 0.02,
        "WAIT": 0.02,
        "BLOCKED": 0.02
      }
    }
  },
  "usage": {"input_tokens": 120, "output_tokens": 0}
}
```

每个选择题的 `probabilities` 覆盖其候选 ID，总和约为 1，浮点计算可产生微小偏差。`confidence` 表示选择分布的集中程度，不代表现实操作成功率。`return_details=true` 时还会出现 `details` 等原生附加字段；客户端应允许额外字段。

### 多问题：Jev operation/target 形状

```json
{
  "model": "startlux-4b-instant",
  "state": {"goal": "Open Settings", "visible_apps": ["Settings", "Clock"]},
  "questions": {
    "operation": {
      "type": "choice",
      "instructions": "Choose the next operation.",
      "criteria": {"OPEN_APP": "Open an application", "WAIT": "Wait for loading"}
    },
    "open_app_target": {
      "type": "choice",
      "instructions": "Assuming OPEN_APP is selected, choose its target.",
      "criteria": {"OPEN_APP:settings": "Settings", "OPEN_APP:clock": "Clock"}
    }
  }
}
```

POST 到 `/api/alpha/decisions` 或 `/v1/systemone` 均可。响应中包含 `answers.operation` 与 `answers.open_app_target`。只有 operation 为 `OPEN_APP` 时才使用对应 target；其他问题的答案不是额外执行指令。多个问题由原生模型分别处理，不应假定一次 HTTP 就只有一次模型计算。当前项目本地 `StartLuxClient` 使用单问题 flat 形状。

将 Jev 请求改接本服务时，同时替换 URL 和 `model`；保留原来的 state/questions 结构。继续发送 `typesafe/jev-1.14` 会得到 422。

## 限制与错误处理

| 项目 | 行为 |
|---|---|
| 请求体 | 网关最多 8 MiB，超过返回 413 |
| 并发 | 网关只接收 1 个进行中的决策；忙时立即返回 429，不排队 |
| 模型等待超时 | 网关上游调用总超时 90 秒 |
| 连接复用 | 入站及网关到 worker 的空闲连接保持时间为 300 秒；项目客户端不设置连接池空闲过期 |
| 自动重试 | 网关和项目模型客户端均不自动重试 |
| worker 共享 | 同一 worker 的其他本机调用也可能触发上游并发限制 |

| HTTP | 含义 |
|---|---|
| 200 | 本次决策或查询成功；不等于动作已执行 |
| 400 | 决策 URL 带有不支持的 query 参数 |
| 404 / 405 | 路径不存在 / 方法错误 |
| 413 | 请求体超过网关限制 |
| 422 | JSON 不是 object、模型名不支持，或原生模型拒绝字段、问题、候选、输入长度等 |
| 429 | 网关或 worker 正忙；响应包含 `Retry-After: 1` |
| 502 | worker 无法连接或模型引擎失败 |
| 503 | 健康检查未就绪 |
| 504 | 网关等待模型响应超时；不保证上游已停止计算 |

错误响应包含 `error` 字符串，上游错误还可能带 `detail`。例如：

```json
{"error": "model not served; use startlux-4b-instant"}
```

返回头可包含原生 `x-request-id` 和 `x-typesafe-request-id`，可用于定位 worker 请求。读取响应后再解析并验证候选 ID，不要根据 HTTP 200 直接执行任意字符串。需要重试时由调用方明确安排；刷新观察后重新构造候选，沿用项目对动作失败、未派发和引用失效的既有处理。

## 项目配置与自测

```sh
source experiments/startlux-backend/client.env.example
# 使用现有启动器的 startlux-instant provider；设备和任务参数沿用实际任务配置。
```

`PHONE_BENCH_STARTLUX_ALLOW_LAN=1` 显式允许私网数字 IP；默认仍只允许 loopback。无需 OpenRouter API Key。项目 HTTP 客户端关闭系统代理与自动跳转，并保持连接复用。

```sh
# 在装有 StartLux / MLX 依赖的 Python 环境中执行；只调用模型，不操作手机。
python experiments/startlux-backend/smoke.py \
  --url http://192.168.50.26:8094 --output /tmp/startlux-lan-smoke
```

`smoke.py` 检查健康、模型清单、多个路由、operation/target 多问题和项目客户端连续调用，保存原始输入、响应与网络事件。启停及宿主机依赖见 [部署说明](README.md)。
