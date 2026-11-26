# Form Core

`loadup-modules-form-core` 实现字段目录、发布编译和实例运行时，并通过 Spring Boot 自动配置暴露 `FormCatalog` 与 `FormRuntime`。Maven 坐标为 `io.github.loadup-cloud:loadup-modules-form-core`。

`FormAutoConfiguration` 在存在单一 DataSource 且 `loadup.modules.form.enabled` 未关闭时注册 `FormStore`、`FormCatalogService` 和 `FormRuntimeService`。宿主提供数据库与事务环境。Flyway 扫描路径需包含 `classpath:db/migration/framework/form`，脚本随 core 包提供。设置 `loadup.modules.form.enabled=false` 可关闭自动配置。

HTTP 接口由 web 提供。宿主如何选择 schema 及业务配置边界见[模块 README](../README.md)，领域和发布模型见[架构设计](../ARCHITECTURE.md)。
