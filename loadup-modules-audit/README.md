# Audit Center

为后台应用保存不可变的操作审计事件，并提供按操作者、动作、结果和时间范围查询的服务。事件只包含元数据，不保存请求体或凭证。租户身份可保留在可信执行上下文中；当前存储仅使用默认 schema，不提供租户间数据库隔离，详见[数据库 README](../../loadup-framework/loadup-components/data/loadup-components-database/README.md)。

## 引入

```xml
<dependency>
  <groupId>io.github.loadup-cloud</groupId>
  <artifactId>loadup-modules-audit-core</artifactId>
</dependency>
```

模块使用 JDBC，数据库连接与驱动由消费应用配置；模块引入 Flyway 并提供
`db/migration/framework/audit/V20261002000001__create_audit_event.sql`。Pay 消费基线为 PostgreSQL。如需自动采集 MVC
操作和查询接口，引入 `loadup-modules-audit-web`。

若将 Flyway 首次接入已有非空数据库，请按应用现有的 Flyway 配置决定是否设置 `spring.flyway.baseline-on-migrate`（使用
LoadUp Database 时为 `loadup.flyway.baseline-on-migrate`）。

## 程序化记录

注入 `AuditService`，调用 `record(new AuditRecordCommand(actorId, action, method, path, outcome, traceId))`
。仅传入不含密码、令牌、请求体或敏感参数的元数据。`loadup.modules.audit.enabled: false` 可关闭服务自动装配。

`search(AuditQuery)` 强制传入租户上下文，页码从 1 开始，每页最多 100 条。审计表为追加写入，不提供修改、删除接口；保留和归档策略由接入方规划。

设计与失败语义见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 模块选择

本目录的 Maven 坐标 `loadup-modules-audit` 为聚合 POM。业务接入选择具体 jar，版本由根 BOM 管理：

| 子模块 | 用途 |
| --- | --- |
| [`client`](loadup-modules-audit-client/README.md) | 对外 DTO、请求契约；不依赖 Spring 或持久化实现 |
| [`core`](loadup-modules-audit-core/README.md) | 领域模型、用例服务、DTO 映射、JDBC Gateway 与 Flyway 迁移；程序化接入入口 |
| [`web`](loadup-modules-audit-web/README.md) | 可选 Spring MVC 适配；自动引入 core |

`AuditService` 现在位于 `io.github.loadup.modules.audit.app.service`；公开结果位于
`io.github.loadup.modules.audit.client.dto`。领域 Gateway 不引用客户端 DTO，也不引用数据库或 Spring API。HTTP
适配使用客户端契约，接口路径及 JSON 字段保持一致。

配置统一归入 `loadup.modules.audit`：

```yaml
loadup:
  modules:
    audit:
      enabled: true
      web:
        enabled: true
```

仅引入 core 时不注册 Controller；关闭 web 开关保留程序化服务，关闭模块 enabled 开关同时停止默认应用与持久化 Bean 装配。配置开关不控制
Flyway 对已在 classpath 上的脚本执行。不要在已有数据库重复复制迁移脚本。

## 公共分页与时间约定

分页请求、DTO、HTTP 响应结构及共享时间规则见 [commons-dto 文档](../../loadup-framework/loadup-commons/loadup-commons-dto/README.md)。

## 查询契约

Controller 与 Facade 复用 `AuditPageQuery`；page、size 缺省为 1、20，size 上限 100，非法值返回
PARAM_ILLEGAL。时间范围使用应用本地时区；操作者身份由认证主体解析。事件时间和持久化 createdAt/updatedAt 使用 Spring 共享的
Clock，可由应用提供 Clock Bean 覆盖默认值。

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.audit.client.error.AuditResultCodeEnum`，错误码以 `AUDIT_` 开头。服务/领域/仓储使用
`CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回 HTTP 200 的
result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## Flyway 迁移目录

正式迁移位于 `db/migration/framework/audit/`，文件名、版本和 SQL 内容保持不变。消费应用在
`loadup.flyway.locations` 中显式加入 `classpath:db/migration/framework/audit`，与应用自己的迁移共同自动执行。
不要用共享根 `classpath:db/migration` 表达应用隔离；它也会扫描依赖 JAR。已执行版本只追加升级，不改写历史。
