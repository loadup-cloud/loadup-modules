# Loadup Gotone Store JDBC 架构

## 职责与边界

多渠道通知的可选 JDBC 持久化模块。

## Maven 依赖边界

直接依赖的框架模块：

- `loadup-modules-notification-gotone-api`
- `loadup-components-database`

直接依赖的外部坐标（不含测试与 provided scope）：

- `org.springframework.boot:spring-boot-starter-flyway`
- `org.flywaydb:flyway-database-postgresql`
- `tools.jackson.core:jackson-databind`
- `org.springframework.boot:spring-boot-autoconfigure`
- `org.slf4j:slf4j-api`

## 实现入口

主要入口文件：

- [ `GotoneStoreJdbcAutoConfiguration`](src/main/java/io/github/loadup/components/gotone/store/config/GotoneStoreJdbcAutoConfiguration.java)
- [ `JdbcChannelConfigProvider`](src/main/java/io/github/loadup/components/gotone/store/config/JdbcChannelConfigProvider.java)
- [ `JdbcServiceConfigProvider`](src/main/java/io/github/loadup/components/gotone/store/config/JdbcServiceConfigProvider.java)

## 分层与调用路径

`NotificationService` 根据消息配置路由到多个 `NotificationChannelProvider`；JDBC store 只提供配置与记录持久化。

```text
通知引擎 → 配置/记录 SPI → MyBatis-Flex → PostgreSQL
```

## 装配规则

- [ `GotoneStoreJdbcAutoConfiguration`](src/main/java/io/github/loadup/components/gotone/store/config/GotoneStoreJdbcAutoConfiguration.java)
  是自动配置入口。
    - `@ConditionalOnClass({ChannelConfigProvider.class, NamedParameterJdbcTemplate.class})`

## 扩展契约

通过 `ServiceConfigProvider`、`ChannelConfigProvider` 和 `RecordHandler` 提供自定义实现；默认实现通过参数化查询访问部署预先选定的 schema。

## 设计取舍

保持组件职责独立：业务调用依赖公开契约，自动配置处理框架装配，基础设施细节留在实现层。

集成方式与配置示例见 [README.md](README.md)。

## 发送结果记录与诊断

`JdbcRecordHandler` 为每个请求收件人写入一条记录。邮件和短信按该收件人的明确状态判定成功；缺少状态视为失败。Webhook
是整组投递，收件人用于提及而非独立投递，因此各条记录使用 `SendAttemptResult.success`，不把缺少逐人状态误判为发送失败。这表示机器人消息投递结果，不表示用户已读。

渠道配置或回退列表 JSON 损坏时保留原有空配置/空列表回退规则；诊断只打印操作与异常类型，不打印输入、异常消息或异常链。单测验证失败路径和日志边界，真实
PostgreSQL IT 验证成功/失败状态实际落库。

## 迁移

模块发布的迁移路径为 `classpath:db/migration/modules/notification-gotone`。Pay notificationcenter 的既有安装由其本地
冻结快照管理，两种装配方式对应各自的 Flyway 历史。
