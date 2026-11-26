# Loadup Modules UPMS Infrastructure Layer

UPMS 持久化对象、Mapper 和网关实现。

## 引入

```xml
<dependency>
    <groupId>io.github.loadup-cloud</groupId>
    <artifactId>loadup-modules-upms-infrastructure</artifactId>
</dependency>
```

## 设计

内部边界、依赖与源码入口见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 接入步骤

接入 Spring MVC 时引入 web 与所需 app/infra；需要登录签发时增加 upms-authserver。

## 对外契约

- [`DepartmentConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/DepartmentConverter.java)
- [`LoginLogConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/LoginLogConverter.java)
- [`PermissionConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/PermissionConverter.java)
- [`UserConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/UserConverter.java)
- [`RoleConverter`](src/main/java/io/github/loadup/modules/upms/infrastructure/converter/RoleConverter.java)

阶段 1C：UpmsPersistenceAutoConfiguration 独立装配六类默认 Gateway，每个接口可单独替换，其余默认实现仍注册。原 @Repository
和 @Transactional 语义保留，不再通过 app 扫描仓库。Converter 由此处唯一的 converter 包扫描纳入；不自行新增 Mapper
扫描。默认适配需要既有 JdbcClient/NamedParameterJdbcTemplate、生成 Mapper、Converter 和
Clock。缺少依赖时失败，不把部分缺失误判为禁用全部服务。provided Servlet API 本轮核对无当前 Java
导入，但保留原声明；未借此次装配迁移清理非必要依赖。源码及回归源码已调整，未构建或测试。
