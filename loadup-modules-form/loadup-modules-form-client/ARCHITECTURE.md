# Form Client 架构

本层定义表单目录与运行时的 Java API，是业务模块和表单实现之间的编译期边界。

## 契约路径

- `FormCatalog` 提供字段声明与发布、字段组草稿与发布、模板保存、预览、发布和退役。
- `FormRuntime` 提供 `start`、`detail`、`saveDraft`、`submit`，并提供 `readValues`、`readValue`、`readList` 类型化读取。
- `FormDefinitions` 与 `FormInstances` 保存命令、版本和结果结构。

普通表单可在发布后由业务应用创建实例，提交后按 `itemKey` 和字段 Key 消费值。Pay 等宿主也可在本地事务中调用 Catalog 与 Runtime。版本冻结、值来源和唯一写入口的共享语义见[架构设计](../ARCHITECTURE.md)。

## 依赖方向

业务应用依赖 client；core 实现两个 Facade；web 将受权 HTTP 请求映射至 Facade。本层无 Spring、数据库或 MVC 依赖。
