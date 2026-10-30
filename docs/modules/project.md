# Project

Project 是业务状态的事实源；Canvas 组织资源，不决定 Issue 的执行顺序。项目把工作阶段定义在一份
`workflow` JSON 中，Issue 保存当前阶段，Run 记录一次已接受执行的历史坐标；Agent 身份由
`Issue + Agent` 唯一绑定到一条持久 Thread，同一阶段的预算按 `Issue + state` 计数，
不会因为切换 Agent 重置。先从[系统设计](../system-design.md)理解模块边界；
完整的事务边界和表关系见[Canvas / Project](../canvas-project.md)。

[`apps/project`](../../apps/project/pom.xml) 是建立在平台之上的应用，集中提供领域规则、Issue 用例、
PostgreSQL 持久化、Issue 调度与 Agent 业务工具，经
[`ProjectAutoConfiguration`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/ProjectAutoConfiguration.java)
装配进宿主应用。它直接使用 [Platform](platform.md) 的公开服务（运行时、Agent 任务、全局 Blob、Agent 目录与
Session 深删除），并实现平台定义的 `IssueAgentOwnership`；平台从不依赖 Project。领域与 share DTO 的映射在
[`ProjectDtoMapper`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/dto/ProjectDtoMapper.java)，由本模块扫描为唯一组件，并注入宿主的 Jackson 3 `ObjectMapper` 与唯一的 [`ProjectWorkflowJsonCodec`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain/ProjectWorkflowJsonCodec.java)。Issue
Reconcile 的执行器、生命周期由 [Web](web.md) 组装。模块依赖方向由仓库检查
[`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 校验。

## 模块结构

| 包 | 内容 |
| --- | --- |
| [domain](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain) | workflow 配置、阶段流转与阶段预算授权规则 |
| [model](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/model) | Project、Issue、Run、Activity、Evidence、Work、Agent Thread 绑定、阶段预算行与暂停原因等值 |
| [repo](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/repo) | 按实体拆分的具体 `JdbcClient` Repository（直接承载 SQL、参数绑定与 RowMapper）及项目变更通知 |
| [service](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service) | 用例级事务边界：Project、Issue 编辑/生命周期/阶段预算、Run、Evidence 与 Work 邮箱；`IssueMutationSupport` 共用 owner 锁与幂等回执 |
| [reconcile](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile) | Issue 调度的部署级配置与确定性推进 |
| [platform](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform) | 对运行时与全局 Blob 的窄适配：Issue/Run 执行所需的 Thread 事实与命令控制、证据 Blob |
| [tool](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/tool) | Project Harness Contributor：`issue_transition` 等业务工具与 Run 上下文 projector |
| [turn](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/turn) | 冻结在 branch 上的 `ProjectRunScope` 及其编解码 |
| [dto](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/dto) | 领域模型与 share wire DTO 的双向映射 |
| [error](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/error) | 模块自有错误模型与稳定的 Web 映射 |

## 平台能力与 owner 归属

Project 只经两个窄适配接触运行时与存储，平台异常在此收敛为 Project 错误：

- [`IssueThreadExecution`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform/IssueThreadExecution.java) 读取线程事实、接受命令与 Join、停止并取得权威 head、查询固定回执；Run 任务经平台的 `AgentTaskService` 提交。读取返回 [`IssueThreadView`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform/IssueThreadView.java)：Session、head、version、下一命令序号、历史路径与统一执行事实，Project 不取得完整运行时快照。控制操作加入调用方事务，权威版本冲突原样传播。
- [`EvidenceBlobs`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform/EvidenceBlobs.java) 锁定并消费一次已 READY 的上传、对 Blob 引用做 retain/release、判定 Blob 是否仍可引用；上传不存在译为 not found，未 READY 或已清理译为 validation。

Agent 分支设置直接由平台的 `AgentBranchSettingsMaterializer` 按当前 Agent/Model 目录物化；`Issue + Agent` 名下 Session 的深删除直接调用平台的 `SessionDeletionOrchestrator`。

`Issue + Agent` 是平台编排认识的产品 owner 之一。[`ProjectIssueAgentOwnership`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/ProjectIssueAgentOwnership.java) 实现平台定义的 `IssueAgentOwnership`：命令接受与人工交互按 `Project KEY SHARE -> Issue UPDATE` 锁定产品层级（Project/Issue 缺失、归档或层级不一致时拒绝），深删除用排他锁，并读写 `(issueId, agentName) -> threadId` 稳定绑定。锁序因此由 Project 自己维护，平台编排只按固定顺序调用它。

预算、等待、停止和证据发布政策由 Project 决定，停止区间以本次回执的权威 head 为界。

## 工作流配置

[`ProjectWorkflowJsonCodec`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain/ProjectWorkflowJsonCodec.java)
严格解析 `{"states":[...]}`：拒绝重复字段、未知字段、尾随内容和非法类型；编码为确定性 JSON，
可用于请求指纹。每个 state 使用项目内唯一的大写自然编码（`[A-Z][A-Z0-9_]{0,63}`），
阶段数量由项目配置决定。默认流程为 `INIT -> WORK -> DONE`，另含 `BLOCKED`；默认 WORK 是人工阶段。
`INIT`、`BLOCKED`、`DONE` 必须存在；正常边只指向已声明阶段，不通往 `BLOCKED`。
启用工作阶段须从 `INIT` 可达，且存在到 `DONE` 的正常路径。

[`IssueStateTransitions`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueStateTransitions.java)
区分正常转移、业务阻塞与恢复，以及从 DONE 显式重开。问卷等待与人工暂停分别由 Runtime 等待事实和 Issue 暂停字段表达。

## 预算与运行记录

[`IssueStageBudget`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueStageBudget.java)
承载授权事实：只面向 workflow 中启用且有 Agent 的工作阶段，`maxRuns` 为正，重置高水位只能推进到
`nextRunOrdinal - 1`，不能回退而重新授权历史 Run。消耗由持久化按本 Issue、本阶段、序号超过高水位的全部
Run 计数，失败、取消和 UNKNOWN 也消耗一次；额度只限制**新 Run**，不限制已接受 Run 的恢复。

执行记录落在 model 行：
[`IssueAgentThread`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/model/IssueAgentThread.java)
保存 `(issueId, agentName) -> threadId` 稳定绑定；
[`IssueRun`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/model/IssueRun.java)
保存 `(startEntryId, endEntryId]`、Session/Thread、阶段与终态事实。每 Issue 唯一活动 Run、
终态必须冻结区间、FAILED/UNKNOWN 必须给出原因等不变量由 `project_issue_run` 的检查约束与
部分唯一索引最终保证；Entry 父链、跨表外键与锁序校验由 service 与 Runtime 负责，领域层不重复实现。

Run 的预算、等待与停止政策只消费 Runtime 投影的统一执行事实 [`ThreadExecutionFacts`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadExecutionFacts.java)：存在在途工作（排队命令 / 合法本地 Model / `READY`、`RUNNING` 工具 / owned continuation）时 `reconcile` 只延后重检，绝不投递指示或收尾；只有 `WAITING_APPROVAL` / `WAITING_INPUT` 才把 Run 转入等待；`UNKNOWN` 工具是已收敛的模型反馈，不作为人工核查门禁，只在人工停止且仍有在途工作或结果未定时让 Run 收敛为 `UNKNOWN` 暂停。Project 不再自行遍历 Model、工具与 `TURN_END` 推导「在途」。

Project 的 `yoloEnabled` 只在首次创建该 `Issue + Agent` 执行根时初始化其
YOLO 开关；修改项目开关不改写既有 Thread。该根委派的后代读取根的开关，
后续人工开关只更新根行。每次 Run 由 Project 显式提交 `SET_AGENT` / `SET_MODEL` /
`SET_ENVIRONMENT`、`SET_CONTRIBUTOR_STATE` 与末尾任务输入；阶段 Environment 就来自该 Run 的
显式命令，Platform 不按 workflow 反查解析。它作为普通配置命令随 Turn 冻结，不是对已存在
Thread `BranchSettings` 的带外重写。

`branch` 上的 [`ProjectRunScope`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/turn/ProjectRunScope.java)
以 contributor `project` / customType `run` / schemaVersion 1 冻结本次 Run 的 issue/project/stage 上下文与
`sourceThreadId`，并显式记录 `active`：Run 接受时为 true，结束时在同一业务锁内接受一条同一 runId、
`active=false` 的纯 `SET_CONTRIBUTOR_STATE` 快照；projector 对已关闭 scope 输出空，业务写工具拒绝
已关闭 scope，新 Run 的 true 快照按命令顺序覆盖旧 scope。`issue_transition` 从冻结 scope 取得 runId，
在业务锁内校验 Run/Issue/阶段/版本与调用 Thread 身份，fork 不凭继承 scope 获权。Run 完成只认本次
root Join 冻结的 `terminalEntryId` / `finalAnswerEntryId`（`COMPLETED` / `ERROR` / `CANCELLED` 分别
完成 / 失败 / 取消），既不按快照最新 head 猜测，也不等待永久子树 idle；工具 UNKNOWN 只作为模型反馈，
不升级为人工核查暂停。

## 用例事务与持久化

[`ProjectService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/ProjectService.java)、
[`IssueEditingService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueEditingService.java)、
[`IssueLifecycleService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueLifecycleService.java)、
[`IssueStageBudgetService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueStageBudgetService.java)、
[`IssueRunService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueRunService.java)
与 [`IssueEvidenceService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueEvidenceService.java)
是写用例的事务边界，`IssueReconciler.reconcile` 在 `reconcile` 包内以单事务推进一个有界动作；
[`IssueMutationSupport`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueMutationSupport.java)
只集中 Issue 写路径共用的 `Project KEY SHARE -> Issue UPDATE` 锁序、幂等 receipt、版本/归档门禁与活动落库，
不承载具体动作或状态/额度规则；Repository 只做单表读写与 CAS 行数判定。
写入或删除 Project、Issue、Run、Activity、阶段预算、公开证据与 Agent Thread 绑定时，仓库在同一事务内
用 `pg_notify('project_issue_changed', projectId)` 发送快照失效提示；PostgreSQL 提交后投递、回滚不投递，
同一事务内相同 payload 合并。删除 Issue 前读取其 Project id；通知不依赖 HTTP 入口，调度与 worker
写入也会覆盖。Work 租约/唤醒只影响内部调度，走共享的 `durable_work` 通道，不产生项目快照失效信号。
幂等事实落在 `project_issue_activity`（`idempotencyKey` 精确重放；`body` 的非空性按 `kind` 固定：
COMMENT/INSTRUCTION 必须非空白且不超过 1 MiB，RUN、SPEC_CHANGE、STATE_CHANGE、CONTROL 必须为空）；
Stage 预算与 Evidence 分别落在 `project_issue_stage_budget`、`project_issue_evidence`；
Work 邮箱是共享 `durable_work` 表中 target type 为 `ISSUE` 的行。

[`IssueEvidenceService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueEvidenceService.java)
管 Issue **自有的**公开证据：`project_issue_evidence` 一行是 Issue 持有的一个已发布 Blob 引用，
与 Session 引用各自独立计数，不由 Session 生命周期决定。人工附件经 `lockReadyUpload -> retain ->
deleteUpload` 在调用方事务内完成引用转移，展示名取自上传行而不是客户端；证据行已存在时不重复
retain，重复交付不双计。深删除 Session 只释放该 Session 自己的引用。

Project 深删除在一个事务内锁住 Project 行（`FOR UPDATE`），拒绝仍活动的 Run 与未解除的 `UNKNOWN` 暂停，
再按 Evidence（先于 Run 行，因为 `project_issue_evidence.run_id` 是 RESTRICT 外键）->
Activities -> Work -> Runs -> Stage 预算 -> Agent Session 深删除 -> Issue -> Project 的顺序清理；
Issue 与 Project 行用 `version` CAS 删除并检查受影响行数。

## REST 调度

[`reconcile`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile/package-info.java)
承载 Issue 的部署级配置与确定性推进：`IssueReconcileDispatcher` 只做 `durable_work` 中 `ISSUE` 行的
claim、bounded handoff 与 poll 生命周期，合并 wake 的单 drain、容量计数、stop 收尾与 executor
拒绝/失败分型复用 [Common](common.md) 的
[`BoundedWorkDispatcher`](../../common/src/main/java/fun/fengwk/kkstudio/common/concurrent/BoundedWorkDispatcher.java)，
[`IssueReconciler`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile/IssueReconciler.java)
在固定锁序下一次推进一个有界动作。调度只依赖数据库中的 work 行与 lease token，通知与 poll
提供发现入口；租约参数见 [Platform](platform.md) 的配置表。

Work 的请求、claim、续租、完成与重排由共享的
[`DurableWorkQueue`](../../work/src/main/java/fun/fengwk/kkstudio/work/DurableWorkQueue.java)
完成，Project 只持有 target type 为 `ISSUE` 的实例 `issueWorkQueue`：时间一律取数据库
`statement_timestamp()` 并截断到毫秒，调用方传相对 `Duration`，Dispatcher 不持有 Clock；请求合并较早 due
并递增 wake_version，完成与重排以 lease token 围栏，处理期间到达的新 wake 不会被吞掉。
Harness 的调度时间域见 [Harness Infra](harness-infra.md#work-的-claim--lease--wake)。

## 错误映射

模块内的 `ProjectValidationException`、`ProjectNotFoundException`、
`ProjectVersionConflictException`、`ProjectDuplicateException` 在 Web 边界由
`StudioProjectErrorAdvice` 统一映射：validation 400、not found 404、version/duplicate/runtime conflict 409、
`IllegalStateException` 500，并输出稳定的 `PROJECT_*` 错误码。在 Agent 工具边界由
[`tool`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/tool) 包译成 `AiValidationException` / `AiVersionConflictException` 等平台错误类型。

## 测试与验证

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -pl apps/project -am verify
```

`verify` 对 domain 包做 ≥90% 行覆盖检查，并运行确定性调度与 owner 归属单测。事务、锁序与平台适配的端到端语义由同模块的
Project/Issue 集成测试（如 [`IssueRunAcceptanceIntegrationTest.java`](../../apps/project/src/test/java/fun/fengwk/kkstudio/project/IssueRunAcceptanceIntegrationTest.java)）以真实 PostgreSQL 加平台测试宿主覆盖；平台通过
`platform-runtime` 的 test-jar 提供 Spring/PostgreSQL 测试基座。
