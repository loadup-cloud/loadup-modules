# Gotone Client

`loadup-modules-gotone-client` 发布通知提交、状态查询和管理接口，Maven 坐标为 `io.github.loadup-cloud:loadup-modules-gotone-client`。业务生产者通常使用 `GotoneFacade`：在业务事务提交后的任务中调用 `submit(source, senderId, command)`，并以相同 `requestKey` 重试；用 `status(source, requestId)` 查询该来源的投递状态。

路由和服务配置管理契约由 `GotoneOperations` 提供，供管理端实现使用。命令、收件人和回执定义在 `Gotone`。client 不包含 Web 控制器或投递实现。提交示例和部署约束见[模块 README](../README.md)，投递状态机见[架构设计](../ARCHITECTURE.md)。
