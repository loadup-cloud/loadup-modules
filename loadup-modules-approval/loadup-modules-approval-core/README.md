# Approval Core

`loadup-modules-approval-core` 提供审批用例、持久化和 Spring Boot 自动配置。Maven 坐标为 `io.github.loadup-cloud:loadup-modules-approval-core`，依赖 client、宿主 DataSource、事务管理器及可靠任务组件。

`ApprovalAutoConfiguration` 在存在单一 DataSource 和 `TaskTemplate` 时装配存储、服务和结果投递；没有自定义 `ApprovalDirectory` 时，在 UPMS Facade 齐备后注册 UPMS 适配器。独立部署也可提供自己的 `ApprovalDirectory` Bean。

宿主 Flyway 需包含 `classpath:db/migration/framework/approval`，脚本位于 `src/main/resources/db/migration/framework/approval/`。HTTP 宿主另装 web。部署与权限配置见[模块 README](../README.md)，流程与事务边界见[架构设计](../ARCHITECTURE.md)。
