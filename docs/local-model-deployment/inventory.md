# 模型清单与部署快照

快照：**2026-10-18 19:47:52 +08:00**。机器和 health 状态来自当时的只读检查；此后运行状态可能变化。单位 GiB = 2³⁰ 字节。

## 硬件与存储

本机为 Mac Studio / Apple M3 Ultra，32 核 CPU、512 GiB 统一内存，macOS 26.6。DS 部署使用另一个相同内存规格的 M3 Ultra 节点。快照磁盘可用约 709.0 GiB，下表主要模型目录合计占用 1069.5 GiB；这些是磁盘分配，不能当成推理内存。

| 模型 | 格式 | 目录占用 GiB | 快照状态 | 验证范围 |
| --- | --- | ---: | --- | --- |
| DeepSeek-V4.1-Flash-1017 | 官方 native FP4/FP8 | 475.4 | 双机主服务在线 | 图文、多轮、流式、工具、Pi、LAN |
| Qwen3.8-Flash-Next | 官方 BF16 | 341.1 | 已验证，停用 | 单机图文定位、单步动作；后续 API/Pi |
| UI-Venus-2-9B | BF16 | 17.6 | 离线实验完成 | 同图三控件定位及单步动作 |
| StartLux 27B AutoThink | MLX 8bit | 31.1 | 本机决策服务在线 | 专用决策与 AndroidControl 离线评测 |
| StartLux 4B Instant | MLX 8bit | 4.8 | 本机与 LAN 网关在线 | 双向注意力决策、项目客户端实验 |
| StartLux 4B AutoThink | MLX 8bit | 4.8 | 保留，未常驻 | 同批离线决策对照 |
| Llama-3.2-3B-Instruct | 4bit | 1.7 | 既有保留 | 本期无新增完整服务证据 |
| Llama-3.3-70B-Instruct | 8bit | 69.8 | 既有保留 | 本期无新增完整服务证据 |
| Qwen3-235B-A22B-Instruct-2507 | 4bit，纯文本 | 123.2 | 既有保留 | 不支持图片，不是本次多模态 Qwen |

另用 Qwen2.5-0.5B-Instruct-4bit 做初期 MLX 冒烟，保留在共享缓存，未常驻。Llama 与 Qwen3 235B 的目录早于本期，不能列为新增下载。

DS 净文件为 510311045870 字节（475.26 GiB），48 个权重分片 / 台、84 个文件 / 台；Qwen 净权重为 360000192888 字节（335.28 GiB）、131 个分片。Qwen 目录还留有 4 个下载临时分片，约 5.81 GiB，未清理，因此目录占用大于净权重。

## 服务层

| 服务 | 实验端口 | 快照状态与访问约束 |
| --- | --- | --- |
| DS 私有引擎 | 本机 8100 | ready；2 ranks；JACCL；prefill=512 |
| DS 兼容 API 网关 | LAN 8101 | Bearer 认证；文本、图片、工具、SSE |
| StartLux 4B Instant | 本机 8091 | 正确的双向注意力决策路径 |
| StartLux 27B AutoThink | 本机 8092 | 因果注意力；默认支持自动思考 |
| StartLux LAN 适配 | LAN 8094 | 实验中按用户要求未启用 Key；非公开部署建议 |
| EXO 管理后端 | 52415 | 两节点可见、0 个原生模型实例 |
| 状态页 | 本机 52416 | 显示既有 DS 服务健康信息 |

Qwen 曾复用 8100/8101，停用后地址由 DS 接管；必须查询 health 返回的模型名，不能按端口识别模型。EXO 当前未托管 DS 推理；真实计算仍由 JACCL 完成。

Pi 全局默认是官方 DS provider/model；本地 DS 使用专用 provider 或启动配置。安装 Pi 不代表所有会话均走本地。

## 环境隔离

| 环境 | 固定组件 |
| --- | --- |
| Qwen / UI-Venus | MLX 0.32.4、MLX-VLM 0.7.8、Transformers 5.20.1、Torch 2.14.1、Torchvision 0.29.1 |
| StartLux | startlux-decide 1.1.0；MLX 0.32.4、MLX-VLM 0.7.8、Transformers 5.20.1 |
| DS 双机 | MLX 0.32.3；MLX-VLM 0.7.4 / 实验 PR2406；运行时固定 commit 见证据清单 |
| 网关与运维 | 独立 Python 环境；不覆盖 DS/Qwen 的模型依赖 |
| Agent / EXO | Pi 1.3.0；EXO 官方签名运行时 1.0.71 |

DS/Qwen 巨型模型按互斥切换管理，未验证两者同时常驻。不能用进程 RSS 代替 Metal 分配峰值。
