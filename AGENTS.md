# 开发约定

- commit 使用规范、详细的中文描述。
- 业务实现遵循 TDD、代码规范与解耦；文档变化使用链接、内容与渲染检查，不编造测试结果。
- 开始任务前阅读 `documents/dev_log/README.md` 与 `temp_log.md`，查找并维护对应任务档案。
- 对外可运行系统脚本同时提供同语义的 `.ps1` 与 `.sh` 入口；共用跨平台核心，公共入口不能写死单平台。
- SDK Daemon 唯一源码在 WatcheRobot Python SDK。本仓库实现 Application，不复制另一份 Daemon。
- Application 设备业务下发走其 Device channel；Daemon 业务路由与消息内容无关，不增加按业务类型直达设备的旁路。
- 公开资料区分计划、静态调研、实测与交付；未经真机验证不得称已完成。
- 不提交密钥、个人路径、临时日志或下载的未审查第三方源码。
