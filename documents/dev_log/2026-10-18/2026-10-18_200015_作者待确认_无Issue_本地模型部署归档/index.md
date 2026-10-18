# 本地模型部署实验归档

## 基本信息

- 创建日期：2026-10-18，Asia/Shanghai。
- 建档作者：作者待确认（本机仓库未配置有效 git user.name）。
- 关联需求：将 10 月 17 日以来的本地模型部署记录单独归档到黑客松仓库。
- 类型：文档与脱敏实验数据归档；不更改模型、网络、运行服务或业务代码。
- 分支：`feat/local-model-deployment-records`，从远端 main 的固定提交建立。
- 基线：`2ad28ffe6ead70a188bbbc423224d0d89890de74`。
- 交付状态：文档已整理；发布状态以包含本档案的 Git 提交及 PR 为准，合入状态不预先宣称。

## 背景与目标

部署记录原分散于模型实验目录和运行服务目录。公开仓库需要可阅读、可追溯的研究材料，同时避免将模型服务测试误表述为黑客松 Application/HID 已完成。

本次新增独立目录，记录模型清单、时间线、环境版本、局部实测、云端对照、运维经验和验证边界；不搬运模型权重、凭据、运行日志、手机会话及机器私有配置。

## 验收与验证

- [x] 文档首页与根 README 互相可达。
- [x] 运行状态明确为 2026-10-18 19:47:52 +08:00 的历史快照。
- [x] 结构化数据保留样本数、缓存条件、计时口径及原始证据 SHA-256。
- [x] 检查相对链接、JSON 解析、数值一致性及常见敏感信息。
- [x] 检查暂存 diff；未纳入 Android 实验分支的其他未提交文件。

文档变更不适用业务 TDD；未重跑模型、Android 构建、单元测试或硬件测试。使用内容、链接、Markdown 转 HTML 结构与脱敏检查替代，不将历史测试计为本次新执行。

## 材料

- [部署资料入口](../../../../docs/local-model-deployment/README.md)
- [模型与机器快照](../../../../docs/local-model-deployment/inventory.md)
- [部署变更时间线](../../../../docs/local-model-deployment/changelog.md)
- [实测与云端比较](../../../../docs/local-model-deployment/benchmarks.md)
- [运维经验](../../../../docs/local-model-deployment/operations.md)
- [脱敏数值证据](../../../../docs/local-model-deployment/results.json)
- [原始证据校验清单](../../../../docs/local-model-deployment/evidence-manifest.json)

## 2026-10-18 变更记录

整理此前已完成的实验，保留首次失败、修复和未验证边界。仅为模型服务研究补充，不修改现有 WatcheRobot Application、SDK Daemon 或 ESP32 路线的交付状态。原始运行证据保留在实验机器，仓库发布摘要及哈希，不声称未发布的日志可以从仓库复跑得到。

## 遗留事项

- 尚需把模型服务接入黑客松 Application 的真实设备通道；不能把此前 ADB/MCP 实验视作 BLE HID 路线交付。
- 冷启动、长时间稳定性、同条件多模型质量及重启恢复仍需扩展验证。
- 未提交机器专用启动脚本，故本次无新增跨平台系统脚本入口。

验证补充：Markdown 渲染转换包含5篇文档、9张表格；浏览器本地文件预览受协议策略限制，未声称完成浏览器视觉检查，改做HTML结构与文本内容检查。
