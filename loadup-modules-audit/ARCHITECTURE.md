# Audit Center 架构

## 边界

`loadup-modules-audit` 提供 `AuditRecordCommand`、`AuditService` 和 JDBC 存储，不依赖 UPMS 或 HTTP。
`loadup-modules-audit-web` 可选地提供 MVC 自动采集和管理员查询。业务服务可以直接注入 `AuditService` 记录非 HTTP 操作。

模块通过 JDBC 持久化并提供 Flyway 迁移，不声明必需的数据库厂商；消费应用配置数据库连接与驱动。Pay 消费基线为 PostgreSQL。

```text
AuditRecordCommand → AuditService → AuditGatewayImpl → audit_event
AuditQuery → AuditService → AuditGatewayImpl → PageResult<AuditEvent>
```

## 数据契约

每个事件有唯一 ID、操作者 ID、动作、HTTP 方法、路由模板、结果、traceId 和发生时间。数据库表保留 `id`、`created_at`、
`updated_at`、`deleted` 列，不含 `tenant_id`。一套部署只服务一个租户并使用固定 `loadup.database.schema`，Audit
存储因此天然只属于本部署；详见[数据库 README](../../loadup-framework/loadup-components/data/loadup-components-database/README.md)
。查询按发生时间与 ID 倒序分页，页大小上限 100。

## 写入与失败

审计表只追加事件。自动采集在 MVC 请求结束后写入；写入失败会记录服务器日志，不改变已经完成的业务响应。因此首版适合后台操作追踪，尚不提供与业务事务原子提交的合规级审计保证。认证过滤器在进入
MVC 前拒绝的请求不会被该 MVC 适配器采集。可靠写入、保留/归档与该类拒绝事件不属于当前适配器能力。

接入方式见 [README.md](README.md)。

## COLA 职责与装配

```text
web → app → domain
       ↓       ↑
     client  infrastructure → JDBC / Flyway
```

- client 定义不可变请求和 DTO，domain 保留领域模型与 `AuditGateway` 端口；应用边界由 `AuditDTOConverter` 以 MapStruct
  Spring 模式映射。
- `AuditPersistenceAutoConfiguration` 归 infrastructure，按 DataSource 和 `loadup.modules.audit.enabled` 提供默认
  Gateway，消费者可覆盖接口 Bean。
- app 在持久化装配之后按 Gateway 及所需组件条件创建 `AuditService`。生成的 converter 通过显式 `@Import` 注册，避免
  REGISTER_BEAN 阶段条件与组件扫描冲突。
- web 只负责路由、可信身份/租户、方法授权和 HTTP 投影；请求契约归 client。全局响应、Jackson 与 `/api` 前缀由 WebMVC 组件处理。
- 仓储使用 Spring JDBC 与 Spring MapStruct；显式处理租户、锁、幂等及状态条件。Flyway 初始脚本使用 PostgreSQL 结构。
- 所有子模块 parent 指向根 `loadup-parent`，所有内部依赖坐标由 BOM 管理。

配置契约迁移到 `loadup.modules.audit.*`，不保留旧前缀别名。自动装配、覆盖默认 Gateway 和关闭功能的回归源码见
`AuditAutoConfigurationTest`；真实数据库与 HTTP 验收由消费工程完成。

## 公共边界与映射约束

Facade 是 client 的业务契约，应用服务直接实现；Controller 和跨模块消费者依赖 Facade。domain 保留业务状态、规则与
Gateway，表示层字段转换交给 Spring 管理的 MapStruct。共享配置固定 Spring 模式、构造器注入和目标字段严格校验。

仓储显式维护 ID、审计时间、租户和逻辑删除字段。字典删除与文件引用解绑明确使用物理删除；文件状态、通知归档和任务生命周期是业务状态，独立于
BaseDO 的 deleted。

所有数据对象的诊断文本使用 commons-json；诊断序列化与真实 API JSON 分离，避免因 HTTP 脱敏设置改变日志中的凭证披露规则。

## Client 入参与分页边界

client.command 表达业务写动作，client.query 表达只读条件；Controller 和 Facade 共用入参契约。模块专用 PageDTO 已移除，app
将领域分页对象映射为 commons-dto 的 PageDTO；Web 输出 PageResponse，避免分页结构随模块变化。领域 Gateway 的分页返回值不携带
HTTP envelope。

## 查询契约

Controller 与 Facade 复用 `AuditPageQuery`；page、size 缺省为 1、20，size 上限 100，非法值返回
PARAM_ILLEGAL。时间范围使用应用本地时区；操作者身份由认证主体解析。事件时间和持久化 createdAt/updatedAt 使用 Spring 共享的
Clock，可由应用提供 Clock Bean 覆盖默认值。

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.audit.client.error.AuditResultCodeEnum`，错误码以 `AUDIT_` 开头。服务/领域/仓储使用
`CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回 HTTP 200 的
result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## 分页契约

纯分页输入使用 `io.github.loadup.commons.dto.PageQuery`。HTTP 字段为 `page` 与 `size`，缺失或为 null 时分别取 1 与 20。显式非法值会被拒绝，每页上限为 100。业务查询保留自身筛选字段及平面分页字段，通过 `PageQuery.normalized` 复用规则并保留所属模块错误码。可变的 UPMS 查询在服务边界通过 `pagination()` 校验。各业务维护自己的排序白名单。

领域 Gateway 返回 `io.github.loadup.commons.domain.PageResult<T>`。应用层通过 Spring 注入的 MapStruct converter 和 `PageResult.map` 转换元素，使用 `PageDTO.of(result)` 保留数量、页码和每页长度。Facade 返回 `PageDTO`，Controller 返回 `PageResponse`。

分页 JSON 形如 `{result, data: [...], pageInfo: {totalCount, pageIndex, pageSize, ...}}`，`data` 为当前页数组。`SuccessResponse` 用于普通成功响应。空 `PageDTO` 使用空数组与 0/1/20 作为元数据默认值。原有业务分页包装、`DictionaryTypePageQuery`、`AccountLoginPageQuery` 或 `SuccessResponse.ofPage` 的 Java 调用方需切换到共享契约。HTTP URL 和载荷字段保持原有契约。
