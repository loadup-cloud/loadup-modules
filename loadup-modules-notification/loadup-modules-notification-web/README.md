# Notifications Web

引入 `loadup-modules-notification-web` 后，在站内通知服务可用时注册以下 `/api/notifications` 接口。渠道管理和发布需装配 Gotone JDBC 路由与 Task。

| 方法 | 路径                              | 作用                                     | 权限               |
|------|-----------------------------------|------------------------------------------|--------------------|
| GET | `/api/notifications/routes` | 查询 Service Code 与已启用的渠道 | `ROLE_SUPER_ADMIN` |
| PUT | `/api/notifications/routes` | 保存 `{serviceCode, channels}` | `ROLE_SUPER_ADMIN` |
| POST | `/api/notifications/publish` | 按 Service Code 受理多渠道投递 | `ROLE_SUPER_ADMIN` |
| POST | `/api/notifications/list`         | JSON 分页查询本人消息，可传 `unreadOnly` | 登录用户           |
| POST | `/api/notifications/unread-count` | JSON `{}` 查询本人未读数                 | 登录用户           |
| POST | `/api/notifications/read`         | JSON `{ "id": "..." }` 标记本人消息已读  | 登录用户           |
| POST | `/api/notifications/read-all`     | JSON `{}` 全部标记已读                   | 登录用户           |
| POST | `/api/notifications/archive`      | JSON `{ "id": "..." }` 归档本人消息      | 登录用户           |

JSON 响应复用 WebMVC 的 `{result, data}` 契约。收件人从认证主体获取，不接受客户端指定读取用户 ID。发布请求包含
`requestKey`、`serviceCode`、`recipientsByChannel`、`title`、`body`，并支持可选的 `actionUrl` 和 `expiresAt`。
响应返回通知 ID 与受理时间。

设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。
