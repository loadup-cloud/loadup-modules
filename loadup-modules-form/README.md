# LoadUp 动态表单

通用表单支持登记字段、发布字段组与模板、创建草稿、提交、按固定 Key 读取最终值。业务可以直接在自己的应用中嵌入，设计和填写接口由可选的
Web 模块提供。Pay 接入方式与后续验收见 [架构设计](ARCHITECTURE.md)。

## 接入

| 依赖                         | 用途                                                       |
|------------------------------|------------------------------------------------------------|
| `loadup-modules-form-client` | 字段、发布定义、实例契约与 Java Facade                     |
| `loadup-modules-form-core`   | PostgreSQL 持久化，自动装配 `FormCatalog` 和 `FormRuntime` |
| `loadup-modules-form-web`    | Spring MVC 接口，使用 LoadUp 方法授权和结果信封            |

宿主提供单一或主数据源及其事务管理器。为 Flyway 增加 `classpath:db/migration/framework/form`，使用宿主选择的 PostgreSQL
schema。`loadup.modules.form.enabled=false` 可关闭用例自动装配。Web 需要 LoadUp 方法授权启用。

## 前后端字段约定

- 命名空间、字段 Key、模板和字段组编码使用大写 ASCII 字母、数字、下划线，以字母开头。条目可以用点连接编码，如 `BASE.ONLINE`
  。单条目表单可用 `ROOT`。
- 字段身份由命名空间和 Key 确定。发布后可增加版本调整标签、说明、选项和约束。类型、单多值、数值单位、枚举身份和权属随字段身份固定。
- 业务依赖的 Key 通过 Java `FormCatalog.declareField` 登记。运营维护的采集字段通过 Web 发布。Java 枚举读取要求
  `valueSetCode` 等于类的全限定名，选项编码对应枚举常量名。其他枚举以字符串读取。
- 整数使用 JSON 整数，限制在 JavaScript 安全整数范围内。小数使用十进制字符串，例如 `"12.50"`；日期使用 ISO 日期字符串
  `"2026-11-23"`。布尔使用 JSON 布尔；枚举发送编码字符串；多选枚举发送去重数组。
- 渲染器从 `release.items[].fields[]` 获取 Key（`field.key`）、类型、控件、选项和约束，使用实例的 `selectedItems` 与 `values`
  回显。自动带入的默认值或锁定值保留来源。保存草稿时只传用户填写的覆盖项，已有来源为 `USER` 的值可继续随草稿提交。省略覆盖项表示恢复继承值。

## HTTP 接口

在 LoadUp WebMVC 默认配置下，下面的路径使用前缀 `/api/forms/{namespace}`。接口返回 LoadUp 的 `result` 和 `data`。业务失败通过
`result.code`（如 `FORM_LOCKED_FIELD`）区分，`data` 包含可定位的 `itemKey`、`fieldKey` 和 `reason`。

| Authority                            | 方法和路径                                                                       | 主要请求字段                                                                 |
|--------------------------------------|----------------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `form:{namespace-lowercase}:design`  | GET `/fields`                                                                    | keyPrefix、offset、limit                                                     |
| `form:{namespace-lowercase}:design`  | GET `/groups`、`/templates`                                                      | offset、limit                                                                |
| `form:{namespace-lowercase}:design`  | GET `/groups/{id}`、`/templates/{id}`、`/templates/{id}/preview`                 | 版本 ID                                                                      |
| `form:{namespace-lowercase}:design`  | PUT `/groups`、`/templates`                                                      | code、version、expectedRowVersion、definition。创建时省略 expectedRowVersion |
| `form:{namespace-lowercase}:publish` | POST `/fields/publish`                                                           | version、field                                                               |
| `form:{namespace-lowercase}:publish` | POST `/groups/{id}/publish`、`/templates/{id}/publish`、`/templates/{id}/retire` | expectedRowVersion                                                           |
| `form:{namespace-lowercase}:fill`    | POST `/instances/start`                                                          | releaseId、requestKey                                                        |
| `form:{namespace-lowercase}:fill`    | GET `/instances/{id}`                                                            | 实例 ID                                                                      |
| `form:{namespace-lowercase}:fill`    | PUT `/instances/draft`                                                           | instanceId、expectedRowVersion、selectedItems、values                        |
| `form:{namespace-lowercase}:fill`    | POST `/instances/submit`                                                         | instanceId、expectedRowVersion、requestKey                                   |

填写者身份来自宿主认证主体。实例查询、保存、提交均按命名空间和填写者限定。Java 宿主从可信上下文传入填写者身份。模板退役后停止创建实例，已有草稿继续使用其发布版本。

权限码中的命名空间转为小写。例如请求路径中的 `ASSET` 使用 `form:asset:fill`。字段 Key 和 API 路径继续使用发布定义中的大写编码。

## 设备领用表接入示例

1. 在 `ASSET` 发布 `DEVICE_TYPE` 单选枚举字段，选项可以是 `LAPTOP` 和 `MONITOR`。发布 `QUANTITY` 整数字段，
   `constraints.min` 为 `"1"`。
2. 创建并发布字段组 `DEVICE_REQUEST_FIELDS` V1。组的 `definition.fields` 引用两个 `fieldVersionId`，分别选用 `SELECT` 和
   `NUMBER` 控件，设为必填。接着发布模板 `DEVICE_REQUEST` V1，`definition.items` 包含必选且默认选中的 `ROOT`，持有已发布的
   `groupVersionId`。发布模板返回 `Release.id`。
3. 用 `releaseId` 和请求键创建草稿。用返回的实例 ID 和 `rowVersion` 保存以下草稿。

```json
{
  "instanceId": "<实例 ID>",
  "expectedRowVersion": 1,
  "selectedItems": ["ROOT"],
  "values": {
    "ROOT": {"DEVICE_TYPE": "LAPTOP", "QUANTITY": 2}
  }
}
```

4. 用更新后的草稿版本和提交请求键正式提交。Java 业务可以通过
   `readValue("ASSET", actor, instanceId, "ROOT", "QUANTITY", Integer.class)` 得到整数 2。使用错误类型会得到
   `FORM_INVALID_TYPE`。可选字段未填写时返回空。
5. 同一实例、同一草稿版本与提交键重试，返回原提交结果。模板发布 V2 后，已创建实例继续使用 V1 的定义、选项和最终值。

目前组和模板共用版本表，定义正文使用类型明确的 DTO 保存。字段、字段版本、发布版本、选中条目和提交字段值都有独立记录，提交值通过字段版本的外键定位。设计文档中的
Pay 生成模板、合约绑定和对应管理页面属于后续接入。

## 子模块入口

- 客户端契约的[接入](loadup-modules-form-client/README.md)与[设计](loadup-modules-form-client/ARCHITECTURE.md)。
- 业务实现的[接入](loadup-modules-form-core/README.md)与[设计](loadup-modules-form-core/ARCHITECTURE.md)。
- Web 适配的[接入](loadup-modules-form-web/README.md)与[设计](loadup-modules-form-web/ARCHITECTURE.md)。
