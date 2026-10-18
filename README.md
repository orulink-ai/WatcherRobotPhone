# WatcherRobotPhone

**让 WatcheRobot 从“陪你聊天”，走向“帮你操作手机”。**

京东 48 小时创作营项目。用户向桌面机器人提出自然语言需求，机器人通过局域网连接电脑，由电脑上的 PI 调用本项目的手机端 MCP，控制 Android 应用并读取操作结果。重复流程可以通过示教生成 Skill，再定位、验证和复用。

## 当前技术链路

```mermaid
flowchart LR
    User[用户自然语言需求] <-->|语音交互| Robot[WatcheRobot 桌面机器人]
    Robot <-->|局域网连接| Computer[电脑：机器人连接与 PI 任务处理]
    Computer <-->|局域网 HTTP MCP：读屏、输入、点击、结果| Phone[Android：本项目 MCP 服务]
    Phone <-->|无障碍操作与界面状态| Apps[美团 / 小红书等手机应用]
    Computer <-->|在线模型请求| Cloud[Jev / DeepSeek 等模型后端]
```

机器人负责语音交互与反馈，电脑上的 PI 负责组织任务和调用工具；手机端运行本项目 MCP，通过无障碍服务操作应用、读取节点和截图。手机执行后的界面状态回传电脑，供后续决策和结果验证。机器人业务连接复用现有 SDK/设备通道，MCP 是电脑到手机的工具连接。

**当前控制路径使用局域网与手机 MCP。ESP32 BLE HID、USB HID 和机器人摄像头拍屏属于前期调研，不承担本次手机操作。** 各演示所用模型与配置按对应实测记录区分；本地 Skill 命中时可跳过模型请求。

## 已展示的能力

- **机器人语音操作美团**：提出晚饭、汉堡需求后，手机执行商家搜索、选购和订单流程；演示显示“支付成功”。用户随后语音要求取消，最终显示“订单已取消”。实机演示已上传一版至飞书问卷，支付确认方式未完整拍出。
- **手机示教到 Skill**：在 Orulink Control 录制并提交，电脑完成 PI 验证、生成、复用与安装，再回报手机结果。[实机闭环记录](docs/research/app-workflow-loop.md)。
- **固定流程复用**：小红书同一任务的 Jev 与 Skill 模式各3轮均成功；中位全程从22.537秒降至15.594秒，减少30.8%，每轮模型调用14→0。结果仅覆盖该固定任务、版本和小样本。[对照实验](docs/results/phone-record-replay-20261018.md)。
- **参数化任务**：已实测更换搜索词后只收藏、按精确标题取消收藏，点赞保持不变；该模式仍由模型逐步判断。[经验辅助与验收](docs/research/phone-experience.md)。

美团演示由团队确认完成；它与仓库中的小红书测速分别留证，不能混用任务耗时或模型配置。最终冻结版本仍在修改，当前 main 不自动代表最终提交版。

## 评委与开发者入口

| 内容 | 入口 |
| --- | --- |
| 当前机器人—电脑—手机架构 | [技术架构](docs/architecture.md) |
| 手机控制、依赖与运行入口 | [PI 手机控制](experiments/pi-phone-use/README.md) |
| 日常 pi-phone 的使用与排查 | [终端入口](docs/research/pi-phone-terminal.md) |
| App 录制、一键作业、生成与发布 | [App 闭环](docs/research/app-workflow-loop.md) |
| 固定流程的速度、模型调用和失败记录 | [复用实验](docs/results/phone-record-replay-20261018.md) |
| 可变搜索词与目标帖 | [经验模式](docs/research/phone-experience.md) |
| Android 构建与后端说明 | [Android 说明](docs/android-backend/README.md) |
| 可选本地模型部署与对照 | [部署档案](docs/local-model-deployment/README.md) |
| 开源底座和历史来源 | [第三方说明](THIRD_PARTY.md) |

## 使用条件与边界

需要解锁的 Android 手机、已开启的无障碍/MCP 服务、配置好的电脑和可互通局域网。日常 PI 手机操作通过 LAN MCP，无需持续连接 USB；ADB 仅用于部分历史安装、诊断和实验准备。ROG 魔盒 Air 提供局域网路由与无线网络，Mac Studio 用于可选本地模型研究。

PI、任务策略、MCP 和 Skill 执行在本地，在线模型通过云 API 调用。录制生成入口目前限定已验证示例，经验模式覆盖小红书搜索及点赞/收藏任务族；不代表任意应用或任意录制都可直接执行。长时后台稳定性和更多机型仍需验证，详细失败记录见各实测文档。

## 仓库历史与第三方基础

Android MCP 基础源自 `danielealbano/android-remote-control-mcp`。本项目将其源码快照整理为一次 `chore: 初始化提交`，保留 MIT 许可证、版权和来源；后续功能、修复及文档提交独立保留。原完整历史保存在归档分支 `archive/history-before-upstream-squash-20261018`，上游基础能力不计为本队48小时原创成果。

前期 ESP32 HID、摄像头和 Phone Agent 调研保留在 [研究资料](docs/research/README.md)，用于说明路线演进；其中历史架构不代表当前运行链路。
