# LoadUp Gotone — Architecture

## 1. 设计定位

Gotone 是 LoadUp Modules Notification 的 **统一投递引擎**，采用 **Mode B（多后端共存模式）**：多个通知渠道
Provider 同时活跃，由运行时配置（serviceCode → channel mapping）驱动路由，存储可选。

设计目标与项目总纲一致： **底层 OSS + 薄集成**。业务代码只依赖
`loadup-modules-notification-gotone-api` 的 `NotificationService`，不感知具体渠道 SDK（Spring Mail、
厂商短信 SDK、webhook 协议）。持久化通过可选 SPI 接入，引擎本身无需数据库。

## 2. 架构图

```
业务代码（注入 NotificationService）
        │  send / sendAsync（serviceCode + 渠道收件人 + templateParams）
        ▼
loadup-modules-notification-gotone-engine（DefaultNotificationService）
        │  resolveChannels：ChannelConfigProvider（可选）或 request.channels 直连模式
        │  renderContent：TemplateRenderer（可选，默认 ${var} 渲染）
        ▼
NotificationChannelManager（可用性过滤 + provider 降级链）
        │  每个 provider 可选被 ResilientNotificationChannelProvider 包装
        ▼
NotificationChannelProvider SPI
        ├── IN_APP         → InboxFacade（Notification core）
        ├── binder-email   → 系统 JavaMailSender
        ├── SMS            → 系统注册的真实短信 Provider
        ├── binder-push    → fcm（stub）
        └── binder-webhook → dingtalk / wechat / feishu（真实 HTTP）
        ▼
RecordHandler（可选）→ store-jdbc（MyBatis-Flex，3 张表）
```

## 3. 模块结构（Mode B）

```
loadup-modules-notification-gotone/
├── pom.xml                                    # 聚合 POM
├── loadup-modules-notification-gotone-api/              # SPI + facade + 模型（零框架依赖）
├── loadup-modules-notification-gotone-engine/           # 纯发送引擎（零存储、零 DB）
├── loadup-modules-notification-gotone-store-jdbc/       # 默认存储实现（Spring JDBC，可选）
├── channels/
│   ├── loadup-modules-notification-gotone-binder-email/    # Spring Mail 真实实现
│   ├── loadup-modules-notification-gotone-binder-sms/      # stub
│   ├── loadup-modules-notification-gotone-binder-push/     # stub
│   └── loadup-modules-notification-gotone-binder-webhook/  # 真实 HTTP
```

模型、引擎、渠道和 JDBC 测试分别由 API、引擎、对应渠道 binder 和 JDBC store 模块维护。

### api（契约层，零框架依赖）

- `NotificationService`：业务门面，`send`（同步聚合结果）/ `sendAsync`（TaskExecutor 异步）。
- `NotificationChannelProvider`：渠道 SPI。`getChannelType()`（EMAIL/SMS/PUSH/WEBHOOK）+
  `getProviderName()`（smtp/aliyun/...）标识身份，`send(ChannelSendRequest)` 返回
  逐接收者成功/失败状态，`isAvailable()` 供引擎做可用性过滤。
- `config.ServiceConfigProvider`（可选）：serviceCode 的启停与元数据。
- `config.ChannelConfigProvider`（可选）：serviceCode → `List<ChannelConfig>`（channel、
  首选 provider、降级链、模板内容、渠道级配置 map）。
- `record.RecordHandler`（可选）：每次渠道发送结果的落库回调（按接收者）。
- `template.TemplateRenderer`（可选）：模板渲染 SPI。
- 模型全部为 Java `record`：`NotificationRequest/Response`、`ChannelSendRequest/Response`。

### engine（纯发送引擎）

- `DefaultNotificationService`：按 serviceCode 解析全部启用路由 → 按渠道取收件地址 → 渲染内容 →
  逐渠道投递 → 聚合响应和记录。指定 `receiversByChannel` 时按渠道覆盖共享收件人。无 `ChannelConfigProvider` 时支持直连模式（`request.channels()`
  指定渠道类型，注册的同类型 provider 全部参与降级链）。
- `NotificationChannelManager`：按可用性过滤 Provider。缺省使用 Spring 注入顺序，在渠道内依次降级。
  旧路由指定首选或备用时优先尝试指定项。
- `ResilientNotificationChannelProvider`：resilience4j 包装器。实例名
  `gotone-<channelType>-<providerName>`，熔断包裹整个降级重试循环（重试耗尽只记一次失败），
  熔断打开时该 provider 直接不可用，由降级链接管。
- `SimpleTemplateRenderer`：默认 `${var}` 占位符渲染（业务可提供自定义 `TemplateRenderer`
  覆盖）。
- 异步发送依赖应用的 `TaskExecutor`；没有时降级为同步发送并打 WARN。

### store-jdbc（默认存储，可选）

- 三张表：`gotone_notification_service`（serviceCode 配置）、`gotone_service_channel`
  （channel mapping + 模板 + 降级链 + 渠道配置 JSON）、`gotone_notification_record`
  （逐接收者发送记录）。
- 实现 `ServiceConfigProvider` / `ChannelConfigProvider` / `RecordHandler` 三个可选 SPI，
  新接入应用将 `classpath:db/migration/modules/notification-gotone` 加入 Flyway locations。
  Pay notificationcenter 使用自己的历史初始化快照。

### binders（渠道实现）

- email：`SmtpEmailProvider` 复用系统的 `JavaMailSender`，按接收者逐个发送以获得精确状态；
  `channelConfig` 支持 `subject` / `from` / `html`。
- webhook：`DingtalkWebhookProvider` / `WechatWebhookProvider` / `FeishuWebhookProvider`，
  JDK HttpClient + Jackson 真实发送 HTTP 请求。
- sms / push：随仓库提供的厂商实现是占位代码。真实短信投递由部署注册可用的 `SMS` Provider。

## 4. 关键设计决策

### 4.1 ServiceCode 路由与直连模式并存

有 `ChannelConfigProvider` 时按 serviceCode 解析渠道；`request.channels()` 可收窄为子集。
无配置 Provider 时退化为直连模式——请求显式列出渠道类型，引擎用所有已注册的同类 provider
做降级链。这样最小可用场景（本地联调）不需要数据库。

### 4.2 存储可选、引擎纯净

`ServiceConfigProvider` / `ChannelConfigProvider` / `RecordHandler` 全部 `Optional`，引擎
不依赖任何存储与 DB。集成方不引入 `-store-jdbc` 时组件就是纯内存发送器；引入后自动获得
配置路由与发送记录，业务代码不变。

### 4.3 降级链 + resilience4j

降级链是 **进程内、按 provider 顺序**的快速失败切换；resilience4j 提供 **跨调用窗口**的熔断
与重试。二者正交：熔断打开只跳过当前 provider，由降级链切到备用 provider。instance 命名
`gotone-<channel>-<provider>` 允许按 provider 用标准 `resilience4j.*` 配置。

### 4.4 与后台任务的分工

通知的进程内降级与重试由 Gotone 引擎处理；跨实例的最终一致性补偿由消费方的 PowerJob/Task 编排，业务需保证幂等。执行历史和平台告警使用
PowerJob 原生能力，Gotone 不反向依赖调度组件。API 模型继续使用不可变 record。

## 5. 扩展点

1. 新渠道：实现 `NotificationChannelProvider`，新增 `binder-{channel}` 模块，只依赖 `-api`；
   在 `META-INF/spring/...AutoConfiguration.imports` 注册自动配置。引擎自动收集。
2. 新存储：实现 `ServiceConfigProvider` / `ChannelConfigProvider` / `RecordHandler` 中所需
   SPI，注册为 bean 即可替换默认 JDBC 存储。
3. 自定义模板：提供 `TemplateRenderer` bean 覆盖默认 `SimpleTemplateRenderer`。
4. 自定义熔断/重试：按 `gotone-<channel>-<provider>` 用标准 `resilience4j.*` properties
   定制；`loadup.gotone.resilience.enabled=false` 可整体关闭包装。

## 6. 反绕过契约

业务代码禁止直接注入渠道 SDK（JavaMailSender、厂商 client）。所有发送必须经
`NotificationService`，否则丢失 serviceCode 路由、降级链、熔断、模板与记录能力。
渠道实现必须返回逐接收者的 `ChannelSendResponse` 状态，不得吞异常。
