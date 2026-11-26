# 业务模块架构

2026-11-15 的[阶段 0 基线](../loadup-pay/design/PHASE0_BASELINE.md)
与[完整声明依赖清单](../loadup-pay/design/PHASE0_DEPENDENCIES.md)区分当前实现和目标规范。阶段 1C 已移除 UPMS app 对
infrastructure 的生产依赖及扫描，由独立 UpmsPersistenceAutoConfiguration 注册默认 Gateway；五个小型 core 的 app 自动配置通过
PersistenceAutoConfiguration 显式约束装配顺序。后者是同 JAR 装配例外，不能写成所有 app 已完全不引用基础设施；UPMS
切片源码和回归源码已同步，构建和测试未运行。其他 core 的装配规则不随之变更。

业务能力位于 `commons → components → modules → 消费工程` 的第三层。modules 只按业务领域组织聚合目录，模块边界按真实消费方式划分，
不把代码分层直接变成 Maven 模块边界。

默认小型业务（audit、dictionary、file、gotone、transfer）：

```text
loadup-modules/
  loadup-modules-{business}/
    loadup-modules-{business}-client
    loadup-modules-{business}-core        # 同一 JAR 内保留 domain / app / infrastructure 包
    loadup-modules-{business}-web
```

Gotone 使用 client、core、web 三层，覆盖服务编码、系统账号、异步投递和站内收件箱。

UPMS 因独立 authserver 集成保留完整分层：

```text
  loadup-modules-upms/
    loadup-modules-upms-client
    loadup-modules-upms-domain
    loadup-modules-upms-infrastructure
    loadup-modules-upms-app
    loadup-modules-upms-web
    loadup-modules-upms-test
```

## 包与依赖

| 包 / 模块           | 职责                                    | 依赖边界                                    |
|---------------------|-----------------------------------------|---------------------------------------------|
| client              | Facade、Command、Query、DTO、消费方 SPI | 不依赖应用、HTTP 或持久化                   |
| core.domain         | 纯 Java 领域模型、规则、Gateway         | 不依赖 Spring、ORM、client DTO              |
| core.infrastructure | 持久化/外部系统适配、迁移               | 实现 domain Gateway                         |
| core.app            | 用例、事务、应用装配、MapStruct         | 依赖 client/domain，组合默认 infrastructure |
| web                 | Controller、可信请求上下文与授权        | 依赖 core/client，复用全局 WebMVC           |
| test（可选）        | 独立装配的单测和集成验证源码            | 仅 test scope 使用运行模块                  |

Transfer client 自有公共枚举，domain 保留领域枚举，应用层通过 MapStruct/名称映射转换；domain 不依赖 client。UPMS 保留所属
authserver 适配。

应用服务依赖 Gateway 接口，公开返回 client DTO；HTTP 层使用可见字段投影，文件 storageId 与通知接收者等内部信息不暴露。MapStruct
统一使用 runtime.mapping.LoadUpMapStructConfig 和 Spring 注入，并直接声明 loadup-components-runtime 依赖。

## 装配与配置

业务模块的默认 MyBatis-Flex / Spring JDBC Gateway 与 Flyway 脚本归 core 的 `infrastructure` 包；持久化自动配置创建端口适配。
app 在其之后按 Gateway 和所需组件条件创建用例服务。消费者可替换 Gateway Bean。

配置为 `loadup.modules.<business>.*`，Web 按 `*-web` Maven 依赖装配，无额外 enabled 开关；全局安全协议由技术组件的
`loadup.security.*` 管理。配置的 enabled 开关控制 Bean，不作为 Flyway 跳过脚本机制。用户需迁移旧前缀，无兼容别名。

## 跨业务边界

Transfer core 显式复用 File core 的权限、业务引用和 DFS 生命周期。UPMS web 通过 AuditFacade 记录敏感查询审计，但当前 Maven
可选依赖是 audit-core，且自动配置引用 AuditAutoConfiguration；不能将实际装配依赖写成只有 audit-client。
商户事实归 Pay 的 merchantcenter，产品目录与合约事实归 Pay 的 productcenter，框架不再提供 merchant/contract 业务模块。

## 本次调整范围

原顶层 audit/dictionary/file/gotone/transfer 与各自 sibling web 已归入业务聚合目录。小型业务把 domain/app/infrastructure
合并为可复用的 `core` JAR，同时保留包边界；UPMS 保留完整分层。保留路由、JSON 字段、数据库历史脚本与事务语义。各模块 README
说明其定向验证范围；未执行的回归仍须单独记录，不能视作通过。

接入见 [README.md](README.md)，各领域的业务与一致性细节见所属 ARCHITECTURE。

## 发布版本管理

`loadup-modules-bom` 管理实际发布的业务 JAR，并通过 BOM 内的 `loadup.framework.version` 导入配套 Framework BOM。
本仓库 parent 继承 `loadup-framework` 的 Maven 插件配置，导入同版本 Modules BOM，以及由 `loadup.testify.version` 指定的内部测试工具
BOM。
公开 Modules BOM 不导入 Testify。
BOM 为独立无父 POM 模型，避免“父 POM 导入 BOM、BOM 又继承父 POM”的循环。

## Database 编译期处理器

编译期配置由 database processor 内置维护，模块无需同步配置；处理器路径不单独声明版本，统一通过 Modules BOM → Framework BOM
解析。详见
[framework database processor 文档](../loadup-framework/loadup-components/data/loadup-components-database-processor/README.md)。
