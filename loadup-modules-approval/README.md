# LoadUp Approval

通用顺序审批支持在线配置模板、提交时选人、进行中换人、个人待办、审批轨迹和可靠结果投递。默认使用 PostgreSQL 和 UPMS 身份目录。业务系统通过 API 或 Java 接入，保存各自的冻结业务数据。

## 装配与授权

- 同进程业务依赖 `loadup-modules-approval-client`，宿主安装 `loadup-modules-approval-core`。HTTP 宿主安装 `loadup-modules-approval-web` 并启用资源服务器认证。模块使用宿主的 DataSource、事务管理器和可靠任务组件。
- 在宿主 Flyway 路径中加入 `classpath:db/migration/framework/approval`。可靠任务表由宿主安装。LoadUp Boot 已加入审批依赖和迁移路径。审批迁移为独立的新增表，应用回退时可保留这些数据。
- 在 UPMS 为每个业务应用设置独立编码，例如 `asset`。授权后重新登录以刷新身份令牌，从 Admin 的“审批管理”打开应用并建立首个流程。模板步骤、角色、部门和具体人选均在页面或管理 API 中维护。
- 独立宿主可提供 `ApprovalDirectory` Bean。适配器负责按接入应用限定可见身份和实时有效性。随模块提供的 UPMS 适配器使用同一部署内的身份目录，步骤范围可配置用户、角色和部门。

| 应用权限 | 用途 |
|---|---|
| `approval:asset:design` | 查询目录、编辑、复制、发布及退役模板 |
| `approval:asset:start` | 读取启用模板、查选候选人、发起及列出本人申请 |
| `approval:asset:decide` | 查看个人待办并办理当前获指派的步骤 |
| `approval:asset:adjust` | 查看应用内申请并调整当前及未来步骤的人选 |
| `approval:asset:read` | 查询应用内申请及其结果 |
| `approval:asset:retry` | 重投失败的结果事件。在 Admin 中搭配 read 或 adjust 使用。 |

模板管理、发起、换人、审批与撤回使用个人 JWT，独立应用可转发其面向审批服务的可信操作者 JWT。服务 JWT 可在其 read 权限内查询结果。内嵌调用的 `Approval.Caller` 由宿主认证适配器构造，包含稳定主体 ID、个人身份标记和审批权限。APP 权限决定管理范围，步骤候选规则决定个人办理范围。

## 接入流程

1. 管理员使用 Admin 或模板 API 发布流程。
2. 业务应用查询启用版本与候选人，冻结业务内容并生成业务引用及摘要。
3. 调用 `ApprovalFacade.start` 或 `POST /api/v1/approval/{app}/instances`，保存返回的实例 ID。发起参数与 HTTP 示例见 [架构设计](ARCHITECTURE.md#6-java-和-http-接入契约)。
4. 业务应用通过 `ApprovalFacade.detail` 或 `GET /api/v1/approval/{app}/instances/{id}` 取得状态。审批工作台提供待办、通过、驳回、撤回及授权换人。具名待办在宿主装配 Gotone 和 Inbox 时可发送站内提醒；管理员须先发布 `approval.pending` 的 IN_APP 服务配置（声明 `actionUrl`，并设置类别、标题、正文和 `actionPath=${actionUrl}`）。Gotone 迁移不预置该服务或模板。共享角色待办显示在个人待办列表中。
5. 业务应用处理终态，并先核对实例与本地冻结版本。结果事件允许重复，消费端以 `eventId` 去重。撤回和驳回也会产生终态事件。

## 结果接收

同进程使用 `ApprovalOutcomeHandler` Bean，其 `appCode()` 返回所属应用编码。远程接入方可按应用配置一个 HTTPS 回调，凭据由部署环境注入。仅传入业务引用、摘要及结果，接收端须验证服务身份并在持久化接收后返回 HTTP 2xx。

```yaml
loadup:
  modules:
    approval:
      webhooks:
        asset:
          url: https://asset.example.com/api/internal/approval-outcomes
          bearer-token: ${ASSET_APPROVAL_WEBHOOK_TOKEN}
```

同一应用使用一个接收通道。没有回调与 Handler 的应用通过查询接口获取结果。审批端将失败投递保留在可靠任务中，可由持有 retry 权限的用户在详情页填写原因重投。
