# Gotone 业务模块重构设计

**状态**　已实施的架构与后续验收场景。

业务方以 `serviceCode` 表达“发生了什么”，例如审批待办或付款成功。管理员为该编码发布渠道组合及各渠道的内容。Gotone
接受一次请求，为每个渠道的每个目标创建一笔独立投递。`IN_APP` 是站内信投递渠道。独立的 Inbox 模块提供用户收件箱、未读数、已读和归档功能。

以 `servicecodeA` 为例，管理员勾选 SMS、Email、Push 和 Inbox。请求携带一个手机号、一个邮箱、一个设备令牌和一个用户 ID，受理后产生
**1 笔请求、4 笔投递、4 个后台任务**。邮件账号暂时不可用时，其余三笔仍可完成。业务调用和路由配置均无需填写短信或邮件供应商名称。

## 1. 模块结构

Maven 聚合坐标为 `io.github.loadup-cloud:loadup-modules-gotone`，仅聚合以下三个 JAR。依赖方向为 `web → core → client`，与
Form 模块一致。[Form 聚合 POM](../loadup-modules-form/pom.xml)

| Maven artifactId               | 职责与调用方                                                                                                                                                 |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `loadup-modules-gotone-client` | 提交、投递状态查询与配置管理 Facade，以及相应的 Command、DTO 和错误码。业务方只需依赖此契约。                                                                |
| `loadup-modules-gotone-core`   | 应用服务、版本化路由、模板校验与渲染、各渠道处理、供应商选择、持久化、Task Handler 和自动装配。渠道适配器与 Provider SPI 放在 core；邮件依赖由宿主按需引入。 |
| `loadup-modules-gotone-web`    | 管理配置与发布、内部提交与查询的 HTTP 适配。负责身份、授权及响应封装，业务规则通过 Facade 执行。                                                             |

Java 主包统一为 `io.github.loadup.modules.gotone`。旧 `gotone-api/engine/store-jdbc/binder-*` 的职责分别归入上述契约或
core 内部包。投递处理器通过 `loadup-modules-inbox-client` 的 `InboxReceiver` 写用户消息副本，Inbox 模块的 `InboxFacade`
提供查询和状态更新。业务提交统一走 Gotone，避免同步直发绕过路由、幂等或审计。

## 2. 服务编码与渠道配置

同一部署的默认 schema 内，`service_code` 唯一，沿用现有大小写语义。管理员一次发布的是**整个编码的一版配置**
，包括启用的渠道、每个渠道的模板与目标策略。这样修改邮件标题和增加 Inbox 可以同时生效。配置表按服务编码、修订和渠道关联。

| 持久化表                      | 主要字段与关系                                                                                                                                                                           |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `gotone_service`              | `service_code, name, description, enabled, active_revision, version`。编码唯一，发布指针只能指向本服务的版本。停用后停止新受理。                                                         |
| `gotone_service_draft`        | `service_code, actor, config`。每位编辑者一份草稿，完整配置以文本 JSON 保存。                                                                                                            |
| `gotone_service_revision`     | `service_code, revision, required_parameters, published_by, published_at`。唯一键为 `(service_code, revision)`；发布版本不可变。                                                         |
| `gotone_service_channel_map`  | `service_code, revision, channel, fields`。唯一键为 `(service_code, revision, channel)`；`fields` 文本 JSON 按渠道描述模板和目标策略，渠道值为 `SMS / EMAIL / PUSH / IN_APP / WEBHOOK`。 |
| `gotone_provider_account`     | 通用账号属性及渠道专属 `settings` 文本 JSON，按 `channel` 和 `adapter` 校验。                                                                                                            |
| `gotone_sms_template_binding` | `map_id, account_code, remote_template_id, remote_signature, variable_mapping, service_code, revision`。将短信配置修订与供应商备案模板绑定；唯一键为 `(map_id, account_code)`。          |
| `gotone_request`              | 请求幂等键、规范化摘要、服务修订、发送人、参数文本 JSON、受理时间、过期时间和目标数；唯一键 `(source, request_key)`。                                                                    |
| `gotone_delivery`             | 请求和渠道映射、目标摘要、投递快照文本 JSON、状态、尝试序号、发送人和到期时间。保存提交时的发送人及过期快照。                                                                            |
| `gotone_delivery_attempt`     | 每次供应商尝试、账号与配置版本、结果、回执和起止时间；唯一键 `(delivery_id, number)`。                                                                                                   |
| `gotone_delivery_resolution`  | 对未知结果的人工核对记录，包含接受结论、原因、操作者和时间；唯一键 `(delivery_id, attempt_no)`。                                                                                         |
| `gotone_delivery_retry`       | 人工重试的投递、操作者、原因和请求时间。                                                                                                                                                 |

渠道模板和账号设置以文本 JSON 存储，由应用校验；所有表名均以 `gotone_` 开头。持久化对象继承 `BaseDO`，使用字符串
`IdGenerator`，Mapper 由 Database processor 生成。表间关系由应用逻辑、唯一约束及事务维护，不建外键或使用多表 JOIN。
`(source, request_key)` 唯一键竞争失败后重新读取已提交请求：摘要相同则返回原结果，不同则报告冲突。发布时检查版本并冻结修订；在途任务继续使用原修订。

## 3. 系统级账号、端点与自动选取

系统配置按部署管理。服务编码页面只编辑“发什么、发哪些渠道”。平台管理员在独立页面维护“系统有哪些发送账号、凭证和技术能力”。同类账号可有多个，系统级停用和凭证轮换对后续尝试生效。

| 系统表                             | 主要字段与用途                                                                                                               |
|------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `gotone_provider_account.settings` | 按 `channel` 和 `adapter` 校验账号专属的 SMTP、FCM、Webhook 等设置。密码、签名凭证及服务账号文件通过通用 `secret_ref` 解析。 |

发布系统配置时按账号的 `channel` 和 `adapter` 校验字段。Inbox 使用本地数据库能力。账号设置发布后保持不变；Webhook
目的地或协议变化时，创建新账号并发布服务新修订，旧账号设置不修改。系统表保存凭证引用，运行时通过宿主的密钥服务解析。管理接口返回账号设置和凭证引用；原始秘密仅由
Worker 解析，模板快照和 Task 载荷不含账号秘密。

每笔外部投递先按渠道、模板能力、目标平台及应用过滤已启用账号，再校验适配器已安装且凭证可读。首次按 `priority`、`account_code`
升序稳定选取。明确可重试的失败后优先使用尚未尝试的账号，之后使用最久未尝试的账号。投递明细显示实际账号、版本和缺少可用账号等原因。供应商选择只在执行阶段发生，一条服务编码可复用全系统的账号池。

## 4. 受理、异步投递与失败隔离

**调用契约。** 主入口为 `GotoneFacade.submit(source, senderId, command)`，返回
`requestId、serviceCode、revision、acceptedAt、targetCount`。来源由认证入口或受信应用上下文确定。调用方传入
`requestKey、serviceCode、parameters、recipientsByChannel` 和可选 `expiresAt`。收件信息按渠道提供，Inbox 用用户 ID，SMS
用手机号，Email 用邮箱，Push 用设备令牌以及匹配账号所需的平台、应用标识。Webhook 的目标来自已发布配置，业务请求无需传入
URL。地址字段只表达目标，已发布的服务配置决定渠道集合。

```json
{
  "serviceCode": "servicecodeA",
  "requestKey": "order-paid-7391",
  "parameters": { "orderNo": "7391", "amount": "128.00" },
  "recipientsByChannel": {
    "SMS": [{ "address": "+8613800000000" }],
    "EMAIL": [{ "address": "buyer@example.org" }],
    "PUSH": [{ "address": "device-token", "platform": "ANDROID", "appId": "buyer-app" }],
    "IN_APP": [{ "address": "user-42" }]
  }
}
```

**事务边界。**

1. 主体业务有“提交成功后必须发通知”的要求时，在自己的本地事务内登记通知意图，复用现有 Task，业务提交后由生产方 Handler 调
   Gotone。跨库或 HTTP 接入同样使用生产方本地持久化意图，网络调用放在事务外。业务回滚同时撤销意图；Gotone
   离线、模板错误或发送超时由后台处理。业务库自身无法持久化意图时，强一致模式的业务事务会失败，这是“业务成功就一定有发送意图”的代价。管理端手动发布可以直接调用受理接口。
2. Gotone 先按 `(source, request_key)`
   查重，相同规范化摘要返回原受理结果，不同摘要返回冲突。首次提交读取一个已发布修订，校验参数与目标，规范化并按渠道去重。启用的个人渠道缺少目标时整笔拒绝，后台生产者可记录配置错误；临时缺少可用发送账号只影响该渠道后续投递。受理在一个本地事务中写请求、全部投递与对应
   Task，然后返回。并发相同键由数据库唯一约束裁决。受理响应丢失时原键重试即可。
3. Task 载荷仅携带 `deliveryId`。按渠道注册 `gotone.deliver.sms/email/push/inbox/webhook`
   五种任务类型。租约和重试预算按任务类型配置，请求超时按账号配置，高延迟渠道可配独立 Worker。租约覆盖调用超时与确认余量，外部
   I/O 在数据库事务外执行。实际接收方仅收到属于本渠道的内容和目标。
4. Gotone 在同一数据库事务内调用 Inbox 按投递键入箱并更新投递状态，Worker 崩溃重领可安全去重。外部 Worker
   先持久化尝试标识和账号，再发送，使用稳定的 `deliveryId` 作为下游幂等键。收到明确结果后以尝试标识作条件更新，防止旧 Worker
   覆盖新状态。主体业务只观察其自身的提交结果。

| 外部执行结果                                     | 投递记录与后续动作                                                                                                                                             |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 明确成功                                         | 记为 `SENT` 并保存供应商消息 ID。它表示供应商已接受。Inbox 成功表示已写入。若未来接入送达回执，可另外记录终端送达时间。                                        |
| 明确未受理且可恢复，或暂缺账号                   | 记为 `RETRY_WAIT`，通过 Task 做有上限的退避。仅在已确认未产生副作用时，允许下一次选择其他匹配账号。                                                            |
| 参数被明确拒绝、预算耗尽或过期                   | 分别进入 `FAILED` 或 `EXPIRED`，原因可查。到期停止创建新的发送尝试。Task 自行耗尽时，查询投递明细会对账并落实终态。                                            |
| 读取响应超时、发出后进程退出、无法判定是否被接受 | 记为 `UNKNOWN`，保留原账号、请求标识与尝试。管理员依据原供应商的回执或后台记录登记核对结果。确认未受理后可在期限内登记重试。即使超过发送期限，也保留未知结果。 |

若 Worker 在结果落库前失联，重领取时先检查未闭合尝试，按“结果未知”恢复。管理页按投递状态汇总待发送、已受理、失败及待核对的数量。按请求和投递
ID 查询，以投递记录为准。人工补发记录操作人、原因和关联的新尝试。Framework Task 提供队列待处理量、失败和最老任务等待时长。投递明细汇总各状态数量。

## 5. 数据库与初始化约定

Gotone 迁移不使用数据库专属 JSON 类型或 PostgreSQL 专属运行时 SQL；DDL 已在 MySQL 8.0 和 H2 2.4 验证可创建 11 张业务表。现有宿主使用
PostgreSQL，其他引擎的完整应用接入尚未验证。Gotone 依赖宿主提供 Framework Task；任务状态用于请求查询对账。收件人消息存于
Inbox 的 `inbox_message`，运行记录和配置按部署 schema 隔离。

Boot 与 Pay NotificationCenter 启用 `classpath:db/migration/modules/gotone` 和 `classpath:db/migration/modules/inbox`
。Flyway 只创建表和索引，不插入示例服务、系统账号或渠道种子数据；部署后由管理员配置账号并发布 Service Code。Approval 通过
`approval.reminder` 后台提交 `approval.pending`，使用提醒前，管理员须在 Gotone 创建并发布同编码的 `IN_APP` 服务配置。示例：声明参数
`actionUrl`，设置 `category=APPROVAL`、`title=待办审批`、`body=你有一项审批待处理`、`actionPath=${actionUrl}`。Admin 使用
`/api/gotone` 管理服务编码、系统账号、短信备案映射和投递，使用 `/api/inbox` 操作当前用户的收件箱。

## 6. 实施验收

| 场景                 | 可观察结果                                                                                                                         |
|----------------------|------------------------------------------------------------------------------------------------------------------------------------|
| 页面发布四渠道并提交 | 一次受理、四笔投递。Inbox 可查且可已读，其他三个适配器各得到正确目标。停用某一渠道后，新请求只生成余下渠道，原请求仍按原版本执行。 |
| 账号选择与切换       | 配置编码无需指定短信或邮件供应商。阿里云短信账号需要版本对应的备案映射，Twilio 可发送渲染后的正文。轮换凭证后新尝试使用新凭证。    |
| 外部失败与主业务隔离 | 阻塞邮件发送或关闭 Gotone，主体业务在写入本地意图后仍可提交。恢复后可按原键补受理。                                                |
| 幂等、超时与崩溃     | 同来源同键并发只有一笔请求。Inbox 重领去重，外部响应不明进入核对，已过期目标不会启动新尝试。                                       |
| Webhook 与权限       | Webhook 目标由已登记端点决定。普通用户只能读取自己的站内收件箱，内部服务只能查询自己的来源。                                       |
| 装配                 | Boot、Pay NotificationCenter 能以各自的基础表和 Gotone 业务表初始化；Admin 路由与新 HTTP 接口匹配。                                |
