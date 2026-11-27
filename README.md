# LoadUp Modules

可按需消费的业务能力，每个业务目录是聚合 POM。通过 `loadup-modules-bom` 管理业务 JAR 版本，消费工程引入具体 `*-core`、
`*-app`
或 `*-web` jar。

## BOM 接入

消费方在 `dependencyManagement` 中导入以下独立制品：

```xml
<dependency>
    <groupId>io.github.loadup-cloud</groupId>
    <artifactId>loadup-modules-bom</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

随后引入 `loadup-modules-upms-web`、`loadup-modules-file-client` 等具体 JAR 时省略 version。
Modules BOM 已导入配套的 `loadup-framework-bom`，可同时管理直接使用的框架依赖版本；配套 Framework 版本在 Modules BOM 内维护。
[Modules BOM](loadup-modules-bom/README.md) 覆盖当前发布的 JAR，不管理聚合目录或内部测试模块。

## 查看 OpenAPI

宿主引入 `io.github.loadup-cloud:loadup-components-springdoc` 生成已装配 Controller 的文档。交互页面为 `/scalar`，OpenAPI
JSON 位于 `/v3/api-docs`。如果自定义 `springdoc.packages-to-scan`，纳入 `io.github.loadup.modules` 或所需 Controller
的包。详见 [SpringDoc 接入](../loadup-framework/loadup-components/web/loadup-components-springdoc/README.md)。

## 构建与格式检查

Modules 继承 Framework parent 的 Maven 插件配置。并列检出时使用本地 Framework POM，独立检出时从 Maven 本地仓库或 GitHub
Packages 解析。
升级 Framework 时同步更新 Modules POM 中的 parent 版本与 `loadup.framework.version`，以及 Modules BOM 中的
`loadup.framework.version`。

Spotless 格式检查和修复独立于 Maven 构建生命周期，可在脚本后传入 `-pl` 等 Maven 参数。

```bash
./spotless.sh check
./spotless.sh apply -pl :loadup-modules-file-core
```

运行 `./spotless.sh install` 安装 pre-commit hook。提交时，hook 会格式化已暂存的 Java 文件和 POM，并重新暂存格式化结果。
同一文件有未暂存改动时，hook 会中止提交，请先处理这些改动再重试。
使用 `git commit -- <文件>` 时，若 hook 修改了格式，按提示重试提交。

## GitHub Actions

[Modules CI](.github/workflows/ci.yml) 在 PR、`main`、`develop`、`dev` 的推送和手动运行时触发。工作流使用 JDK 25、
Maven Daemon 和 Docker，执行单元测试与 Testcontainers 集成测试并打包。JaCoCo 在 CI 中关闭。
运行详情可下载 `loadup-modules-jars` 和测试报告。

`main` 推送通过测试后，将同一轮构建的 Maven 制品发布到 `loadup-cloud/loadup-packages`。发布步骤使用
`OSSRH_USERNAME` 和 `OSSRH_TOKEN`。普通验证使用 `LOADUP_PACKAGES_USERNAME` 和 `LOADUP_PACKAGES_TOKEN` 读取依赖，
缺少这些凭证时使用工作流的只读令牌。

## 发布

本仓库的 JAR、parent 与独立 BOM 默认激活 `github`，日常发布到 GitHub Packages；准备好正式版后再手动发布 Central：

```bash
mvnd clean deploy -DdeployAtEnd=true
mvnd clean deploy -Prelease
```

`github` 使用 Maven settings 的 `github` server 凭证；`release` 使用 `central` server 的 Portal token 和本机 GPG 私钥。
中央库 profile 为 JAR 附加 sources/Javadoc 并签名，独立 BOM 只签名 POM；普通构建和 GitHub 发布不启用 Central/GPG 插件。
同一版本双仓发布时分别执行两条命令，每次选择一个 profile。正式发布需先准备配套的正式版 Framework、Testify 与 Modules 版本；
当前 SNAPSHOT 日常发布使用 GitHub。Central 不由 CI 触发，`autoPublish=false`，上传校验后由人在 Portal 完成最终发布。
若本地启用其他 profile，需显式组合 `github`；`maven.deploy.skip=true` 的内部测试模块在两个目标均排除。
参见 [Central 官方说明](https://central.sonatype.org/publish/publish-portal-maven/)。

## 业务目录

| 模块                                              | 能力                                       |
|---------------------------------------------------|--------------------------------------------|
| [UPMS](loadup-modules-upms/README.md)             | 用户、RBAC/ABAC、账号安全及可选认证适配    |
| [Audit](loadup-modules-audit/README.md)           | 审计记录、查询与可选 MVC 采集              |
| [Dictionary](loadup-modules-dictionary/README.md) | 字典类型、条目及启用选项                   |
| [File](loadup-modules-file/README.md)             | 文件元数据、访问控制、引用与清理           |
| [Form](loadup-modules-form/README.md)             | 字段目录、表单发布、填写与按 Key 取值      |
| [Approval](loadup-modules-approval/README.md)     | 顺序审批模板、待办、换人与结果投递         |
| [Gotone](loadup-modules-gotone/README.md)         | 按服务编码配置多渠道异步投递               |
| [Inbox](loadup-modules-inbox/README.md)           | 站内消息的幂等接收、收件箱查询、已读及归档 |
| [Transfer](loadup-modules-transfer/README.md)     | 导入导出任务、进度、结果与重试             |

## 分层选择

小型业务目录默认包含 `client / core / web` 子模块：`core` 内部保留 `domain / app / infrastructure` 包边界。UPMS 因独立
authserver 集成保留完整 `client / domain / infrastructure / app / web / test` 分层。web 是可选接口适配层，不在 modules
下单列为业务模块。

- 仅共享协议：引入 `*-client`。
- 程序化使用用例：引入 `*-core`（UPMS 为 `*-app`），同一 JAR 带入默认持久化适配（完整分层模块按宿主装配）。
- 提供 HTTP 接口：引入同一业务目录下的 `*-web`，自动带入 core。
- 扩展持久化：依赖 domain Gateway，实现并注册接口 Bean。
- 独立测试模块只用于验证，不引入生产工程；默认测试跟随 core。

## 配置命名空间

业务配置统一使用 `loadup.modules.<module>.*`，开关为 `enabled`：

```yaml
loadup:
  modules:
    file:
      enabled: true
      web:
        enabled: true
    audit:
      enabled: true
      web:
        include-paths:
          - /api/files/*
    transfer:
      max-input-bytes: 20971520
      max-output-bytes: 104857600
    upms:
      security:
        login:
          max-fail-attempts: 5
```

模块专有业务策略使用 modules 前缀。全局认证协议配置仍由 `loadup.security.*` 管理，观测使用标准 `management.*` 与
`loadup.<domain>.*` 指标命名。旧业务配置前缀已移除，集成方需更新配置；具体属性以各模块文档为准。

设计边界见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## Database 编译期处理器

编译期配置由 database processor 内置维护，模块无需同步配置；处理器版本由 framework BOM 管理。详见
[framework database processor 文档](../loadup-framework/loadup-components/data/loadup-components-database-processor/README.md)。
