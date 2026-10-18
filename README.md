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

现场视频：[京东创造营现场｜让 AI 操作真实手机](https://www.xiaohongshu.com/discovery/item/6ad3c336000000001402ebd7?source=webshare&xhsshare=pc_web&xsec_token=ABBoFwwcmUXezu3hfE3BT0LQlge7vTDLIcNUBMpKQxugQ=&xsec_source=pc_share)。

- **机器人语音操作美团**：提出晚饭、汉堡需求后，手机执行商家搜索、选购和订单流程；演示显示“支付成功”。用户随后语音要求取消，最终显示“订单已取消”。实机演示已上传一版至飞书问卷，支付确认方式未完整拍出。
- **手机示教到 Skill**：在 Orulink Control 录制并提交，电脑完成 PI 验证、生成、复用与安装，再回报手机结果。[实机闭环记录](docs/research/app-workflow-loop.md)。
- **固定流程复用**：小红书同一任务的 Jev 与 Skill 模式各3轮均成功；中位全程从22.537秒降至15.594秒，减少30.8%，每轮模型调用14→0。结果仅覆盖该固定任务、版本和小样本。[对照实验](docs/results/phone-record-replay-20261018.md) · [小红书视频：录一遍手机操作，让 AI 照着完成整套流程](https://www.xiaohongshu.com/discovery/item/6ad3cbb0000000000202bff4?source=webshare&xhsshare=pc_web&xsec_token=ABBoFwwcmUXezu3hfE3BT0LfZssYEaD_U-VYZ149DwzNo=&xsec_source=pc_share)。
- **参数化任务**：已实测更换搜索词后只收藏、按精确标题取消收藏，点赞保持不变；该模式仍由模型逐步判断。[经验辅助与验收](docs/research/phone-experience.md)。

美团演示由团队确认完成；它与仓库中的小红书测速分别留证，不能混用任务耗时或模型配置。参赛代码以 `final` 标签对应的40位commit固定；后续迭代另开分支，不改变该次评奖版本。

## 现场技术讨论

![创作营现场白板：手机控制链路、模型决策与示教复用讨论](docs/media/technical-discussion-whiteboard-20261018.jpg)

48小时创作过程中的技术讨论。团队围绕手机控制链路、模型决策延迟和示教复用展开探索，逐步收敛到局域网 MCP 控制与 Skill 复用方案。板书包含候选方向，实际实现以当前架构和实测记录为准。

## 使用条件与边界

需要解锁的 Android 手机、已开启的无障碍/MCP 服务、配置好的电脑和可互通局域网。日常 PI 手机操作通过 LAN MCP，无需持续连接 USB；ADB 仅用于部分历史安装、诊断和实验准备。ROG 魔盒 Air 提供局域网路由与无线网络，Mac Studio 用于可选本地模型研究。

PI、任务策略、MCP 和 Skill 执行在本地，在线模型通过云 API 调用。录制生成入口目前限定已验证示例，经验模式覆盖小红书搜索及点赞/收藏任务族；不代表任意应用或任意录制都可直接执行。长时后台稳定性和更多机型仍需验证，详细失败记录见各实测文档。

## 运行与交付

本次赛道为 **AI设备联动**。使用现有WatcheRobot、Android手机与开发电脑，现场已配置设备可演示；依赖局域网和模型服务，暂无无需配置的公网体验地址。评审材料包含两份飞书说明及问卷中的实际演示视频。

手机开启本项目MCP与无障碍服务，解锁并保持与电脑网络互通。电脑需Python 3.11+、PI CLI（已验证1.3.0）及[模块依赖](experiments/pi-phone-use/requirements.txt)，按[配置与运行说明](docs/research/pi-phone-terminal.md)准备私有MCP/模型配置和启动入口。现场已配置电脑运行 `pi-phone` 后，在PI内逐条输入：

```text
/phone-status
读取当前手机屏幕
小红书搜索“小狮日记”，停在搜索结果页
```

新电脑的安装与实验环境见[PI模块说明](experiments/pi-phone-use/README.md)，Android构建见[后端说明](docs/android-backend/README.md)。上述步骤用于连接与搜索验证；机器人语音演示需配套的既有SDK/Application环境，不把手机终端启动等同于完整机器人配置完成。

模型与API：实时手机候选决策使用OpenRouter Jev，刷新观察后可由DeepSeek兜底；固定Skill本地命中不请求模型，语音服务按演示配置运行。本地/云端分工见上文与运行记录。

开源基础为[Android Remote Control MCP](https://github.com/danielealbano/android-remote-control-mcp)（MIT）、PI和既有WatcheRobot SDK。开赛前已有机器人硬件、SDK及上游基础能力；48小时新增手机录制、任务队列、PI工作流、Skill生成复用、经验模式及机器人手机联动。归属见[第三方说明](THIRD_PARTY.md)，详细前后对照见提交的作品说明表。
