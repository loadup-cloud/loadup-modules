# Approval Web 架构

Web 层负责 HTTP 映射、身份提取和协议响应，不承载审批状态规则。

## 请求路径

`ApprovalWebAutoConfiguration` 仅在 Servlet Web 环境且 `ApprovalFacade` 可用时注册 `ApprovalController`。控制器基路径为 `/v1/approval/{app}`，通过 JWT 校验主体及 `principal_type`，再把 subject、操作者类型和 authorities 转成 `Approval.Caller`。控制器将定义管理、候选查询、实例与待办操作委托给 `ApprovalFacade`。

代表性路由包括：`GET /definitions/{id}`、`POST /definitions/{id}/publish`、`POST /instances`、`PATCH /instances/{id}/assignments`、`POST /tasks/{stepId}/decisions` 和 `POST /instances/{id}/withdraw`。版本写操作读取 `If-Match`，命令重试使用 `Idempotency-Key`。

## 边界

Web 依赖 core 和 client，并要求宿主提供资源服务器安全配置。授权范围与业务校验由 Facade 实现；外部路由前缀和认证规则由宿主决定。共享流程、权限与数据模型见[审批架构设计](../ARCHITECTURE.md)。
