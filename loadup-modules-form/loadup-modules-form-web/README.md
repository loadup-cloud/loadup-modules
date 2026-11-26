# Form Web

`loadup-modules-form-web` 为表单目录和填写用例提供 Spring MVC API。Maven 坐标为 `io.github.loadup-cloud:loadup-modules-form-web`。宿主需安装 core、Servlet Web 和 LoadUp 授权组件；自动配置要求 `FormCatalog`、`FormRuntime` 及授权自动配置可用。

`FormController` 的类级路径为 `/forms/{namespace}`。主要入口包括 `GET /fields`、`PUT /groups`、`POST /templates/{id}/publish`、`POST /instances/start`、`PUT /instances/draft` 和 `POST /instances/submit`。在 LoadUp WebMVC 默认配置下，完整外部路径前缀为 `/api/forms/{namespace}`。具体授权码和部署条件见[模块 README](../README.md)。

共享表单领域与接入约束见[架构设计](../ARCHITECTURE.md)。
