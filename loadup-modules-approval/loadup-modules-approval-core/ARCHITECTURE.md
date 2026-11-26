# Approval Core 架构

Core 实现 client 契约并拥有审批状态变更、事务和数据访问。

## 实现路径

`ApprovalAutoConfiguration` 创建 `ApprovalStore`，并在目录 Bean 可用时创建 `ApprovalService` 作为 `ApprovalFacade`。默认目录适配器 `UpmsApprovalDirectory` 将 UPMS 用户、角色、部门和账户安全 Facade 映射为审批候选查询。`ApprovalService` 执行模板校验、实例创建、办理、撤回及换人；`ApprovalStore` 负责 SQL 持久化。`ApprovalReminder` 和 `ApprovalDelivery` 接入可靠任务，后者调用可选 `ApprovalOutcomeHandler` 或 webhook 配置完成结果投递。

自动配置要求单一 DataSource 和 `TaskTemplate`。身份目录可由 UPMS 自动提供，也可由宿主实现 `ApprovalDirectory`。迁移脚本在 `src/main/resources/db/migration/framework/approval/V20261125000001__create_approval.sql`，宿主需把该路径加入 Flyway 扫描。

## 边界

Core 依赖 client 契约、数据库组件及可靠任务组件；它不声明 HTTP 路由。业务数据和正文由调用方保存，审批仅保留业务引用、摘要、模板快照和处理轨迹。整体表结构、状态机与投递约束见[共享审批架构](../ARCHITECTURE.md)。
