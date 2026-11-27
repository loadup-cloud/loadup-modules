# Loadup Modules UPMS App Layer

UPMS 应用服务与业务编排。

`AccountSecurityService` 提供本人安全概览、按用户 ID 查询登录历史以及使用旧密码修改密码的应用入口。HTTP 适配见
`loadup-modules-upms-web`。登录失败跟踪和临时锁定由 `loadup.modules.upms.security.login` 控制。管理员手动锁定不会因超时自动解除。新密码强度使用 `loadup.modules.upms.security.password-strength`，默认 `normal`。宿主配置 ConfigCenter binder 后，该键可动态更新。

## 引入

```xml
<dependency>
    <groupId>io.github.loadup-cloud</groupId>
    <artifactId>loadup-modules-upms-app</artifactId>
</dependency>
```

## 装配与配置

模块提供以下自动配置入口；实际启用条件和配置项以对应类及父模块 README 为准：

- `UpmsAutoConfiguration`

## 设计

内部边界、依赖与源码入口见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 接入步骤

接入 Spring MVC 时引入 web 与所需 app/infra；需要登录签发时增加 upms-authserver。

## 配置入口

| 前缀                           | 配置类                                                                                                                |
|--------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `loadup.modules.upms.security` | [`UpmsSecurityProperties`](src/main/java/io/github/loadup/modules/upms/app/autoconfigure/UpmsSecurityProperties.java) |

## 对外契约

- [`LoginStrategy`](src/main/java/io/github/loadup/modules/upms/app/strategy/LoginStrategy.java)
- [`OAuthProvider`](src/main/java/io/github/loadup/modules/upms/app/strategy/oauth/OAuthProvider.java)

## 自动装配

- [`UpmsAutoConfiguration`](src/main/java/io/github/loadup/modules/upms/app/autoconfigure/UpmsAutoConfiguration.java)

## 受控明文读取

`UserSensitiveReadService.read(actorId, query)` 要求可信 actor、有效 RBAC/ABAC 和可用的可靠 `SensitiveReadAudit`。recorder
必须同步持久化且传播失败；缺失 recorder 时拒绝明文。输出 DTO 在审计成功后由 Spring MapStruct converter 构造。HTTP 入口由
web 适配提供；配置与授权示例见 [UPMS README](../README.md)。

阶段 1C：仅引入 app 时须提供六类 Gateway（User、Role、Permission、Department、LoginLog、UserOAuthBinding）及 Clock。默认数据库实现须额外引入
loadup-modules-upms-infrastructure；app 不再传递引入 JDBC/ORM 实现。应用服务按各自构造依赖正常装配，缺少必需端口会暴露装配错误，不以粗粒度条件静默禁用整个
UPMS。原 PasswordEncoder 注册规则、Delegating 编码及无前缀 BCrypt 匹配回退、领域服务 ConditionalOnMissingBean
保留。源码及装配回归源码已调整，未构建或测试。
