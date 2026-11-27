# Gotone Web

`loadup-modules-gotone-web` 发布通知服务间 API 和管理 API，Maven 坐标为
`io.github.loadup-cloud:loadup-modules-gotone-web`。宿主同时装配 Gotone core、Servlet Web、资源服务器认证和 LoadUp 授权。

服务间接口类路径为 `/internal/v1/gotone`：`POST /submit` 要求 SERVICE JWT 和 `gotone:submit` scope；`GET /requests/{id}`
要求 `gotone:status`，并限定请求属于调用来源。管理控制器类路径为 `/gotone`，要求 `ROLE_SUPER_ADMIN`
，提供路由发布、模板预览、账号管理、请求查询和失败投递处理。默认外部前缀为 `/api`。

Web 自动配置可通过 `loadup.modules.gotone.web.enabled=false`
关闭。调用契约见[Client 说明](../loadup-modules-gotone-client/README.md)，部署和渠道配置见[模块 README](../README.md)。
