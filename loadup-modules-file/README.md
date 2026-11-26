# File Resources

文件资源模块在 DFS 存储之上管理文件的业务元数据、归属、引用和删除生命周期。DFS 决定文件字节存在哪里；本模块提供可被业务引用的稳定文件
ID。适用于单体应用中的附件、头像和导出文件。

## 引入

引入 `loadup-modules-file-core` 和一个 DFS binder，例如 `loadup-components-dfs-binder-local`。应用需要 PostgreSQL
`DataSource`；Flyway 会执行 `V20261002000003__create_file_resource.sql`。可设置 `loadup.modules.file.enabled: false`
停用自动装配。HTTP API 另需 `loadup-modules-file-web`。

```xml
<dependency><groupId>io.github.loadup-cloud</groupId><artifactId>loadup-modules-file-core</artifactId></dependency>
<dependency><groupId>io.github.loadup-cloud</groupId><artifactId>loadup-components-dfs-binder-local</artifactId></dependency>
```

本地 binder 配置 `loadup.dfs.binder.local.base-path`；生产多节点环境请选择共享存储 binder，并配置其凭据和容量策略。本地启动器示例路径为
`./data/files`。

## 业务 API

注入 `FileResourceService`。`upload(ownerId, filename, contentType, size, InputStream)` 上传并记录元数据，调用者关闭输入流。返回的
`FileResource.id` 是业务文件 ID，`storageId` 仅供内部定位 DFS 对象。`get`、`list` 和 `download` 均校验租户及所有者，管理员权限由调用方传入；下载返回的
`FileDownloadResponse` 必须关闭。

业务记录落库后可调用 `attach(id, actorId, admin, referenceType, referenceId)` 建立引用。业务记录删除时调用
`detach`。有引用的文件不能删除。引用类型和引用 ID 只用于记录关联；业务系统负责确认引用对象的真实性及访问授权，不应把任意用户输入直接传给这两个
API。

`requestDeletion` 将无引用文件置为 `PENDING_DELETE`，事务提交后调用 `cleanup` 删除 DFS 对象并标记 `DELETED`。DFS 清理失败时可调用
`cleanupPending(limit)` 重试。需要自动清理的应用可用已有调度组件定期调用它。没有租户上下文时使用 `__default__`。

Web API 与配置见 [Web 适配说明](loadup-modules-file-web/README.md)，设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 模块选择

本目录的 Maven 坐标 `loadup-modules-file` 为聚合 POM。业务接入选择具体 jar，版本由根 BOM 管理：

| 子模块                                           | 用途                                                                                  |
|--------------------------------------------------|---------------------------------------------------------------------------------------|
| [`client`](loadup-modules-file-client/README.md) | 对外 DTO、请求契约；不依赖 Spring 或持久化实现                                        |
| [`core`](loadup-modules-file-core/README.md)     | 领域模型、用例服务、DTO 映射、默认 Spring JDBC Gateway 与 Flyway 迁移；程序化接入入口 |
| [`web`](loadup-modules-file-web/README.md)       | 可选 Spring MVC 适配；自动引入 core                                                   |

`FileResourceService` 现在位于 `io.github.loadup.modules.file.app.service`；公开结果位于
`io.github.loadup.modules.file.client.dto`。领域 Gateway 不引用客户端 DTO，也不引用数据库或 Spring API。HTTP
适配使用客户端契约，接口路径及 JSON 字段保持一致。

配置统一归入 `loadup.modules.file`：

```yaml
loadup:
  modules:
    file:
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

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.file.client.error.FileResultCodeEnum`，错误码以 `FILE_` 开头。服务/领域/仓储使用
`CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回 HTTP 200 的
result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## Flyway 迁移目录

正式迁移位于 `db/migration/framework/file/`，文件名、版本和 SQL 内容保持不变。消费应用在
`loadup.flyway.locations` 中显式加入 `classpath:db/migration/framework/file`，与应用自己的迁移共同自动执行。
不要用共享根 `classpath:db/migration` 表达应用隔离；它也会扫描依赖 JAR。已执行版本只追加升级，不改写历史。
