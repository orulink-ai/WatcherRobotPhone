# 当前架构：机器人 → 局域网电脑 → PI → 手机 MCP

2026-10-18，按团队确认的实际链路更新。机器人不通过 BLE HID 模拟手机键鼠，而是通过局域网连接电脑，由电脑 PI 调用本项目 Android MCP 控制手机。

```mermaid
flowchart LR
    Robot[机器人：语音交互与反馈] <-->|局域网 / 现有设备连接| PC[电脑：机器人连接、PI AgentLoop]
    PC <-->|LAN HTTP MCP| MCP[手机：本项目 MCP 服务]
    MCP <-->|无障碍节点、截图、输入与点击| App[手机应用]
    PC <-->|按具体任务配置| Model[云端或可选本地模型]
```

- 机器人：承担自然语言交互和反馈，业务经现有 SDK/设备通道连接电脑。
- 电脑：运行 PI、任务策略、可用模型后端及 Skill 验证/复用，调用手机工具；现有 SDK Daemon 保持内容无关转发，不新增业务旁路。
- 手机：运行本项目 MCP，提供无障碍读屏和操作能力，将执行结果回传电脑。
- 网络：ROG 魔盒 Air 提供局域网路由和无线环境。日常手机操作不依赖 ADB 或持续 USB。

美团语音选购与订单取消已完成演示，视频已上传一版至飞书问卷；支付确认方式未完整展示。小红书的录制生成、复用对照和参数化经验按各自实机记录验收，具体模型、入口和计时口径分别记录。[运行入口](../experiments/pi-phone-use/README.md)、[App 闭环](research/app-workflow-loop.md)、[复用实验](results/phone-record-replay-20261018.md)。最终冻结版本仍在修改。

## 以下为前期方案，保留用于追溯

下文 HID 链路属于项目初始设计，已由上述局域网与手机 MCP 路线替代。

# WatcheRobot 接入架构

> 本页是设计提案，尚未完成集成。第三方参考实现的现状见[路线一调研](research/phone-app-esp32.md)。

## 模块分工

| 模块 | 职责 |
| --- | --- |
| 手机伴随 App | 系统授权截屏、图像尺寸与流标识、上传图像、权限和会话生命周期 |
| 手机助手 Application | 用户任务、机器人语音接入、视觉模型请求、动作校验、坐标转换、结果观察与任务状态 |
| SDK Daemon | Application 生命周期与现有连接管理；对业务帧做内容无关的路由 |
| WatcheRobot ESP32 | 接收设备动作，调度 HID 轨迹、键鼠报告、释放与状态回执 |
| 手机操作系统 | 接收标准 HID 输入并作用于当前目标 App |
| 视觉模型服务 | 根据用户任务、当前截图和记录返回单步动作 |

## 任务与动作信息流

```mermaid
flowchart TD
    Desktop[桌面用户输入] -->|Desktop channel，经 Daemon| App[手机助手 Application]
    Robot[WatcheRobot 音频与设备事件] -->|Device channel，经 Daemon| App
    Capture[手机伴随 App / 授权屏幕] -->|拟新增图像接入接口| App
    App <-->|任务、截图、记录 / 单步动作| Model[视觉模型服务]
    App -->|Application Device channel| Daemon[SDK Daemon]
    Daemon --> ESP[WatcheRobot ESP32 / HID 服务]
    ESP -->|BLE HID| Phone[手机系统 / 目标 App]
    ESP -->|执行回执，经 Daemon| App
    Phone -->|新画面| Capture
```

手机图像接口是拟新增能力，尚未选定具体传输和鉴权实现。不会把未知 SDK API 写成已存在能力。

## 架构边界

- Daemon 唯一源码归属 WatcheRobot Python SDK；本仓库不复制或维护第二份 Daemon。
- Application 由 Daemon 管理，使用其注入的 Desktop channel 与 Device channel。
- Application 运行时，Desktop 与 Device 业务帧进入当前 Application；Application 下发设备动作必须走 Device channel。
- Daemon 不根据业务消息类型解析、改写或增加直达设备的旁路。
- Application 未运行时，既有 Desktop / Device 透明转发规则保持不变。
- 连接管理 REST 与业务操作帧分离。

## 从参考实现迁移的内容

参考项目在手机中运行 AgentEngine、VisionClient、MouseExecutor 和 BLETransport；拟议方案把任务编排、模型调用与坐标转换放到 WatcheRobot Application。可借鉴板端轨迹、HID 报告与回执设计，但需要改造输入接口，不能直接覆盖现有机器人固件入口。

若后续决定让 Agent 全部留在手机，需另行定义 Application 与手机 Agent 的任务委托协议，并记录架构变更。

## 核心数据合同（待落实）

- 图像：任务会话、流标识、像素尺寸、采集/确认时间、方向与压缩数据。
- 模型动作：动作类型、归一化坐标、参数、关联观察帧。
- 设备动作：序号、校准版本、轨迹/键盘参数、取消与释放语义。
- 执行回执：序号、busy、error；业务结果由后续屏幕确认。

## 现有配置差异

前期对 WatcheRobot 本地 S3 默认配置的静态调查显示：NimBLE 最大连接数为 1，未启用安全；参考配件配置为 2 个连接并启用加密。该比较不是实机运行配置证明。集成前需要评估配网服务共存、持久配对、连接数量、Wi-Fi/BLE 共存及内存。