# Inbox Web

`loadup-modules-inbox-web` 提供认证用户的收件箱 API，Maven 坐标为 `io.github.loadup-cloud:loadup-modules-inbox-web`。宿主同时引入 core、LoadUp WebMVC、授权组件和 Servlet Web。

`InboxController` 路由基路径是 `/inbox`，提供 `POST /list`、`/unread-count`、`/read`、`/read-all` 和 `/archive`。控制器从认证的 `LoadUpUser` 获取 userId，客户端不能指定其他收件人。默认 WebMVC 外部路径为 `/api/inbox`。通过 `loadup.modules.inbox.web.enabled=false` 可关闭 Web 自动配置。

自动配置条件、消息所有权和 Gotone 投递衔接见[Inbox 架构](../ARCHITECTURE.md)。
