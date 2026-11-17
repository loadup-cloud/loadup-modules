# Modules BOM Architecture

BOM 自带 groupId、artifactId、version 和发布元数据，不继承本仓库根父 POM。根父 POM 导入它，为实际子模块管理版本。
因此模型依赖单向为 parent → BOM，不产生 parent ↔ BOM 循环。

本 BOM 默认激活 `github` profile，只显式声明 Maven Deploy 插件；手动启用 `release` 后额外加载 Central Publishing 与 GPG，签名并上传 BOM 的 POM。
POM 制品不需要 sources/Javadoc JAR；原生属性 `spotless.skip=true`、`enforcer.skip=true` 跳过针对 BOM 的质量检查。
这些属性不通过 BOM import 传递，业务模块继续执行父 POM 配置的检查。

dependencyManagement 中的 ${project.version} 在 BOM 自身模型中解析，表示本仓库发布版本，不跟随消费工程版本。
BOM 不提供 Java 类，也不会自动引入 JAR。自身业务制品跟随 `${project.version}`，配套 Framework BOM 使用独立的
`loadup.framework.version` 导入；框架与第三方版本随该导入提供给消费方。Testify 不进入公开 BOM，由本仓库 parent 管理内部测试版本。
parent 中的注解处理器路径从这条 BOM 导入链解析版本，不另行维护 Framework 版本。
待验收：当前 BOM 的 Maven 模型解析、配套制品安装及目标仓库发布。
