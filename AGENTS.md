# LoadUp Modules 协作指南

本指南补充模块仓库约定；模块自身的 `README.md`、`ARCHITECTURE.md` 定义具体契约。代码定位遵循根仓库 `AGENTS.md` 的 CodeGraph
规则：存在 `.codegraph/` 时先用 CodeGraph。

## 协作与验证

- 架构、跨模块语义、安全、生产操作、数据库写入、部署和最终验收由 lead 负责。同时最多使用两名
  Worker。仅将边界清晰的代码搜索、证据收集、局部机械修改或指定测试交给 `luna_worker`；派发前明确范围、文件、验收条件、非目标和命令/证据。Worker
  不得扩大范围，完成后由 lead 审查并验收。
- 构建和 Maven 插件统一使用 `mvnd`，命令带 `clean` 并限定受影响模块；`mvnd` 不可用时报告，不回退到 `mvn`。不主动运行全仓构建或测试。

## 文档策略

- 为仓库根目录及各 Maven 模块维护中文 `README.md` 和 `ARCHITECTURE.md`。根 README 提供快速开始，多模块仓库同时提供模块索引。子模块说明自身接入与边界，并链接上级的共享说明。
- `README.md` 是接入手册。说明用途、依赖坐标或启动方式、必要配置、调用入口和最小可用示例。
- `ARCHITECTURE.md` 是架构与设计文档。说明职责边界、依赖、设计决策和关键约束。遇到复杂分支、跨模块调用、事务边界或状态迁移，按需补充 Mermaid 流程图、时序图或状态图。
- 改动接口、配置、依赖、启动方式或关键行为时，对照源码与配置更新受影响的文档和导航。交付前检查示例、相对链接及 Mermaid 语法，按实际执行情况记录验证结果。
- 业务模块接入手册写清 `client`、`core`、`web` 的选用方式及宿主装配条件。已收录的站点页面按 [文档站维护说明](../docs/README.md) 从模块源文档同步。

## 模块边界与分层

- 默认采用 `client + core + web`：`client` 放公开 Facade、DTO、Command、Query 和消费方 SPI；`core` 放领域、应用用例与基础设施；可选
  `web` 只放 Spring MVC 入口，调用服务，不把 HTTP 映射放进业务服务。
- `core` 保留 `domain / app / infrastructure` 包边界。`domain` 不使用 Spring 或 ORM 注解；DO 放在
  `infrastructure.dataobject`，并继承 `BaseDO`。
- 仅在需要独立发布/消费各层或独立应用集成时保留完整 COLA Maven 分层。依赖方向保持单向，避免不必要的业务模块横向依赖。

## 数据库与持久化

- 每张业务表包含 `id VARCHAR(64)` 主键、`created_at TIMESTAMP(6)`、`updated_at TIMESTAMP(6)` 和
  `deleted BOOLEAN NOT NULL DEFAULT FALSE`。DO 继承 `BaseDO`，主键使用 Database 的 `IdGenerator`。Mapper 和 TableDef 使用
  Database 注解处理器生成。
- 当前部署模型为每套部署一个固定租户和 schema；业务表不加 `tenant_id`。不建外键，也不写跨表
  JOIN；通过应用层组合查询，并用事务、约束和显式校验维护关系。
- 持久化 SQL 与迁移应保持数据库可移植，使用参数化 SQL，避免依赖单一数据库方言。新增持久化实现与迁移前，核对模块架构和数据库组件规范。
- Flyway 迁移放在 `src/main/resources/db/migration/` 下，版本使用 14 位时间戳（`VyyyyMMddHHmmss__desc.sql`
  ）。生产迁移只管理结构，不插入测试或演示数据。测试夹具放在独立测试资源中。

## 可变配置与模板

- 同一业务实体的设置、配置或模板应集中存储，通过类型或渠道编码区分变体；避免为每种变体复制一套结构相同的表。只将确有不同生命周期或关系的实体拆表，并在模块架构文档说明理由。
- JSON 字段用于结构确实可变的配置/快照；稳定、需约束或查询的属性优先使用明确列。配置和模板的读取、校验与序列化保持在所属模块边界内。

## Java 与模块契约

- 公开的 DTO、Command、Query 优先使用 Java record。DO 使用显式字段和访问器。遵循 Framework 约定，不引入 Lombok。
- Web 层与 Facade 之间的结构映射沿用模块的 MapStruct 转换器。业务错误使用模块错误码，日志使用共享的 LogUtil。
- 新增或调整对外模块时同步维护聚合 POM、Modules BOM、模块 README 和架构说明。部署所用的驱动及数据库扩展由宿主选择。
