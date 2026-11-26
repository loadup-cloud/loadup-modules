# Approval Client

`loadup-modules-approval-client` 发布审批模块的 Java 接入契约，供业务应用编译期依赖。Maven 坐标为 `io.github.loadup-cloud:loadup-modules-approval-client`。

核心入口是 `io.github.loadup.modules.approval.client.ApprovalFacade`，覆盖模板、目录候选人、发起、详情、待办、换人、决定、撤回和结果重投。业务代码注入该接口后，可调用 `start(caller, app, commandKey, command)` 发起审批，并用 `detail(caller, app, id)` 查询。`Approval` 中的 record 定义命令与返回数据；结果处理和身份目录扩展点分别为 `ApprovalOutcomeHandler`、`ApprovalDirectory`。

本层不包含自动装配、持久化或 HTTP 控制器。宿主部署时还需按场景引入 core，HTTP 接入再引入 web。整体接入步骤见[模块 README](../README.md)，业务规则和调用协议见[架构设计](../ARCHITECTURE.md)。
