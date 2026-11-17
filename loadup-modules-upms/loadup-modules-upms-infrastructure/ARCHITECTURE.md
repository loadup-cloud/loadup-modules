# Loadup Modules UPMS Infrastructure Layer 架构

## 职责与边界

UPMS 持久化对象、Mapper 和网关实现。

## Maven 依赖边界

以下为核心依赖，完整直接声明见 [POM](pom.xml) 与[阶段 0 清单](../../../loadup-pay/design/PHASE0_DEPENDENCIES.md)。当前 infrastructure 没有反向依赖 UPMS app；UpmsPersistenceAutoConfiguration 通过自身 AutoConfiguration.imports 独立登记，以 beforeName 明确先于 app。每个默认 Gateway 逐接口 ConditionalOnMissingBean，保留原 bean 名与实现类；只扫描 converter 包，不扫描 repository，避免默认 Bean 重复注册。Mapper 继续由 database 的既有扫描提供。

核心内部依赖：

- `loadup-modules-upms-domain`
- `loadup-components-database`
- `loadup-modules-upms-client`

主要外部坐标（非完整清单，不含测试与 provided scope）：

- `org.apache.commons:commons-lang3`
- `org.springframework.boot:spring-boot-starter-aspectj`
- `tools.jackson.core:jackson-databind`
- `org.mapstruct:mapstruct`
- `com.zaxxer:HikariCP`

## 实现入口

主要源码入口：

- [`UserGatewayImpl`](src/main/java/io/github/loadup/modules/upms/infrastructure/repository/UserGatewayImpl.java)
- [`AuditMappingSupport`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/AuditMappingSupport.java)
- [`DepartmentConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/DepartmentConverter.java)
- [`LoginLogConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/LoginLogConverter.java)

## 分层与调用路径

Web → App → Domain Gateway → Infrastructure；认证适配连接 UPMS 凭证校验与通用 AuthServer。

```text
领域 Gateway → GatewayImpl → Mapper/DO → 数据库
```

## 扩展契约

- [`DepartmentConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/DepartmentConverter.java)
  ：由实现方或调用方按接口定义对接。
- [`LoginLogConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/LoginLogConverter.java)
  ：由实现方或调用方按接口定义对接。
- [`PermissionConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/PermissionConverter.java)
  ：由实现方或调用方按接口定义对接。
- [`UserConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/UserConverter.java)
  ：由实现方或调用方按接口定义对接。
- [`RoleConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/RoleConverter.java)
  ：由实现方或调用方按接口定义对接。

## 设计取舍

保持组件职责独立：业务调用依赖公开契约，自动配置处理框架装配，基础设施细节留在实现层。

集成方式与配置示例见 [README.md](README.md)。

阶段 1C：UpmsPersistenceAutoConfiguration 独立装配六类默认 Gateway，每个接口可单独替换，其余默认实现仍注册。原 @Repository 和 @Transactional 语义保留，不再通过 app 扫描仓库。Converter 由此处唯一的 converter 包扫描纳入；不自行新增 Mapper 扫描。默认适配需要既有 JdbcClient/NamedParameterJdbcTemplate、生成 Mapper、Converter 和 Clock。缺少依赖时失败，不把部分缺失误判为禁用全部服务。provided Servlet API 本轮核对无当前 Java 导入，但保留原声明；未借此次装配迁移清理非必要依赖。源码及回归源码已调整，未构建或测试。
