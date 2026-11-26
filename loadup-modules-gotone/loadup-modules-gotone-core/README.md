# Gotone Core 接入

本工件在宿主内提供服务配置、投递受理、任务 Handler、供应商适配和默认持久化。需要 Java 调用方时依赖 [Gotone Client](../loadup-modules-gotone-client/README.md) 的 `GotoneFacade`，需要 HTTP 时装配 [Gotone Web](../loadup-modules-gotone-web/README.md)。

```xml
<dependency>
    <groupId>io.github.loadup-cloud</groupId>
    <artifactId>loadup-modules-gotone-core</artifactId>
</dependency>
```

宿主提供共享数据源、JDBC 事务、Database 的序列分配表和 Framework `TaskTemplate`。将 `classpath:db/migration/modules/gotone` 纳入 Flyway，迁移完成后创建账号并发布服务编码。配置 `loadup.modules.gotone.enabled=false` 可关闭组件装配。启用 `IN_APP` 渠道时组合 Inbox Core，并让两个模块共享事务。

[渠道配置、任务类型与接入检查](../README.md) · [本层架构](ARCHITECTURE.md)
