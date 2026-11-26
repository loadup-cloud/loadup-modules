# Inbox Client

`loadup-modules-inbox-client` 发布入箱和收件人操作契约，Maven 坐标为 `io.github.loadup-cloud:loadup-modules-inbox-client`。Gotone 或其他同进程通知生产者可注入 `InboxReceiver`，用稳定请求键、收件人 ID 和已经渲染的标题、正文及路径调用 `receive`。同一 `(recipientId, requestKey)` 的重试保留首次内容。

用户查询和状态操作通过 `InboxFacade` 完成。HTTP 宿主使用 web，持久化实现由 core 提供。模块部署及消息投递事务说明见[Inbox 架构](../ARCHITECTURE.md)。
