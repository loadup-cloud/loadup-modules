# Gotone Client 架构

Client 将通知生产者与渠道投递实现解耦。

## 对外契约

- `GotoneFacade.submit` 接收 source、可选 senderId 和 `Gotone.Submit`，返回 `Receipt`；契约要求业务事务提交后调用。
- `GotoneFacade.status` 按 source 和 requestId 读取请求及投递结果。
- `GotoneOperations` 提供服务路由草稿与发布、预览、供应商账号及短信绑定管理，以及查询、重试和不确定结果核对。
- `Gotone` 容纳请求、回执、投递结果、路由、账户和渠道定义。

生产者依赖 client；core 实现契约并调度异步投递；web 暴露服务间和管理入口。`IN_APP` 最终委托 Inbox 的 `InboxReceiver`
，同进程装配时传递 deliveryId 作为收件幂等键。更完整的执行路径和 Inbox 同事务要求见[父级架构](../ARCHITECTURE.md)
与[Inbox 架构](../../loadup-modules-inbox/ARCHITECTURE.md)。
