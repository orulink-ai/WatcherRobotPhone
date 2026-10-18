# 4B Instant 局域网决策后端

当前服务：`http://192.168.50.26:8094`，模型 `startlux-4b-instant`，实际权重为 `StartLux-Decision-v1.1-4B-Instant-MLX-8bit`。按部署要求无 API Key。端口只绑定本机的局域网 IPv4 地址，使用 HTTP/1.1 长连接；没有 TLS。这里是模型决策 API，不是手机 MCP 服务，也不是 OpenAI chat completions。

完整的请求字段、响应结构、多问题示例、错误码和连接参数见 [API 接口文档](API.md)。

## 接口

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/health` | 检查网关与 Instant 模型是否就绪 |
| GET | `/v1/models` | 上游原生模型清单 |
| POST | `/v1/systemone` | `state` + `questions` 决策 |
| POST | `/v1/decisions` | 同一协议别名 |
| POST | `/api/alpha/decisions` | 对应当前项目 Jev 请求路径的兼容别名 |
| POST | `/api/v1/decisions`, `/api/v1/systemone` | OpenRouter 风格路径别名 |

请求可省略 `model`，或使用 `startlux-4b-instant`。不要继续发送 `typesafe/jev-1.14`：未部署的模型名返回 422，避免把本地模型误记为 Jev。网关转发原生 `answers`、`usage`、`model` 和请求 ID；支持同次请求的多个问题，包括项目的 operation/target heads。Instant 强制 `think_mode=never`、关闭 none 检查，API 保持选择于已提供的候选。

单层调用示例（从仓库根目录执行）：

```sh
curl --noproxy '*' http://192.168.50.26:8094/health
curl --noproxy '*' http://192.168.50.26:8094/v1/systemone \
  -H 'Content-Type: application/json' \
  --data-binary @experiments/startlux-backend/request.json
```

响应的 `answers.action.choice` 是候选键，如 `OPEN_APP:settings`，`probabilities` 包含各候选概率。模型不负责直接执行手机动作，项目仍使用现有 MCP 解析与执行所选动作。

## 项目接入

```sh
source experiments/startlux-backend/client.env.example
```

然后在现有 PI 启动器选择 `startlux-instant`，例如将现有 `run_pi_trial.py` 命令的 provider 参数设为该值，保留任务自己的设备、trial ID 等参数。

`experiments/pi-phone-use/local_providers.py` 默认仍只连接 loopback；显式设置 `PHONE_BENCH_STARTLUX_ALLOW_LAN=1` 后，允许 RFC1918 私网数字 IP。无须 OpenRouter 密钥，系统代理不会用于本接口，客户端持久连接不设置空闲过期，不自动重放失败的动作请求。本地适配器保持 flat 单动作问题；调用方若已有 Jev heads 构造器，也可直接使用上面的兼容路径和本地模型名。

## 启停

在模型宿主机，从仓库根目录启动：

```sh
experiments/startlux-backend/start.sh --host 192.168.50.26
```

脚本默认复用已运行的 `127.0.0.1:8091` Instant 进程，不重复加载权重。未运行时，使用现有 StartLux 环境与本地权重启动 worker，然后启动 LAN 网关。可用 `STARTLUX_ROOT`、`STARTLUX_MODEL`、`STARTLUX_RUNTIME` 覆盖宿主机路径；默认值对应本次部署。省略 `--host` 时自动读取 en0 的局域网 IP，IP 改变后也要更新客户端 URL。

查询状态或停止网关（停止不影响已被其他项目复用的 worker）：

```sh
/Users/studio/Documents/Codex/2026-10-17/new-chat/work/startlux/.venv/bin/python \
  experiments/startlux-backend/manage.py status \
  --runtime-dir /Users/studio/Documents/Codex/2026-10-17/new-chat/outputs/startlux-lan
# 将 status 改为 stop 即可停止网关
```

进程在当前登录会话中后台运行，不依赖本次命令窗口；未设置开机自动启动。状态、PID 与内容脱敏访问日志位于 runtime 目录。模型请求并发上限 1，忙时立即 429 + Retry-After:1，不排队也不自动重试。网关与 worker 使用持久 HTTP 客户端。该上限不隔离同一 worker 上来自其他本机项目的请求，上游忙时也可能返回 429。

## 已完成验证（2026-10-18）

部署时通过 GitHub API 核对功能分支头为 `544859afa5386b676897f2dc0ed00c1ed459d916`，与当时本地 HEAD 一致。Git HTTPS fetch 超时，未发生远端代码更新。验证结果和原始日志保存在宿主机 runtime 目录。

- 30 项离线测试通过：网关转发、默认拒绝非 loopback、显式允许私网、未知模型 422、无认证调用、Jev 路径别名、429 忙时不执行、现有 worker policy。
- 真实模型：健康、模型清单、三种路径的 2+3 选择题、项目实际 `_jev_payload(..., style='heads')` 的双问题，以及 `StartLuxClient` 连续两次调用均通过。
- fixture 中项目客户端两次约 95ms / 93ms；这是极短输入的 API 连通性验证，不是完整手机任务或 AndroidControl 的性能数据。
- 已从宿主机通过 `192.168.50.26` 这一 LAN 地址验证；尚未用另一台局域网设备实测。宿主机防火墙查询为关闭，未更改防火墙或路由器。
- 本次测试未发送或执行任何真机动作。

完整原始结果：runtime 目录的 `smoke-results.json`、`tests.log`、`service.json`、`access.jsonl`、`revision-check.json`。

复验 API（不操作手机）：

```sh
/Users/studio/Documents/Codex/2026-10-17/new-chat/work/startlux/.venv/bin/python \
  experiments/startlux-backend/smoke.py \
  --url http://192.168.50.26:8094 --output /tmp/startlux-lan-smoke
```
