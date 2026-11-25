# Notification 架构

## 职责

站内收件箱保存消息及已读状态。模块内的 [Gotone](loadup-modules-notification-gotone/ARCHITECTURE.md)
按 serviceCode 解析多渠道路由，负责模板与发送编排。`InAppChannelProvider` 实现 Gotone
`NotificationChannelProvider`，并通过 `InboxFacade` 写入收件箱。模块不依赖 UPMS，收件人 ID
是业务身份标识。

```text
发布 / 服务提交 → NotificationFacade → 渠道与接收地址快照 → Task
Task → Gotone → IN_APP provider → InboxFacade → notification_inbox
              → EMAIL / SMS / PUSH provider
用户查询 / 已读 / 归档 → InboxFacade
```

## 数据与一致性

一位收件人对应一条 `notification_inbox` 记录，保存收件人、发送者、分类、标题、正文、应用内链接、已读时间和归档时间，不含 `tenant_id` 列。
`(recipient_id, request_key)` 为非空 key 提供幂等约束；不提供 key 时允许重复投递。批量投递由一个 JDBC 事务包裹，单次最多
100 人；Gotone 渠道返回逐收件人状态。

租户身份可通过可信执行上下文传播，但数据库表和查询不使用租户行列；当前仅使用默认 schema，尚未实现租户间数据库隔离。读取、已读及归档操作按收件人和软删除状态限定。归档不影响其他收件人的副本。数据库隔离现状见[数据库 README](../../loadup-framework/loadup-components/data/loadup-components-database/README.md)。首版不自动清理历史消息。

## 边界

Web 层仅允许超级管理员发布消息，普通用户只能操作自己的收件箱；业务系统内部使用服务时，负责收件人身份和租户归属校验。通知正文作为文本保存，前端应进行
HTML 转义。`actionUrl` 限制为站内路径，避免发布任意外部跳转。

接入见 [README.md](README.md)。

## COLA 职责与装配

```text
web → app → domain
       ↓       ↑
     client  infrastructure → JDBC / Flyway
```

- client 定义不可变请求和 DTO，domain 保留领域模型与 `InboxGateway` 端口；应用边界由 `NotificationDTOConverter` 以
  MapStruct Spring 模式映射。
- `NotificationPersistenceAutoConfiguration` 归 infrastructure，按 DataSource 和 `loadup.modules.notification.enabled`
  提供默认 Gateway，消费者可覆盖接口 Bean。
- app 在持久化装配之后按 Gateway 及所需组件条件创建 `InboxService`。生成的 converter 通过显式 `@Import` 注册，避免
  REGISTER_BEAN 阶段条件与组件扫描冲突。
- web 只负责路由、可信身份/租户、方法授权和 HTTP 投影；请求契约归 client。全局响应、Jackson 与 `/api` 前缀由 WebMVC 组件处理。
- 仓储使用 Spring JDBC 与 Spring MapStruct；显式处理租户、锁、幂等及状态条件。Flyway 初始脚本使用 PostgreSQL 结构。
- 所有子模块 parent 指向根 `loadup-parent`，所有内部依赖坐标由 BOM 管理。

配置契约迁移到 `loadup.modules.notification.*`，不保留旧前缀别名。自动装配、覆盖默认 Gateway 和关闭功能的回归源码见
`NotificationAutoConfigurationTest`；真实数据库与 HTTP 验收由消费工程完成。

## 公共边界与映射约束

Facade 是 client 的业务契约，应用服务直接实现；Controller 和跨模块消费者依赖 Facade。domain 保留业务状态、规则与
Gateway，表示层字段转换交给 Spring 管理的 MapStruct。共享配置固定 Spring 模式、构造器注入和目标字段严格校验。

仓储显式维护 ID、审计时间、租户和逻辑删除字段。字典删除与文件引用解绑明确使用物理删除；文件状态、通知归档和任务生命周期是业务状态，独立于
BaseDO 的 deleted。

所有数据对象的诊断文本使用 commons-json；诊断序列化与真实 API JSON 分离，避免因 HTTP 脱敏设置改变日志中的凭证披露规则。

## Client 入参与分页边界

client.command 表达业务写动作，client.query 表达只读条件；Controller 和 Facade 共用入参契约。模块专用 PageDTO 已移除，app
将领域分页对象映射为 commons-dto 的 PageDTO；Web 输出 PageResponse，避免分页结构随模块变化。领域 Gateway 的分页返回值不携带
HTTP envelope。

## 通用输入与时间约定

分页缺省 page=1、size=20，size 上限 100；非法输入使用稳定业务错误码。业务时间与持久化使用 Spring 共享的
Clock，默认采用系统本地时区；测试或消费工程可提供 Clock Bean 覆盖默认值。

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.notification.client.error.NotificationResultCodeEnum`，错误码以
`NOTIFICATION_` 开头。服务/领域/仓储使用 `CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回
HTTP 200 的 result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## Unified pagination contract

Pure pagination inputs reuse `io.github.loadup.commons.dto.PageQuery`. HTTP fields remain `page` and `size`, with
omitted/null values defaulting to 1 and 20; explicit invalid bounds are rejected and size is capped at 100. Business
queries retain their filter fields and flat pagination fields but delegate policy to `PageQuery.normalized`, preserving
their declared module error code. Mutable UPMS queries expose `pagination()` for validation at the service boundary.
Sort whitelists remain business-specific.

Domain Gateway results use `io.github.loadup.commons.domain.PageResult<T>`, shared with UPMS. Seven duplicate business
Page wrappers have been removed. Application mapping uses the injected Spring MapStruct item converter and
`PageResult.map`; `PageDTO.of(result)` adapts the unchanged total/page/size metadata. Facades return PageDTO, and
Controllers return PageResponse. No HTTP envelope enters a Gateway.

The response remains `{result, data: [...], pageInfo: {totalCount, pageIndex, pageSize, ...}}`; data is the current-page
array. SuccessResponse serves ordinary success data only; its duplicate pagination field and ofPage factories have been
removed. Empty PageDTO defaults to an empty array and metadata 0/1/20. Existing consumer code referencing removed
business Page wrappers, DictionaryTypePageQuery, AccountLoginPageQuery or SuccessResponse.ofPage must use the shared
contracts. No URL or frontend payload-field migration is required.

## 多渠道受理与投递

管理接口读写 Gotone JDBC 中的 serviceCode 渠道映射。一次保存事务序列化同一个 serviceCode 的修改，并保留各渠道的原有模板和参数。
通知受理读取已启用的映射，在同一事务中存储请求、每个渠道和收件人对应的目标及 Task 事件。每个目标保存其渠道配置快照，
同一请求重试根据来源、幂等键和规范化请求摘要去重，已受理任务按原快照执行。

`NotificationDeliveryHandler` 将每个目标交给 Gotone。`IN_APP` Provider 负责写收件箱，写入与 DELIVERED 状态在同一事务提交。
外部 Provider 在数据库事务外调用，失败由 Task 重试。外部投递是至少一次语义，系统 Provider 可将稳定的
`templateParams.requestKey` 传给下游作为幂等键。每个目标独立过期、执行和完成，状态仍为 PENDING 时可用 event_id 查询 Task。

新请求使用 `notification.delivery`，历史 `notification.in-app.delivery` 也由同一个处理器执行。
`NotificationSubmissionAutoConfiguration` 在 Notification、Task 和 Gotone 已装配时注册受理和投递。
宿主应用提供 JDBC 事务、任务队列、配置仓库和所需渠道 Provider。
