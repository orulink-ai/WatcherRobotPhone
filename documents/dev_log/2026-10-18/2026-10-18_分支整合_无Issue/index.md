# 黑客松分支整合

## 需求与范围

2026-10-18，Asia/Shanghai。用户授权合并各开发分支，不以运行质量作为合并门槛。归档作者以 Git 提交记录为准。

- 本地模型部署文档已通过 PR #2 合入 main。
- 手机体验分支与 Android 导入分支发生 Pi provider/worker/参数/说明冲突；组合两侧功能后，PR #1 已合入导入分支。
- 本次将完整 Android / Pi 实验实现合入黑客松 main，保留原参赛资料。
- 其他工作目录中的未提交改动不在远端分支范围内，本次不提交、不覆盖。

## 冲突处理

- 根 README 保持黑客松项目入口，Android 原说明保存在 [Android 后端说明](../../../../docs/android-backend/README.md)。
- 合并两侧 gitignore，保留原凭据和构建产物忽略规则。
- 解决大小写不敏感文件系统的双重路径：保留 main 的 docs/project.md、docs/architecture.md；导入分支同名大写说明移动到 docs/android-backend，并更新当前规则入口。
- Android、MCP 和 Pi 功能已合入不代表 ESP32 BLE HID / SDK Application 的全链路验证完成。

## 验证与边界

检查冲突标记、大小写重复路径、diff格式和冲突相关 Python 文件语法。按用户明确要求，不以运行质量或既有测试失败阻止合并；本次不重新运行模型、Android全套、真机、并发或端到端测试。不得将此次合并写成质量验收通过。

保留全部提交祖先关系，远端通过带期望 head 的合并/引用更新避免覆盖并行更新。不删除分支，不改模型服务与网络配置。
