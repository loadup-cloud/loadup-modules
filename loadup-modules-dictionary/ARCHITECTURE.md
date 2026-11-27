# Data Dictionary 架构

## 职责与边界

`loadup-modules-dictionary` 提供 `DictionaryService`、纯 Java 记录模型、`DictionaryGateway` 契约和 JDBC 实现；
`loadup-modules-dictionary-web` 是可选 MVC 适配。模块不依赖 UPMS、审计中心或 ConfigCenter。

```text
类型/条目管理 → DictionaryService → DictionaryGateway → JDBC / 关系数据库
有效条目读取 → DictionaryService → 启用类型检查 → 启用条目有序查询
```

## 数据与一致性

`dictionary_type` 按 `type_code` 唯一，`dictionary_item` 按 `(type_id, item_value)` 唯一，条目通过外键引用类型。两表保留标准
`id`、`created_at`、`updated_at` 列，不含 `tenant_id`；当前删除为物理删除，类型有条目时拒绝删除。类型编码和值不可修改，避免已被业务引用的含义发生漂移。

写操作由 `@Transactional` 包裹。管理查询返回含停用数据的分页结果；业务读取只有在类型启用时才返回启用条目，按排序值、标签、value
排序。每次读取直接访问数据库，因此修改立即可见，不引入缓存失效问题。

## 租户与扩展

客户端不能在请求体中指定隔离范围；一套部署固定使用自己的 schema，Repository 不使用 `tenant_id` 行谓词。入口只校验部署 code，
schema 路由未实现，因此该模块目前只在默认 schema
中运行，不提供租户间数据库隔离。详见[数据库 README](../../loadup-framework/loadup-components/data/loadup-components-database/README.md)
。模块提供 Flyway 迁移，不声明必需的数据库厂商；数据库连接与驱动由消费应用配置，Pay 消费基线为 PostgreSQL。

接入方式见 [README.md](README.md)。

## COLA 职责与装配

```text
web → app → domain
       ↓       ↑
     client  infrastructure → JDBC / Flyway
```

- client 定义不可变请求和 DTO，domain 保留领域模型与 `DictionaryGateway` 端口；应用边界由 `DictionaryDTOConverter` 以
  MapStruct Spring 模式映射。
- `DictionaryPersistenceAutoConfiguration` 归 infrastructure，按 DataSource 和 `loadup.modules.dictionary.enabled` 提供默认
  Gateway，消费者可覆盖接口 Bean。
- app 在持久化装配之后按 Gateway 及所需组件条件创建 `DictionaryService`。生成的 converter 通过显式 `@Import` 注册，避免
  REGISTER_BEAN 阶段条件与组件扫描冲突。
- web 只负责路由、可信身份/租户、方法授权和 HTTP 投影；请求契约归 client。全局响应、Jackson 与 `/api` 前缀由 WebMVC 组件处理。
- 仓储使用 Spring JDBC 与 Spring MapStruct；显式处理锁、幂等及状态条件。Flyway 初始脚本使用 PostgreSQL 结构。
- 所有子模块 parent 指向根 `loadup-parent`，所有内部依赖坐标由 BOM 管理。

配置契约迁移到 `loadup.modules.dictionary.*`，不保留旧前缀别名。自动装配、覆盖默认 Gateway 和关闭功能的回归源码见
`DictionaryAutoConfigurationTest`；真实数据库与 HTTP 验收由消费工程完成。

## 公共边界与映射约束

Facade 是 client 的业务契约，应用服务直接实现；Controller 和跨模块消费者依赖 Facade。domain 保留业务状态、规则与
Gateway，表示层字段转换交给 Spring 管理的 MapStruct。共享配置固定 Spring 模式、构造器注入和目标字段严格校验。

仓储显式维护 ID 和审计时间。业务表按部署 schema 隔离。需要软删除的表由业务 SQL 维护状态和读取条件。

所有数据对象的诊断文本使用 commons-json；诊断序列化与真实 API JSON 分离，避免因 HTTP 脱敏设置改变日志中的凭证披露规则。

## Client 入参与分页边界

client.command 表达业务写动作，client.query 表达只读条件；Controller 和 Facade 共用入参契约。模块专用 PageDTO 已移除，app
将领域分页对象映射为 commons-dto 的 PageDTO；Web 输出 PageResponse，避免分页结构随模块变化。领域 Gateway 的分页返回值不携带
HTTP envelope。

## 通用输入与时间约定

分页缺省 page=1、size=20，size 上限 100；非法输入使用稳定业务错误码。业务时间与持久化使用 Spring 共享的
Clock，默认采用系统本地时区；测试或消费工程可提供 Clock Bean 覆盖默认值。

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.dictionary.client.error.DictionaryResultCodeEnum`，错误码以 `DICTIONARY_`
开头。服务/领域/仓储使用 `CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回 HTTP 200 的
result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## 分页契约

纯分页输入使用 `io.github.loadup.commons.dto.PageQuery`。HTTP 字段为 `page` 与 `size`，缺失或为 null 时分别取 1 与
20。显式非法值会被拒绝，每页上限为 100。业务查询保留自身筛选字段及平面分页字段，通过 `PageQuery.normalized`
复用规则并保留所属模块错误码。可变的 UPMS 查询在服务边界通过 `pagination()` 校验。各业务维护自己的排序白名单。

领域 Gateway 返回 `io.github.loadup.commons.domain.PageResult<T>`。应用层通过 Spring 注入的 MapStruct converter 和
`PageResult.map` 转换元素，使用 `PageDTO.of(result)` 保留数量、页码和每页长度。Facade 返回 `PageDTO`，Controller 返回
`PageResponse`。

分页 JSON 形如 `{result, data: [...], pageInfo: {totalCount, pageIndex, pageSize, ...}}`，`data` 为当前页数组。
`SuccessResponse` 用于普通成功响应。空 `PageDTO` 使用空数组与 0/1/20 作为元数据默认值。原有业务分页包装、
`DictionaryTypePageQuery`、`AccountLoginPageQuery` 或 `SuccessResponse.ofPage` 的 Java 调用方需切换到共享契约。HTTP URL
和载荷字段保持原有契约。
