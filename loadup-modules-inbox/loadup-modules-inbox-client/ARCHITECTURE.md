# Inbox Client 架构

Client 是生产者、应用服务和实现之间的 Java 契约。

- `InboxReceiver.receive` 接收稳定 request key、收件人、可选发送者、类别、已渲染标题正文和站内 action URL。调用方在投递重试时复用同一 key；有 JDBC 外层事务时，实现在该事务中写入。
- `InboxFacade` 只暴露按收件人查询、未读计数、单条或全部已读、归档操作。调用者必须提供收件人身份，Web 层从认证主体取得该值。
- `InboxMessageDTO`、命令和查询对象承载 API 数据，不泄漏数据库映射对象。

Client 不依赖存储实现。Core 实现两个 Facade，Web 将经过认证的请求映射到 `InboxFacade`。投递和收件人隔离路径见[模块架构](../ARCHITECTURE.md)。
