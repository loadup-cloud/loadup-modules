# LoadUp Modules BOM

可独立安装、发布和导入的纯 POM 制品，当前管理 21 个对外发布的 JAR。它与代码位于同一 Git 仓库，版本跟随本仓库发布。

```xml
<dependency>
    <groupId>io.github.loadup-cloud</groupId>
    <artifactId>loadup-modules-bom</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

将上面的 dependency 放入消费工程的 dependencyManagement，而不是普通 dependencies。
本 BOM 导入配套 Framework BOM，版本由本 BOM 内的 `loadup.framework.version` 指定；消费方无需重复导入框架 BOM。
Testify 仅供本仓库内部测试，由 parent 单独导入其 BOM，实际测试工具使用 `test` scope。

在本仓库根目录，仅安装 BOM：

```bash
mvnd -f loadup-modules-bom/pom.xml clean install
```

该命令不编译 Java。正式消费还需要安装或发布同版本实际 JAR。上传本仓库完整 reactor 时，使用已配置凭证和显式部署目标
`-DaltDeploymentRepository=github::https://maven.pkg.github.com/loadup-cloud/loadup-packages`；此处只说明配置，未执行安装、发布或构建。

新增或移除发布 JAR 时同步维护本 POM；内部测试 JAR 和聚合 POM 不进入对外版本清单。

默认 Deploy 上传 GitHub Packages。中央库发布使用 `mvnd -f loadup-modules-bom/pom.xml clean deploy -Prelease`，
独立 profile 会启用 Central Publishing 和 GPG，签名并上传 POM，不生成 sources/Javadoc JAR。
凭证和版本要求见[仓库发布说明](../README.md#发布)。
