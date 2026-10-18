# 本地模型部署记录

覆盖北京时间 **2026-10-17 00:00 至 2026-10-18 19:47:52** 的实验机器部署与验证记录。本文是阶段归档，不是实时监控。

**已验证模型服务层：DeepSeek 双机推理、Qwen 单机 BF16 多模态、StartLux 决策 API，以及部分 Pi / Android MCP 实验调用。** 黑客松的 WatcheRobot Application、SDK Device channel、ESP32 BLE HID 端到端链路尚不能因此标记为完成。

| 阅读目的 | 入口 |
| --- | --- |
| 当前有哪些模型、哪些在运行 | [模型清单与快照](inventory.md) |
| 从 10 月 17 日以来变更了什么 | [部署变更时间线](changelog.md) |
| 本地、官方 API 有多快，准确性如何 | [实测结果与限制](benchmarks.md) |
| 加载超时、缓存、保活、切换的经验 | [运维与复现边界](operations.md) |
| 查看可计算的脱敏汇总 | [results.json](results.json) |
| 核对原始实验来源与固定版本 | [证据清单](evidence-manifest.json) |

## 快照结论

- 主服务：官方 native FP4/FP8 DeepSeek-V4.1-Flash-1017，经两台 M3 Ultra 的 JACCL 后端计算。
- 备选：Qwen3.8-Flash-Next 官方 BF16 已通过单机图文、工具和 Pi 接入验证；快照时停用。
- 小模型：StartLux 27B AutoThink 与 4B Instant 决策服务在线；4B AutoThink、UI-Venus-2-9B 保留实验权重。
- 机器：本机 M3 Ultra、512 GiB 统一内存；磁盘可用约 709 GiB。
- 部署成功、截图单步测试成功、手机连续任务成功是不同层级，分别记录，不合并为一个成功率。

## 与黑客松工程的关系

模型服务是未来手机助手 Application 的可选后端。原有[Application / Daemon / ESP32 架构](../architecture.md)不变：模型返回的动作仍需校验、坐标映射、Device channel 下发和观察验证。这里没有复制 SDK Daemon，也没有把 Android MCP 的实验执行链路冒充 ESP32 HID 链路。

原有 main 分支的“整体集成待验证”状态仍然成立。Android 实验适配此前独立发布在 `codex/import-android-remote-control` 分支；本次不将其全部源码混入 main。

## 公开归档范围

本目录只保存文档、数值摘要和来源校验信息。不包含权重、访问密钥、个人绝对路径、内网地址、设备序列号、手机截图、原始任务会话或完整服务日志。原始证据尚未作为公共制品发布；其哈希供持有原始文件的人核对，不表示任何人仅凭本目录就能复现所有部署。
