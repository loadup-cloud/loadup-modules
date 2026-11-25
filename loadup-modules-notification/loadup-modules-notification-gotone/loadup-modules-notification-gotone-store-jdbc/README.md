# Loadup Gotone Store JDBC

多渠道通知的可选 JDBC 持久化模块。

## 引入

```xml
<dependency>
    <groupId>io.github.loadup-cloud</groupId>
    <artifactId>loadup-modules-notification-gotone-store-jdbc</artifactId>
</dependency>
```

## 装配与配置

模块提供以下自动配置入口；实际启用条件和配置项以对应类及父模块 README 为准：

- `GotoneStoreJdbcAutoConfiguration`

## 设计

内部边界、依赖与源码入口见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 接入步骤

引入 engine 和需要的渠道 binder；渠道可同时存在，存储模块按需引入。

## 对外契约

使用 Gotone API 的 `NotificationService`、`ServiceConfigProvider`、`ChannelConfigProvider` 和 `RecordHandler`；持久化
Mapper 由 APT 生成在 target 中，仅供存储实现使用。

## 自动装配

- [ `GotoneStoreJdbcAutoConfiguration`](src/main/java/io/github/loadup/components/gotone/store/config/GotoneStoreJdbcAutoConfiguration.java)

## 数据库迁移与记录语义

将 `classpath:db/migration/modules/notification-gotone` 加入 `loadup.flyway.locations`。Pay notificationcenter
继续使用已登记的冻结快照。路由读取 `gotone_service_channel`，发送记录使用各渠道实际接收地址。
