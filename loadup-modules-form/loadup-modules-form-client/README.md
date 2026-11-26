# Form Client

`loadup-modules-form-client` 发布动态表单的 Java 契约，供字段所有者和业务应用依赖。Maven 坐标为 `io.github.loadup-cloud:loadup-modules-form-client`。

`FormCatalog` 管理字段、字段组和模板版本，支持预览与发布；`FormRuntime` 创建草稿、读取、保存、提交并按 Key 类型化读取已提交值。业务应用可使用 `readValue(namespace, actor, instanceId, itemKey, key, type)` 获取单值，或使用 `readList` 读取多值。定义与运行时 DTO 分别在 `FormDefinitions`、`FormInstances`。

本层不提供自动装配或 HTTP 路由。嵌入式部署需加入 core；需要设计和填写 API 时再加入 web。配置入口与用例约束见[模块 README](../README.md)及[架构设计](../ARCHITECTURE.md)。
