# Approval Web

`loadup-modules-approval-web` 将 `ApprovalFacade` 暴露为 Spring MVC API。Maven 坐标为 `io.github.loadup-cloud:loadup-modules-approval-web`；宿主同时加入 core、Servlet Web、资源服务器认证及 LoadUp WebMVC 和授权组件。

`ApprovalController` 的类级路径为 `/v1/approval/{app}`。例如 `POST /v1/approval/{app}/instances` 发起申请，`GET /v1/approval/{app}/instances/{id}` 读取详情，`GET /v1/approval/{app}/tasks/mine` 查询本人待办。LoadUp WebMVC 的外部前缀由宿主配置决定。控制器从已认证 JWT 构造 `Caller`，请求需配置对应应用权限。

详细接入权限、部署与示例见[模块 README](../README.md)，完整 API 语义见[架构设计](../ARCHITECTURE.md)。
