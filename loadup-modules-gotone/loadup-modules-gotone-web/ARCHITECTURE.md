# Gotone Web 架构

Web 模块按用途注册两个 Controller，均委托给 client Facade。

## 服务间入口

`GotoneWebAutoConfiguration` 在 Web 开关启用时，仅当 `GotoneFacade` 存在才注册提交控制器。`GotoneSubmissionController` 基路径
`/internal/v1/gotone`，校验 JWT 的 `principal_type=SERVICE` 且 `service_id` 与 subject 一致。提交和状态路由分别要求
`SCOPE_gotone:submit` 与 `SCOPE_gotone:status`。服务身份被转为 `service:<id>` 作为请求来源。

## 管理入口

当 `GotoneFacade` 与 `GotoneOperations` 同时存在时，自动配置还会注册 `GotoneOperationsController`。基路径 `/gotone`，类级授权要求
`ROLE_SUPER_ADMIN`。控制器提供服务路由草稿、预览和发布，账号与短信模板绑定维护，人工提交、请求详情、确认失败后的重试及不确定投递核对。

宿主的 LoadUp WebMVC 默认外部前缀为 `/api`。Web 开关为 `loadup.modules.gotone.web.enabled`
。渠道、幂等键和投递状态设计见[父级架构](../ARCHITECTURE.md)。
