# LoadUp UPMS Authorization Server Adapter 架构

## 职责与边界

UPMS 与授权服务器之间的可选认证适配模块。

## Maven 依赖边界

直接依赖的框架模块：

- `loadup-modules-upms-app`
- `loadup-components-oauth-server-api`
- `loadup-commons-dto`

直接依赖的外部坐标（不含测试与 provided scope）：

- `org.springframework.boot:spring-boot-starter-security`
- `org.springframework.boot:spring-boot-starter-webmvc`
- `org.springframework.security:spring-security-oauth2-jose`

## 实现入口

主要入口文件：

- [
  `UpmsAuthServerAutoConfiguration`](src/main/java/io/github/loadup/modules/upms/authserver/UpmsAuthServerAutoConfiguration.java)
- [`UpmsAuthenticationProvider`](src/main/java/io/github/loadup/modules/upms/authserver/UpmsAuthenticationProvider.java)
- [`UpmsTokenController`](src/main/java/io/github/loadup/modules/upms/authserver/UpmsTokenController.java)

## 分层与调用路径

Web → App → Domain Gateway → Infrastructure；认证适配连接 UPMS 凭证校验与通用 AuthServer。

```text
POST /api/auth/login → UPMS AuthenticationService → JwtEncoder → JSON Token 响应
```

## 装配规则

- [
  `UpmsAuthServerAutoConfiguration`](src/main/java/io/github/loadup/modules/upms/authserver/UpmsAuthServerAutoConfiguration.java)
  是自动配置入口。
    - `@ConditionalOnMissingBean(UpmsAuthenticationProvider.class)`
    -
  `@ConditionalOnProperty( prefix = "loadup.security.auth-server", name = "protocol-endpoints-enabled", havingValue = "true", matchIfMissing = true)`
    - `@ConditionalOnBean(JwtEncoder.class)`

## 设计取舍

保持组件职责独立：业务调用依赖公开契约，自动配置处理框架装配，基础设施细节留在实现层。

集成方式与配置示例见 [README.md](README.md)。

阶段 1C：本适配模块显式依赖 UPMS app 与 infrastructure，默认持久化不再依赖 app 隐式传递引入。持久化自动配置先于 app；Web
自动配置保留在 app/Audit 之后，AuthServer 自动配置显式在 app 与 SAS 之后。注册路径与安全协议分工不变，登录、JWT
身份、权限和路径实现未修改。自定义 Gateway 逐接口覆盖默认适配。构建与测试未运行。
