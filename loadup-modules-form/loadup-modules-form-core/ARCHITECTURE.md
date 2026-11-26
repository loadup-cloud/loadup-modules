# Form Core 架构

Core 实现 client 中的 Catalog 与 Runtime 两组用例，并管理发布定义和实例值。

## 实现路径

`FormAutoConfiguration` 在单一 DataSource 和启用属性条件成立时创建 `FormStore`，再注册 `FormCatalogService` 与 `FormRuntimeService`。Catalog 服务通过 `FormCompiler` 和 `FormRules` 校验字段、组及模板定义并生成发布快照；Runtime 服务读取快照，创建和更新实例草稿，校验提交并冻结最终值。`FormStore` 负责关系数据读写。

迁移脚本位于 `src/main/resources/db/migration/framework/form/V20261123000001__create_form.sql`。宿主把 `classpath:db/migration/framework/form` 加入 Flyway 路径，并选择目标 schema。Core 自动配置默认启用，可由 `loadup.modules.form.enabled=false` 关闭。

## 边界

Core 依赖 client 与数据库访问，不负责身份认证、HTTP 权限或 Pay 的产品和合同规则。字段含义由所属业务模块声明，表单保存版本化定义和输入。共享模型及约束见[动态表单架构](../ARCHITECTURE.md)。
