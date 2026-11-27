# Gotone Core 架构

`GotoneAutoConfiguration` 在 Task 可用时组装受理、运营、发送、Provider 和持久化服务。应用层以已发布的服务修订解析渠道和模板，生成渲染快照，再与目标及
Task 一起提交。Spring Data JDBC、显式 SQL 和 Task 共用宿主事务。

`app.integration` 定义 Provider 契约及共用传输能力，供应商适配器按 `sms`、`email`、`push` 和 `webhook` 分包。
执行时各渠道 Handler 解析启用的账号。Push 和 Webhook 由 Framework Task 并行调度，每个目标单独保留尝试；
SMS 和 Email 按账号顺序尝试，任一账号受理即停止。站内投递通过 `InboxReceiver` 与 `SENT`
更新同事务提交。外部渠道先保存尝试记录，再在数据库事务外传输，根据确认结果写入投递状态。未知结果交由运营核对。路由与投递的完整状态、幂等和回执规则见 [Gotone 总体设计](../ARCHITECTURE.md)。

[Core 接入](README.md) · [Inbox 事务边界](../../loadup-modules-inbox/ARCHITECTURE.md)
