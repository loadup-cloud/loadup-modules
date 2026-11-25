# Notifications

通知模块提供持久化站内收件箱和 Gotone 多渠道投递。通知发布按 `serviceCode` 交给 Gotone，站内收件箱通过 `IN_APP` 渠道接入。消息按收件人查询，支持分页、未读计数、已读和归档。租户身份仍可在可信上下文中传递，但当前数据库只使用默认 schema，不提供租户间数据库隔离，详见[数据库 README](../../loadup-framework/loadup-components/data/loadup-components-database/README.md)。

## 引入

```xml
<dependency><groupId>io.github.loadup-cloud</groupId><artifactId>loadup-modules-notification-core</artifactId></dependency>
```

应用需要 PostgreSQL `DataSource`；Flyway 执行 `V20261003000001__create_notification_inbox.sql`。设置
`loadup.modules.notification.enabled: false` 可关闭自动装配。需要 HTTP API 时另引入 `loadup-modules-notification-web`。`core` 自动装配 Gotone 引擎和 `IN_APP` 渠道。
按需引入存储和渠道适配器，见 [Gotone 接入](loadup-modules-notification-gotone/README.md)。

## 服务 API

`InboxFacade.publish(senderId, recipients, category, title, body, actionUrl, requestKey)` 每次最多投递 100
个收件人，返回新插入的消息数。`requestKey` 可选；当前 schema 内同一收件人和 key 重复投递只保留一条。`actionUrl` 仅接受应用内以 `/`
开头的路径。`list`、`unreadCount`、`markRead`、`markAllRead` 和 `archive` 均以收件人 ID 为边界。调用方负责保证收件人属于当前租户。

Gotone `IN_APP` 渠道的接收者为用户 ID，可通过 `serviceCode` 路由，也可显式指定 `channels = ["IN_APP"]`。`templateParams` 提供
`title`、`body`、`category`，可选 `senderId`、`actionUrl`、`requestKey`；已配置 Gotone 模板时渲染结果作为消息正文，否则使用
`body`。业务重试时应复用相同 `requestKey`，以避免重复入站通知。租户上下文由部署入口建立，模板参数不能选择数据库 schema。

Web 接口见 [Web 适配](loadup-modules-notification-web/README.md)，设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 模块选择

本目录的 Maven 坐标 `loadup-modules-notification` 为聚合 POM。业务接入选择具体 jar，版本由根 BOM 管理：

| 子模块                                                    | 用途                                                         |
|-----------------------------------------------------------|--------------------------------------------------------------|
| [`client`](loadup-modules-notification-client/README.md)  | 对外 DTO、请求契约；不依赖 Spring 或持久化实现               |
| [`core`](loadup-modules-notification-core/README.md)      | 领域模型、用例服务、DTO 映射、JDBC Gateway 与 Flyway 迁移；程序化接入入口 |
| [`web`](loadup-modules-notification-web/README.md)        | 可选 Spring MVC 适配，自动引入 core                          |
| [`gotone`](loadup-modules-notification-gotone/README.md)  | 统一投递 API、引擎、可选 JDBC 路由和渠道适配器              |

`InboxService` 现在位于 `io.github.loadup.modules.notification.app.service`；公开结果位于
`io.github.loadup.modules.notification.client.dto`。领域 Gateway 不引用客户端 DTO，也不引用数据库或 Spring API。HTTP
适配使用客户端契约，接口路径及 JSON 字段保持一致。

配置统一归入 `loadup.modules.notification`：

```yaml
loadup:
  modules:
    notification:
      enabled: true
      web:
        enabled: true
```

仅引入 app 时不注册 Controller；关闭 web 开关保留程序化服务，关闭模块 enabled 开关同时停止默认应用与持久化 Bean 装配。配置开关不控制
Flyway 对已在 classpath 上的脚本执行。不要在已有数据库重复复制迁移脚本。

## 公共分页与时间约定

分页请求、DTO、HTTP 响应结构及共享时间规则见 [commons-dto 文档](../../loadup-framework/loadup-commons/loadup-commons-dto/README.md)。

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.notification.client.error.NotificationResultCodeEnum`，错误码以
`NOTIFICATION_` 开头。服务/领域/仓储使用 `CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回
HTTP 200 的 result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## 按 Service Code 配置和发布

超级管理员在「通知管理 → 渠道配置」新增 `servicecodeA`，同时启用站内信、邮件、短信和推送。保存后打开「发布通知」，选择
`servicecodeA`，为所选渠道分别填写用户、邮箱、手机号和设备令牌。每个渠道和收件地址各有一条投递任务。一个渠道失败时，
其他任务继续处理。

配置和发布使用同一个 JDBC 路由库。启用邮件适配器后复用系统的 `JavaMailSender`，短信和推送使用系统注册的可用 Provider。
部署需要为 SMS 和 PUSH 提供真实发送实现。通知渠道配置保存渠道和开关，供应商由 Gotone 自动选择。

`NotificationFacade.dispatch(source, senderId, command)` 是多渠道持久化入口。它使用 serviceCode 读取已启用的路由，
校验每个启用渠道的地址，在同一事务保存请求、逐目标快照和 Task。渠道调整只影响新请求。相同 source、requestKey 和内容的
重试返回原受理结果。受理结果表示任务已保存，送达状态由后台更新。

服务间使用 `POST /api/internal/v1/notifications/dispatch`，凭证需要 `SCOPE_notification:submit`。原有站内提交
`/api/internal/v1/notifications/submit` 使用 `SCOPE_notification:submit:in-app`，经同一个 Gotone 处理器进入收件箱。

## Flyway 迁移目录

正式迁移位于 `db/migration/framework/notification/`，新增投递渠道和快照通过增量迁移写入。消费应用在
`loadup.flyway.locations` 中显式加入 `classpath:db/migration/framework/notification`，与应用自己的迁移共同自动执行。
不要用共享根 `classpath:db/migration` 表达应用隔离；它也会扫描依赖 JAR。已执行版本只追加升级，不改写历史。
