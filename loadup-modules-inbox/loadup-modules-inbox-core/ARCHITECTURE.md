# Inbox Core 架构

Core 实现 client 契约，保持业务消息规则和数据库访问分离。

## 用例路径

`InboxAutoConfiguration` 在 Gateway、单一 DataSource 和模块开关条件满足时创建 `InboxService` 与 `InboxReceiverService`。接收服务检查必填字段长度及站内路径，然后创建领域 `InboxMessage`。查询服务规范化分页和输入，再将 Gateway 结果转换为 DTO。

`InboxPersistenceAutoConfiguration` 在单一 DataSource 且 Database 组件可用时创建 `InboxGatewayImpl`。Gateway 所有查询按 `recipient_id` 限定；列表和未读计数排除已归档记录。入箱使用 `(recipient_id, request_key)` 唯一约束；重复键在嵌套事务保存点回滚后查找已有记录，不覆盖首次接收内容。

## 事务与迁移

Gotone `InboxDeliveryHandler` 在同一 JDBC 事务中调用 `InboxReceiver.receive` 并把 delivery 更新为 `SENT`。唯一键重试可让外层投递任务正常结束。需要共享同一 DataSource 和事务管理器；跨进程 HTTP 调用不具备该原子性。

Flyway 迁移为 `src/main/resources/db/migration/modules/inbox/V20261126000001__create_inbox.sql`。核心投递和收件人访问路径见[父级架构](../ARCHITECTURE.md)。
