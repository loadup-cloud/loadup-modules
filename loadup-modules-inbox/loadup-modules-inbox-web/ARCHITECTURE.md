# Inbox Web 架构

Web 层只负责认证主体提取、请求 DTO 转换和响应封装，不接受客户端指定收件人 ID。

`InboxWebAutoConfiguration` 在 `InboxFacade` 可用且 `loadup.modules.inbox.web.enabled` 未关闭时注册 Controller 和转换器。`InboxController` 的基路径为 `/inbox`。每个操作先从已认证的 `LoadUpUser` 读取 userId，再把该 ID 与请求内容传给 `InboxFacade`。因此列表、未读计数、读取和归档都沿同一主体边界执行。

具体路由为 `POST /list`、`/unread-count`、`/read`、`/read-all` 和 `/archive`。LoadUp WebMVC 默认加 `/api` 外部前缀。存储层再次按 recipient_id 限定操作对象，详见[收件人访问流程](../ARCHITECTURE.md#收件人读取和状态更新)。
