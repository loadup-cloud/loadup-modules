# UPMS

用户、角色、权限和部门业务模块。UPMS 校验凭证，提供角色继承的 RBAC 授权及基于资源所属人、部门的数据范围判定；令牌签发由
AuthServer 完成。

账号安全自助接口由 `loadup-modules-upms-web` 提供：`GET /api/account/security/overview` 查看本人状态，
`GET /api/account/security/logins` 查看本人登录记录，`POST /api/account/security/password` 使用旧密码修改本人密码。用户 ID
从认证主体取得，不接受请求指定其他账号。新密码至少 8 字符、最多 72 个 UTF-8 字节且必须不同于旧密码。管理员的锁定与解锁接口仍在
`/api/upms/user/**`。

登录失败达到配置阈值后临时锁定；到期后的下一次登录会自动解锁。管理员手动锁定不会自动解锁。HTTP 登录记录使用服务端观察到的
IP，不信任客户端传入的转发头。当前 JWT 为无状态令牌，修改密码或锁定账号不会立即撤销已签发令牌；已有令牌可用至其到期，后续撤销机制见
Roadmap。

## 引入

```xml
<dependency>
  <groupId>io.github.loadup-cloud</groupId>
  <artifactId>loadup-modules-upms-app</artifactId>
</dependency>
<!-- Default persistence is an explicit consumer choice. -->
<dependency>
  <groupId>io.github.loadup-cloud</groupId>
  <artifactId>loadup-modules-upms-infrastructure</artifactId>
</dependency>
```

前后端分离的用户名密码登录需引入 `loadup-modules-upms-authserver` 与 `loadup-components-oauth-server-core`，通过
`POST /api/auth/login` 获取 JWT。可设置 `loadup.security.auth-server.protocol-endpoints-enabled: false` 关闭 OAuth2 协议端点。

## 配置

```yaml
loadup:
  modules:
    upms:
      security:
        login:
          enable-failure-tracking: true
          max-fail-attempts: 5
          lock-duration: 30
```

`AuthenticationService.login` 是内部身份校验接口；HTTP 登录由 authserver 适配器完成。需要预置用户注册与 RBAC HTTP 接口时，再引入
`loadup-modules-upms-web`；也可以直接在应用 Controller 中调用 UPMS 服务。

业务代码注入 `AccessCheckService`，调用
`check(new AccessCheckCommand(userId, permissionCode, ownerUserId, departmentId))`
。结果默认拒绝；仅当有效角色授予该权限，且该角色的数据范围匹配资源属性时放行。`@PreAuthorize` 可校验静态权限，资源级 ABAC
判定须显式调用此 API。

| 能力                    | 所属模块                                        |
|-------------------------|-------------------------------------------------|
| 用户凭证校验与登录策略  | `loadup-modules-upms-app`                       |
| 角色与权限数据          | `loadup-modules-upms-domain`、`-infrastructure` |
| RBAC + 资源属性判定 API | `loadup-modules-upms-client`、`-app`            |
| 可选 HTTP Controller    | `loadup-modules-upms-web`                       |
| SAS 用户认证适配        | `loadup-modules-upms-authserver`                |
| 令牌签发与刷新          | `loadup-components-oauth-server-core`           |
| Bearer 验签             | `loadup-components-oauth-server-core`           |
| 方法级授权              | `loadup-components-authorization`               |

## 敏感数据展示与明文读取

普通 `UserDetailDTO` 的姓名、邮箱、手机号在 WebMVC JSON 输出中固定脱敏；内部对象与数据库保留原值。管理员也遵循同一展示规则。用户修改时省略敏感字段表示不变，显式空字符串表示清空；包含
`*` 的姓名、邮箱或手机号不能写入，避免把掩码保存为原数据。管理端编辑界面将这些字段留空，填写新值才更新。

`POST /api/upms/user/sensitive` 是独立明文读取入口，要求 JWT 静态 authority `upms:user:sensitive:read`
，并重新执行有效角色权限与目标用户数据范围的 RBAC/ABAC 校验。请求示例：

```json
{"id":"target-user-id","purpose":"CUSTOMER_SUPPORT"}
```

purpose 只允许 `PROFILE_CORRECTION`、`CUSTOMER_SUPPORT`、`SECURITY_REVIEW`。接口没有超级管理员绕过规则；需要显式配置权限及相应角色数据范围，不自动授予现有账号。返回
`result/data`，data 只含 id、realName、email、mobile；响应为 `Cache-Control: no-store`。

明文返回前必须有 `SensitiveReadAudit` 同步持久化访问记录。消费工程显式引入 `loadup-modules-audit` 后，Web
适配器提供默认桥接，以独立事务记录 actor、目标 ID、用途与 traceId，不记录字段原文。缺失审计组件或写入/提交失败则拒绝明文；也可提供自己的可靠
recorder。

## 业务配置命名空间

模块专属配置统一使用 `loadup.modules.upms.*`，配置中心与消费工程需同步迁移旧前缀；登录策略为
`loadup.modules.upms.security.login`，OAuth 为 `loadup.modules.upms.security.oauth`。认证协议与令牌校验继续使用全局
`loadup.security.*`。

## 输入与安全约定

新密码至少为 8 个 Unicode 字符、最多 72 个 UTF-8 字节；创建、注册、修改和重置共用 `PasswordPolicy` 与 `@ValidPassword`，避免
BCrypt 截断。验证码不写日志，邮箱和手机号显式脱敏。敏感数据展示和明文读取约束见上文。

分页请求、DTO、HTTP
响应结构及共享时间规则见 [commons-dto 文档](../../loadup-framework/loadup-commons/loadup-commons-dto/README.md)。用户排序字段限于
`createdAt`、`username`、`nickname`、`id`，角色查询只接受已声明字段白名单。业务与持久化时间使用 Spring 共享的
Clock，默认采用系统本地时区。

## 错误码契约

公开错误枚举位于 `io.github.loadup.modules.upms.client.error.UpmsResultCodeEnum`，错误码以 `UPMS_` 开头。服务/领域/仓储使用
`CommonException(enum)`；系统原因可作为 cause 保留，响应文案仅来自枚举。WebMVC 统一返回 HTTP 200 的
result/data。枚举码按稳定公开契约维护，不用字符串消息覆盖。

## Flyway 迁移目录

正式迁移位于 `db/migration/framework/upms/`，文件名、版本和 SQL 内容保持不变。消费应用在
`loadup.flyway.locations` 中显式加入 `classpath:db/migration/framework/upms`，与应用自己的迁移共同自动执行。
不要用共享根 `classpath:db/migration` 表达应用隔离；它也会扫描依赖 JAR。已执行版本只追加升级，不改写历史。

阶段 1C 已分开 app 与默认持久化装配。仅使用自定义 Gateway 时可只引入 app；Web/AuthServer 已显式包含默认
infrastructure，应用可逐接口替换 Gateway。未改登录策略、身份、权限、token 协议、SQL 或部署配置。源码及回归源码已同步，未构建或测试。
