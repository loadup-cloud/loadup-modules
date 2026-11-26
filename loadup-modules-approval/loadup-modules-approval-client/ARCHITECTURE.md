# Approval Client 架构

本层只定义稳定的 Java API 和数据契约，避免业务应用依赖审批实现细节。

## 契约组成

- `ApprovalFacade` 是用例入口，方法参数显式携带 `Caller`、应用编码和命令所需版本或幂等键。
- `Approval` 定义 `Start`、`Decide`、`Reassign` 等命令，以及模板、实例、待办和详情结果。
- `ApprovalDirectory` 由部署适配器实现，用于目录查询和候选资格校验。
- `ApprovalOutcomeHandler` 由业务应用实现，消费可靠投递的终态结果。

典型调用顺序为读取 `active` 定义、查询 `candidates`、调用 `start`，再通过 `detail` 读取实例。具体版本冻结、授权与幂等语义见[共享审批架构](../ARCHITECTURE.md#2-模板实例与业务版本)。

## 依赖方向

业务应用依赖本 client；core 实现 `ApprovalFacade` 并使用目录与结果处理契约；web 将 HTTP 身份和请求映射到同一 Facade。此层不依赖 Spring，不创建数据库或 Web Bean。
