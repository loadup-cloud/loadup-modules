# Loadup Modules UPMS App Layer 架构

## 职责与边界

UPMS 应用服务与业务编排。

当前 GitHubOAuthProvider 也在此层直接创建 RestTemplate 并解析第三方 token/userinfo 响应，是传输适配职责的现有例外（阶段 0
E15）。本轮只登记，不借装配拆分同时改写第三方登录协议。

## Maven 依赖边界

以下列核心依赖，完整直接声明见 [POM](pom.xml) 与[阶段 0 清单](../../../loadup-pay/design/PHASE0_DEPENDENCIES.md)。app
不再依赖或扫描 infrastructure。UpmsAutoConfiguration 只注册应用层及既有领域服务，Gateway 由消费者提供；spring-tx 与
spring-security-crypto 改为显式声明。

核心内部依赖：

- `loadup-modules-upms-domain`

主要外部坐标（非完整清单，不含测试与 provided scope）：

- `org.apache.commons:commons-lang3`
- `org.springframework.boot:spring-boot-starter`
- `org.springframework:spring-context`
- `org.springframework:spring-web`

## 实现入口

主要入口文件：

- [`UpmsAutoConfiguration`](src/main/java/io/github/loadup/modules/upms/app/autoconfigure/UpmsAutoConfiguration.java)
- [`DepartmentService`](src/main/java/io/github/loadup/modules/upms/app/service/DepartmentService.java)
- [`PasswordResetService`](src/main/java/io/github/loadup/modules/upms/app/service/PasswordResetService.java)
- [`PermissionService`](src/main/java/io/github/loadup/modules/upms/app/service/PermissionService.java)
- [`RoleService`](src/main/java/io/github/loadup/modules/upms/app/service/RoleService.java)
- [`UserService`](src/main/java/io/github/loadup/modules/upms/app/service/UserService.java)
- [`VerificationCodeService`](src/main/java/io/github/loadup/modules/upms/app/service/VerificationCodeService.java)
- [`GitHubOAuthProvider`](src/main/java/io/github/loadup/modules/upms/app/strategy/oauth/GitHubOAuthProvider.java)

## 分层与调用路径

Web → App → Domain Gateway → Infrastructure；认证适配连接 UPMS 凭证校验与通用 AuthServer。

```text
Client Service → 应用服务编排 → 领域服务/Gateway → 基础设施实现
```

## 装配规则

- [`UpmsAutoConfiguration`](src/main/java/io/github/loadup/modules/upms/app/autoconfigure/UpmsAutoConfiguration.java)
  是自动配置入口。

## 扩展契约

- [`LoginStrategy`](src/main/java/io/github/loadup/modules/upms/app/strategy/LoginStrategy.java)：由实现方或调用方按接口定义对接。
- [`OAuthProvider`](src/main/java/io/github/loadup/modules/upms/app/strategy/oauth/OAuthProvider.java)：由实现方或调用方按接口定义对接。

## 配置归属

- [`UpmsSecurityProperties`](src/main/java/io/github/loadup/modules/upms/app/autoconfigure/UpmsSecurityProperties.java)
  绑定 `loadup.modules.upms.security`。

## 设计取舍

保持组件职责独立：业务调用依赖公开契约，自动配置处理框架装配，基础设施细节留在实现层。

集成方式与配置示例见 [README.md](README.md)。

## 可靠敏感访问

`UserSensitiveReadService` 不依赖具体审计实现，通过 `ObjectProvider<SensitiveReadAudit>` 支持普通 UPMS 无审计部署；明文操作必须有
recorder。顺序为参数校验 → 租户范围内目标查询 → 最新角色及资源数据范围判定 → 可靠审计 → MapStruct
输出映射。任一步失败不返回原值。禁止超级管理员短路或根据请求参数关闭审计。

阶段 1C：仅引入 app 时须提供六类 Gateway（User、Role、Permission、Department、LoginLog、UserOAuthBinding）及 Clock。默认数据库实现须额外引入
loadup-modules-upms-infrastructure；app 不再传递引入 JDBC/ORM 实现。应用服务按各自构造依赖正常装配，缺少必需端口会暴露装配错误，不以粗粒度条件静默禁用整个
UPMS。原 PasswordEncoder 注册规则、Delegating 编码及无前缀 BCrypt 匹配回退、领域服务 ConditionalOnMissingBean
保留。源码及装配回归源码已调整，未构建或测试。
