# Inbox Core

`loadup-modules-inbox-core` 实现消息入箱和收件人操作，Maven 坐标为 `io.github.loadup-cloud:loadup-modules-inbox-core`。它依赖 client、LoadUp Database 和 Runtime 组件。

`InboxReceiverService` 校验接收内容与站内路径；`InboxService` 实现列表、计数、已读和归档；`InboxGatewayImpl` 以 `(recipient_id, request_key)` 去重，并在重复键时保留原消息。单一 DataSource 下自动装配，`loadup.modules.inbox.enabled=false` 可关闭。宿主将 `classpath:db/migration/modules/inbox` 加入 Flyway 并提供 Database 序列分配表。

HTTP 接口由 web 提供。模块部署和 Gotone 同事务的条件见[Inbox 架构](../ARCHITECTURE.md)。
