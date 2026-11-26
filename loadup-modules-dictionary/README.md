# Data Dictionary

提供可动态维护的业务字典类型和条目。业务代码通过 `DictionaryService` 读取启用条目；数据保存在关系数据库，修改后无需重新部署。它不承担
ConfigCenter 的应用配置职责。

## 引入

```xml
<dependency>
  <groupId>io.github.loadup-cloud</groupId>
  <artifactId>loadup-modules-dictionary-core</artifactId>
</dependency>
```

模块使用 JDBC，数据库连接与驱动由消费应用配置；模块引入 Flyway，迁移脚本为
`db/migration/framework/dictionary/V20261002000002__create_data_dictionary.sql`。Pay 消费基线为 PostgreSQL。已有非空库首次启用
Flyway 时，按应用现有配置处理 baseline。需要 HTTP 接口时再引入 `loadup-modules-dictionary-web`。

## 业务 API

注入 `DictionaryService`，通过 `listEnabledItems(typeCode)`
读取有效条目。类型或条目禁用后立即不再出现在结果中；管理端列表仍能查看禁用记录。类型编码创建后不可更改，统一转为小写；条目
value 创建后不可更改。同一租户内类型编码唯一，同一类型内 value 唯一。

类型支持创建、更新、分页查询、空类型删除；条目支持创建、更新、分页查询、删除。删除类型前必须先删除其条目。页大小为
1–100。没有租户上下文时使用保留租户 `__default__`。设置 `loadup.modules.dictionary.enabled: false` 可关闭服务自动装配。

设计与持久化边界见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 模块选择

本目录的 Maven 坐标 `loadup-modules-dictionary` 为聚合 POM。业务接入选择具体 jar，版本由根 BOM 管理：

| 子模块                                                 | 用途                                                                      |
|--------------------------------------------------------|---------------------------------------------------------------------------|
| [`client`](loadup-modules-dictionary-client/README.md) | 对外 DTO、请求契约；不依赖 Spring 或持久化实现                            |
| [`core`](loadup-modules-dictionary-core/README.md)     | 领域模型、用例服务、DTO 映射、JDBC Gateway 与 Flyway 迁移；程序化接入入口 |
| [`web`](loadup-modules-dictionary-web/README.md)       | 可选 Spring MVC 适配；自动引入 core                                       |

`DictionaryService` 现在位于 `io.github.loadup.modules.dictionary.app.service`；公开结果位于
`io.github.loadup.modules.dictionary.client.dto`。领域 Gateway 不引用客户端 DTO，也不引用数据库或 Spring API。HTTP
适配使用客户端契约，接口路径及 JSON 字段保持一致。

配置统一归入 `loadup.modules.dictionary`：

```yaml
loadup:
  modules:
    dictionary:
      enabled: true
      web:
        enabled: true
```

仅引入 core 时不注册 Controller；关闭 web 开关保留程序化服务，关闭模块 enabled 开关同时停止默认应用与持久化 Bean
装配。配置开关不控制
Flyway 对已在 classpath 上的脚本执行。不要在已有数据库重复复制迁移脚本。

## 公共分页与时间约定

分页请求、DTO、HTTP
响应结构及共享时间规则见 [commons-dto 文档](../../loadup-framework/loadup-commons/loadup-commons-dto/README.md)。

字典创建项直接提交 `{typeCode, value, label, description, sortOrder, enabled}`；更新类型或条目直接提交
`{id, ...更新字段}`。

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.dictionary.client.error.DictionaryResultCodeEnum`，错误码以 `DICTIONARY_`
开头。服务/领域/仓储使用 `CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回 HTTP 200 的
result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## Flyway 迁移目录

正式迁移位于 `db/migration/framework/dictionary/`，文件名、版本和 SQL 内容保持不变。消费应用在
`loadup.flyway.locations` 中显式加入 `classpath:db/migration/framework/dictionary`，与应用自己的迁移共同自动执行。
不要用共享根 `classpath:db/migration` 表达应用隔离；它也会扫描依赖 JAR。已执行版本只追加升级，不改写历史。
