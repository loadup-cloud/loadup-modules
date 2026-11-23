# loadup-modules-form 动态表单模块设计

本文描述目标设计和 Pay 接入方案。通用模块已开始实施，当前接口、表结构取舍和设备领用示例见 [模块 README](README.md)。

## 1. 目标与集成方式

应用先登记业务字段，再把字段编成可发布、可填写的表单。前端和后端共享字段 Key、值类型、有效范围和发布版本。业务应用既能直接创建“设备领用申请”这样的普通表单，也能把已有产品目录生成的配置交给表单模块渲染和填写。

首版采用可嵌入的 `client / core / web` 结构。`client` 提供字段声明、发布、填写和类型化读取接口；`core` 负责领域规则、事务和持久化；`web` 提供受权的设计与填写入口。通用渲染器位于 loadup-admin。Pay 将 core 嵌入 productcenter，共用本地数据源和事务管理器。这样一次销售方案发布或一次签约都能原子完成。模块划分遵循 [LoadUp 业务模块架构](../ARCHITECTURE.md)。

## 2. 配置归属与唯一维护入口

| 配置或事实 | 权威来源和编辑入口 | 另一方如何使用 |
|---|---|---|
| 字段 Key、业务含义、类型、单多值、枚举身份 | 程序依赖的字段由所属业务模块声明，form 在字段目录登记并检查一致性。纯采集字段经授权在目录创建 | 产品和模板从目录选择字段。前端直接使用发布结果中的 Key，Java 消费方使用同一业务声明 |
| 字段组、字段布局、控件、基础约束与可覆盖层级 | form 字段组设计页 | Pay 产品版本绑定确切的字段组版本。产品页可嵌入字段组选择器和只读预览 |
| 产品能力、使用条件、产品依赖和互斥 | productcenter 产品目录 | 表单接收条目所需的字段组。能力判断和使用条件由 Pay 执行 |
| 组合成员、必选或默认选中、条目预设值 | productcenter 组合页 | 发布时展开组合，将条目、值及来源交给 form |
| 销售方案选择的组合版本、别名、预设值、商户可改范围、售卖期和资格 | productcenter 方案页 | Pay 解析授权范围和覆盖结果，form 校验其符合字段契约并冻结；售卖资格留在 Pay |
| 普通表单的组、条目及覆盖规则 | form 模板设计页 | 可直接发布。单张表默认用保留条目 `ROOT`，无需建立产品、组合或销售方案 |
| 已发表单、草稿、提交值及值来源 | form 发布用例、填写用例 | Pay 生成的模板和发布结果供查看、预览、追溯；持有模板编辑权限也不能修改生成结果 |
| 合约签署、生效、暂停、修订、商业条款与交易准入 | productcenter 签约与条款用例 | 合约修订引用表单实例；交易执行沿用 Pay 的冻结商业条款 |

**一项配置只有一个写入口。** 例如，“线上收单”默认选中在 Pay 组合页设置一次。form 接收包含该结果的条目，生成只读发布记录。运营要更改它，应从表单详情的来源引用回到 Pay 创建新版组合；表单后台不提供对此记录的修改、重新编排或反向同步。普通表单的作者则直接在 form 操作。

## 3. 现有产品、组合、方案如何接入

当前产品自带参数定义；组合条目引用产品版本并可预设值；销售方案按“组合别名.条目编码”展开，解析逐层覆盖和商户可填范围。目录已有草稿、发布、退役以及草稿并发版本控制。接入沿用这些编辑入口和版本标识。[目录契约](../../loadup-pay/loadup-pay-productcenter/loadup-pay-productcenter-client/src/main/java/io/github/loadup/productcenter/client/dto/CatalogDefinitions.java) · [方案编译器](../../loadup-pay/loadup-pay-productcenter/loadup-pay-productcenter-domain/src/main/java/io/github/loadup/productcenter/domain/model/SalesPlanCompiler.java) · [目录发布用例](../../loadup-pay/loadup-pay-productcenter/loadup-pay-productcenter-app/src/main/java/io/github/loadup/productcenter/app/service/CatalogService.java)

| 接入点 | 目标改动 | 防止重复维护的约束 |
|---|---|---|
| 产品版本 | 面向填写的参数从内嵌定义迁为 `groupVersionId`。字段组提供这些字段的产品基线默认值与约束。产品独有的基线通过选用该产品专属的组版本表达 | 同一产品字段只从所绑定的组取定义和基线。领域内部专用参数仍由 Pay 管理，适配时明确映射 |
| 组合版本 | 保留 `itemKey → productVersionId`、必选、默认选中、条目预设和条件；预设值通过所引产品字段组提供的字段选择器录入 | form 不建第二套可编辑的 Pay 组合目录。Pay 预设只能引用组内已声明、允许该层调整的字段 |
| 销售方案版本 | 保留组合版本、别名、方案覆盖、商户策略。Pay 编译出最终条目地址、字段版本、有效约束、初始值、来源和编辑权限 | Pay 负责组合展开和逐层覆盖。form 只校验编译结果与字段约束的一致性，预览与发布共用这条编译路径 |
| 方案发布 | 锁定待发布版本、检查依赖、生成只读表单版本，在同一事务保存发布结果和 Pay 方案状态 | 来源版本唯一。同来源、同内容重试返回同一发布 ID；同来源却有不同内容则报冲突。失败后两边均不能显示发布成功 |
| 合约签署 | 通过方案版本创建草稿；预览与签约引用同一草稿版本。服务端从实例取已选条目和签约人输入 | 签约请求只携带实例引用和业务参数；去掉原请求中可再次录入所选条目和值的通道 |

迁移时应先清点已有产品参数，逐项标为“由表单采集”“由 Pay 提供的锁定配置”或“仅参与 Pay 内部商业策略”，列明旧参数、目标 Key、类型、取值单位、所属条目和实际消费位置。只在含义和基数一致时映射。未完成映射的必需字段阻止新方案发布。已有发布版和合约快照保持可回读；新流程从明确的新销售方案版本启用，同一版本只走一个签约入口。切换前对同一组输入核对旧、新商业条款及来源差异，已批准的语义变更单列。

## 4. 字段契约与版本关系

字段身份由 `namespace + fieldKey` 确定。业务声明的 `PAY.PAY_METHOD` 为单值 `PaymentMethod`。首次发布后固定 Key、业务含义、类型、基数、单位和枚举身份；更改含义须登记新 Key。标签、帮助、选项显示名可发新版本。枚举提交编码，普通业务字典可作为发布输入，发布时冻结允许的编码与标签。[现有字典模块](../loadup-modules-dictionary/README.md)

| 样例对象 | 它固定引用什么 |
|---|---|
| 收单配置组 `ONLINE_ACQ_CONFIG V2` | 字段 `PAY_METHOD V1`、`SETTLEMENT_DAYS V1` 等具体版本 |
| 产品 `ONLINE_ACQ V2` | 收单配置组 V2 |
| 组合 `MERCHANT_STARTER V1` | 条目 `ONLINE` 引用线上收单产品 V2，`NOTICE` 引用确定版本的通知产品 |
| 方案 `STANDARD_ONLINE V3` | 以别名 `BASE` 引用组合 V1。编译结果的条目为 `BASE.ONLINE`、`BASE.NOTICE` |
| 表单发布 `R3003` | 唯一来源为销售方案 V3，冻结完整定义和规则。已发布内容不可覆盖 |
| 表单实例 `F7001` | 创建时固定 `R3003`。V4 上线不替换其发布版本 |
| 合约 `C9001` 的修订 `1` | 绑定已提交实例 `F7001`，同时保存 Pay 自有的商业快照 |

字段、字段组和模板采用“草稿 → 已发布 → 退役”。引用只指向确定版本，发布时检查依赖可用性。独立表单退役后停止新建实例，历史提交可读。Pay 是否允许既有草稿完成签约，由原销售方案在签约时的状态、售卖期和商户资格决定。实例采用“草稿 → 已提交”；首版不扩展现有 Pay 在线改签能力。

## 5. 核心表与索引

以下为逻辑表，实施时沿用宿主的 ID、时间和审计约定。字段引用需要核对命名空间、归属关系和版本。所有写操作均在宿主授权范围内执行。

| 表 | 核心列 | 键与不变量 |
|---|---|---|
| `form_field` | id、namespace、field_key、owner、value_type、cardinality、value_set_code、status | 唯一 (namespace, field_key)，停用仍保留身份 |
| `form_field_version` | id、field_id、version_no、label、constraints、option_snapshot、status | 唯一 (field_id, version_no)，发布后不可变 |
| `form_group_version` | id、namespace、group_code、version_no、name、status、row_version | 唯一 (namespace, group_code, version_no)，草稿使用乐观锁 |
| `form_group_field` | group_version_id、field_id、field_version_id、section、sort_no、widget、required、default_value、allowed_values、override_policy | 唯一 (group_version_id, field_id)，约束 field_version_id 属于该 field_id |
| `form_template_version` | id、namespace、template_code、version_no、source_type、source_version_id、status、row_version | 模板编码按命名空间和版本唯一；外部生成记录另按 (namespace, source_type, source_version_id) 唯一 |
| `form_template_item` | template_version_id、item_key、group_version_id、source_item_ref、required、default_selected、sort_no | 唯一 (template_version_id, item_key)。来源引用对 form 是不透明标识，Pay 可据此追溯产品和组合版本 |
| `form_template_field_rule` | template_version_id、item_key、field_id、allowed_values、required、initial_value、input_mode、rule_origin | 每条目每字段最多一行，仅能引用该条目字段组的字段；区分默认与锁定 |
| `form_release` | id、template_version_id、schema_snapshot、schema_hash、published_at、published_by | template_version_id 唯一。快照冻结条目、字段版本、约束、来源和布局；哈希用于一致性核对 |
| `form_instance` | id、release_id、business_type、business_context_ref、status、row_version、created_by、submitted_at、创建与提交幂等标识及请求摘要 | 实例唯一绑定 release_id；幂等标识按宿主业务范围唯一；Pay 上下文绑定商户和签约范围 |
| `form_instance_item` | instance_id、item_key | 主键 (instance_id, item_key)，独立记录选择。没有输入值的可选产品也可被选中 |
| `form_instance_value` | instance_id、item_key、field_id、value、value_origin、origin_ref | 主键 (instance_id, item_key, field_id)。值使用受类型约束的标量或同类型数组，每行对应一个字段 |
| Pay 的合约修订表 | contract_id、revision、form_instance_id、原商业快照与摘要 | (contract_id, revision) 定位修订；新流程的 form_instance_id 必填且唯一。历史修订沿用原快照 |

草稿值记录用户已填写的覆盖项。展示时将覆盖项与固定发布版本的初始值合并。提交事务补全默认值与锁定值，移除未选条目的输入，冻结最终值及其来源。随后，实例值表是按 Key 取值的依据，商业快照是支付执行的依据。二者通过不可变实例引用在一次签约中建立对应关系。

## 6. 前后端接口契约

下表使用拟议操作名。Pay 可沿用现有管理 BFF 和内部管理接口前缀。权限在拥有数据的宿主服务校验，商户身份、可访问范围和系统提供的锁定值均由服务端确定。[现有 Pay 管理接口](../../loadup-pay/loadup-pay-productcenter/loadup-pay-productcenter-client/src/main/java/io/github/loadup/productcenter/client/http/ProductManagementHttpService.java)

| 入口与操作 | 输入 | 输出或语义 |
|---|---|---|
| form 设计：字段检索、字段组保存与发布 | 命名空间、字段版本引用、控件、基础规则、预期草稿版本 | 字段详情与引用位置；字段组版本。无效默认值、越权覆盖和控件类型不匹配在设计时定位到字段 |
| form 设计：普通模板保存、预览与发布 | 条目、字段组版本、覆盖、预期草稿版本 | 预览最终定义；发布返回 releaseId。外部来源模板只能通过所属应用发布 |
| Pay 配置：catalog/save、catalog/publish | 产品绑定的组版本，或组合与方案的原生定义、预期版本 | 发布销售方案返回 planVersionId、formReleaseId。预览给出最终值、来源及可编辑性 |
| 创建：普通表单 start 或 Pay merchant-contracts/start | 普通业务提供已授权的发布引用；Pay 提供 merchantId、scopeKey、planVersionId；均提供创建幂等键 | instanceId、releaseId、rowVersion。Pay 从方案查发布版本，防止调用者自行拼接商户、方案和表单 |
| 渲染：instances/detail | instanceId | 条目选择状态，字段 Key、类型、控件、选项、当前值、来源、可编辑性及草稿版本 |
| 填写：instances/save-draft | instanceId、expectedRowVersion、完整已选条目与可编辑字段覆盖值 | 新 rowVersion。未传的原覆盖项恢复继承值；取消条目清理其输入。允许暂缺必填项，拒绝未知 Key、向未选条目传值和提交锁定字段 |
| 普通业务：instances/submit | instanceId、expectedRowVersion、requestKey | 重新校验、冻结最终值，返回提交凭据。Pay 管理的实例仅由签约用例提交 |
| Pay：merchant-contracts/preview、merchant-contracts/sign | instanceId、expectedRowVersion、生效区间；签约另传 requestKey | 预览商业条款；签约返回 contractId、revision、instanceId。两次操作之间草稿变更须重新确认 |
| 后端：readValue、readValues | 普通调用传 instanceId、itemKey、类型化字段声明；Pay 查询可传 contractId、revision、itemKey、fieldKey | 仅从已提交实例返回类型化最终值，可附来源。ROOT 场景可省略条目；多条目字段地址有歧义时要求指定条目 |

校验错误携带条目、字段 Key 和原因。分别表达未知字段、字段类型不符、超出允许范围、条目未选、可选字段未填写和草稿版本冲突。配置编辑权限与实例读取权限分别授权。展示和服务端校验使用同一发布定义，正式提交时服务端独立验证。

## 7. 商户签约全流程

1. **配置产品。** Pay 声明单值枚举字段 `PAY_METHOD`。产品 `ONLINE_ACQ V2` 绑定收单配置组 V2，允许 `JSAPI`、`H5`，默认 `JSAPI`，签约时可改。字段组另含整数字段 `SETTLEMENT_DAYS`。支付渠道和模式的适用性仍由 Pay 校验。[现有 PaymentMethod 枚举](../../loadup-framework/loadup-components/integration/loadup-components-payment-channel/loadup-components-payment-channel-contracts/src/main/java/io/github/loadup/components/payment/channel/contract/PaymentMethod.java)
2. **配置组合。** 运营在 Pay 创建 `MERCHANT_STARTER V1`，选入必选的线上收单 `ONLINE` 和可选的交易通知 `NOTICE`，通知默认未选。form 不要求创建同名组合。
3. **发布方案。** `STANDARD_ONLINE V3` 以别名 `BASE` 选择此组合，把结算周期锁定为 1 天。预览中 `BASE.ONLINE / PAY_METHOD` 为可改单选框，`BASE.ONLINE / SETTLEMENT_DAYS` 为只读整数。Pay 检查收单场景必需字段，发布得到 `R3003`。若 Pay 要求单值枚举却引用文本字段，发布失败并定位到条目和 Key。
4. **打开并填写。** 签约人员为商户 `M10086` 及指定签约范围创建草稿 `F7001`。页面从 `R3003` 取得选项，以原 Key 提交 `BASE.ONLINE / PAY_METHOD = H5`，通知保持未选。服务端带入锁定的 1 天。直接提交 `payType` 或把结算周期改为 7 都会被拒绝。
5. **预览并签约。** Pay 在事务外查询可信商户资料，然后在本地事务中校验方案和商户资格、草稿版本、所选产品的依赖与互斥、支付方式与渠道模式的兼容性及既有商业条款。成功时一并冻结实例、保存合约 `C9001 / 1` 和商业快照。失败保留可编辑的原草稿。并发或重复请求通过请求键、摘要、版本和唯一绑定保护，同键同内容返回原结果，同键不同内容报冲突。[现有签约服务](../../loadup-pay/loadup-pay-productcenter/loadup-pay-productcenter-app/src/main/java/io/github/loadup/productcenter/app/service/MerchantContractService.java)
6. **读取值。** 业务应用用 `C9001 / 1` 定位 `F7001`，在已选条目 `BASE.ONLINE` 读取 `PAY_METHOD`，得到 `PaymentMethod.H5`，来源为“签约填写”。同处读取 `SETTLEMENT_DAYS` 得到整数 1，来源为“销售方案 V3 锁定”。V4 改默认值或改标签后，原修订结果仍相同。

样例只展示与填写有关的两个字段。现有 Pay 要求修订中恰好有一个符合条件的收单条目，且对订单、支付、费用、退款、通知、额度和渠道规则有完整校验。接入验收应使用具有完整条款的收单产品，在此基础上替换样例字段的录入来源。新增的单选支付方式表达条目选定方式；已有渠道、模式和可用方式集合继续按原领域规则编译。[Product Center 架构](../../loadup-pay/loadup-pay-productcenter/ARCHITECTURE.md)

## 8. 实施阶段与验收标准

阶段按依赖顺序推进。下表给出每阶段交付物和可核对的通过条件。

| 阶段 | 主要交付 | 验收用例与通过条件 |
|---|---|---|
| 1 · 通用定义与发布 | 字段目录、字段组、普通模板、发布编译及不可变快照 | 同域重复 Key 无法创建；已发布 Key 无法改类型；默认值越界、必填却既无值又不可填、重复条目地址均无法发布。发布 V2 后 V1 的定义和选项可独立回读 |
| 2 · 通用填写闭环 | 渲染器、实例、草稿、提交、按 Key 读取 | 设备领用表通过 ROOT 完成创建、填写、提交和类型化读取，无任何 Pay 依赖。两条目同 Key 可分别读取，省略条目返回歧义；未知 Key、锁定值篡改、过期草稿版本被拒绝。重复提交只产生一个结果 |
| 3 · Pay 目录接入 | 产品引用字段组；组合与方案沿用 Pay；预览、发布和来源追溯 | 运营只在 Pay 完成组合与方案配置即可生成 R3003。通用模板写接口拒绝编辑该生成结果。缺必需字段时 Pay 方案与表单均未发布。并发重复发布得到唯一 releaseId；上游出新版后 V3 仍引用原版本 |
| 4 · 签约与消费 | 签约草稿、商业校验、合约修订绑定、按修订取值 | 用完整商业配置完成 M10086 签约，读到 H5 和整数 1，来源分别正确；渠道不支持 H5 时签约失败。模拟签约保存失败后无已提交孤立实例；同键重试返回原合约，同键改内容被拒绝；他人实例无法读取或签约 |
| 5 · 现有方案切换 | 核对旧新条款、为选定新方案启用表单入口、验证历史回读与停止新签路径 | 对同一参与比较的配置和输入核对商业条款、依赖互斥、可改范围和额度片段；每项差异有归因。老合约按原快照可读，新方案只有一个配置与签约入口。撤下新方案后不能开始新签，已签修订仍可追溯 |

首版完成以两个业务结果为准：普通业务独立使用 ROOT 表单并按 Key 消费；Pay 从原生产品目录发布一次方案后即可完成签约，在指定修订和条目上稳定取得 `PAY_METHOD`。在线改签、任意脚本规则和独立远程表单服务留待有具体业务需求时另行设计。

P0 跳过。实际接入普通表单或 Pay 时，前后端在相应实施阶段一起确认具体字段 Key、类型、枚举编码和条目地址。
