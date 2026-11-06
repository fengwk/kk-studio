# Project

Project 是业务状态的事实源；Canvas 组织资源，不决定 Issue 的执行顺序。项目把工作阶段定义在一份
`workflow` JSON 中，Issue 保存当前阶段，Run 记录一次已接受执行的历史坐标；Agent 身份由
`Issue + Agent` 唯一绑定到一条持久 Thread，同一阶段的预算按 `Issue + state` 计数，
不会因为切换 Agent 重置。先从[系统设计](../system-design.md)理解模块边界；
跨域数据约束与 ER 关系见 [Schema](schema.md)。

[`apps/project`](../../apps/project/pom.xml) 是建立在平台之上的应用，集中提供领域规则、Issue 用例、
PostgreSQL 持久化、Issue 调度、Agent 业务工具与 Project/Issue HTTP 边界，经
[`ProjectAutoConfiguration`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/ProjectAutoConfiguration.java)
装配进宿主应用。它直接使用 [Platform](platform.md) 的公开服务（运行时、Agent 任务、全局 Blob、Agent 目录与
Session 深删除），并实现平台定义的 `SessionOwnership` 与 `AgentReferenceCheck`（含 Issue Agent 归属）；平台从不依赖 Project。Project 的 HTTP DTO 与领域映射由本模块持有，映射在
[`ProjectDtoMapper`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/dto/ProjectDtoMapper.java)，由本模块扫描为唯一组件，并注入宿主的 Jackson 3 `ObjectMapper` 与唯一的 [`ProjectWorkflowJsonCodec`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain/ProjectWorkflowJsonCodec.java)。Issue
Reconcile 的执行器、生命周期由 [Web](web.md) 组装。模块依赖方向由仓库检查
[`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 校验。

## 模块结构

| 包 | 内容 |
| --- | --- |
| [domain](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain) | workflow 配置、阶段流转与阶段预算授权规则 |
| [model](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/model) | Project、Issue、Run、Activity、Evidence、Work、Agent Thread 绑定、阶段预算行与暂停原因等值 |
| [repo](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/repo) | 按实体拆分的具体 `JdbcClient` Repository（直接承载 SQL、参数绑定与 RowMapper）及项目变更通知 |
| [service](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service) | 用例级事务边界：Project、Issue 编辑/生命周期/阶段预算、Run、Evidence 与 Work 邮箱；`ProjectSnapshotAssembler` 与 `IssueDetailAssembler` 聚合快照；`IssueMutationSupport` 共用 owner 锁与幂等回执 |
| [reconcile](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile) | Issue 调度的部署级配置与确定性推进 |
| [platform](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform) | 对运行时与全局 Blob 的窄适配：Issue/Run 执行所需的 Thread 事实与命令控制、证据 Blob |
| [tool](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/tool) | Project Harness Contributor：`issue_transition` 业务工具 |
| [dto](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/dto) | Project/Issue wire DTO 与领域模型双向映射 |
| [http](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/http) | Project/Issue REST 控制器与输入适配；领域行为在 `service`，错误由 Web 统一映射 |

## 平台能力与 owner 归属

Project 经窄适配使用运行时与存储：

- [`IssueThreadExecution`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform/IssueThreadExecution.java) 读取线程事实、接受命令与 Join、停止并取得权威 head、查询固定回执；Run 任务经平台的 `AgentTaskService` 提交。读取返回 [`IssueThreadView`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform/IssueThreadView.java)：Session、head、version、下一命令序号、历史路径与统一执行事实，Project 不取得完整运行时快照。控制操作加入调用方事务，权威版本冲突原样传播。
- [`EvidenceBlobs`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/platform/EvidenceBlobs.java) 锁定并消费一次已 READY 的上传、对 Blob 引用做 retain/release、判定 Blob 是否仍可引用；上传不存在为 not found，不可消费时保留存储的 `conflict` / `STORAGE_VERIFICATION`。

Agent 分支设置直接由平台的 `AgentBranchSettingsMaterializer` 按当前 Agent/Model 目录物化；`Issue + Agent` 名下 Session 的深删除直接调用平台的 `SessionDeletionOrchestrator`。

`Issue + Agent` 是平台编排认识的产品 owner 之一。[`ProjectSessionOwnership`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/ProjectSessionOwnership.java) 实现平台定义的 `SessionOwnership`：命令接受与人工交互按 `Project SHARE -> Issue UPDATE` 锁定产品层级（接受时 Project 缺失或已归档、Issue 缺失或层级不一致时拒绝），深删除用排他锁，并读写 `(issueId, agentName) -> threadId` 稳定绑定。规则是不允许分叉、不允许通用命令直写，删除 Session 前先删除绑定行；锁序由 Project 自己维护，平台编排只按固定顺序调用它。
[`ProjectAgentReferenceCheck`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/ProjectAgentReferenceCheck.java) 实现平台的 `AgentReferenceCheck`：删除 Agent 前由本实现检查未归档项目的工作流阶段，平台核心不直读 `project` 表。

预算、等待、停止和证据发布政策由 Project 决定，停止区间以本次回执的权威 head 为界。

## 工作流配置

`project.workflow` 的持久化文本是严格 JSON。例如由设计和检查两个 Agent 阶段组成的流程：

```json
{
  "states": [
    {"state": "INIT", "name": "待开始", "next": ["DESIGN"]},
    {
      "state": "DESIGN",
      "name": "设计",
      "agent": "designer",
      "instructions": "完成可交付方案",
      "maxRuns": 3,
      "next": ["REVIEW"]
    },
    {
      "state": "REVIEW",
      "name": "检查",
      "agent": "reviewer",
      "instructions": "按 Issue 验收要求检查",
      "maxRuns": 3,
      "next": ["DESIGN", "DONE"]
    },
    {"state": "BLOCKED", "name": "业务阻塞"},
    {"state": "DONE", "name": "完成"}
  ]
}
```

数组顺序决定展示顺序，`next` 声明正常转移白名单。状态编码匹配
`[A-Z][A-Z0-9_]{0,63}`，项目内唯一。INIT、BLOCKED、DONE 是必备保留态，其余为工作
阶段，数量由项目配置决定。工作阶段可配置 `agent`、`environment`、`instructions`、
`maxRuns` 和 `enabled`（默认 true）。绑定 Agent 要求正数 maxRuns；人工阶段省略
Agent、Environment 与 Run 额度，由人工显式推进。

默认流程为 INIT→WORK→DONE，另含 BLOCKED，WORK 是人工阶段。业务阻塞与恢复使用
专用操作；DONE 显式重开回 INIT。流程允许返工环，拒绝自身边。启用工作阶段须从
INIT 可达，INIT 须存在到 DONE 的正常路径。

workflow 整体保存时锁定 Project、检查版本，并要求项目无活动 Run。保存校验编码、
合法边、可达性与引用；所有现存 Issue 的当前阶段及阻塞恢复点都须保留。
工作流编辑读取 Project Snapshot 的 `referencedStateCodes` 实施相同引用保护，
不依赖可见看板列表。后台推送刷新约束而不覆盖打开中的草稿。
Agent 与阶段 Environment 由 Project 在执行接受时以显式命令提交并冻结，缺失引用明确拒绝；
live turn 只读取冻结的命令，不按 workflow 反查。

[`ProjectWorkflowJsonCodec`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain/ProjectWorkflowJsonCodec.java)
严格解析 `{"states":[...]}`：拒绝重复字段、未知字段、尾随内容和非法类型；编码为确定性 JSON，
可用于请求指纹。

[`IssueStateTransitions`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueStateTransitions.java)
区分正常转移、业务阻塞与恢复，以及从 DONE 显式重开。问卷等待与人工暂停分别由 Runtime 等待事实和 Issue 暂停字段表达。

## 预算与运行记录

```text
Issue A
  +-- designer -> Thread D -> Run 1(DESIGN), Run 3(REWORK)
  +-- reviewer -> Thread R -> Run 2(REVIEW), Run 4(REVIEW)
```

`(issueId, agentName)` 在首次接受执行时创建稳定 Thread 绑定；同 Agent 跨阶段、
返工与重开复用该 Thread。每个绑定拥有独立 Harness Session，提供历史与附件授权
范围；绑定保存 Session/Thread，复合外键保证二者一致，Run 冻结 Session/Thread 和历史区间。
分支归属通过 Harness 读取调用线程的 Session，再查询 Project 的绑定，因此同一 Session 的兄弟分支即使没有 Run 也受同一归属约束。

阶段额度按 `(issueId,state)` 授权：

```text
used = COUNT(Run WHERE issue_id = ? AND state = ?
                   AND ordinal > budget_after_ordinal)
remaining = max(0, max_runs - used)
```

Run ordinal 在 Issue 内单调分配。新 Run 的失败和取消都消耗一次额度；
恢复已接受 Run、工具循环、协议续生成和问答等待沿用原次数。更换 Agent、返工或重开
仍使用同一阶段预算。workflow 默认额度用于首次授权，既有额度由明确授权或重置操作维护。

[`IssueStageBudgetService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueStageBudgetService.java)
承载授权事实：只面向 workflow 中启用且有 Agent 的工作阶段，`maxRuns` 为正，重置高水位只能推进到
`nextRunOrdinal - 1`，不能回退而重新授权历史 Run。消耗由持久化按本 Issue、本阶段、序号超过高水位的全部
Run 计数，失败和取消也消耗一次；额度只限制**新 Run**，不限制已接受 Run 的恢复。
人工重置要求无活动 Run，高水位取服务端 `next_run_ordinal - 1`，前后额度与高水位写入 CONTROL
活动。used/remaining 在读取时计算，Agent 通过受控执行使用额度。已接受 Run 还受
持久活动时长余额与模型、工具安全上限约束，安全等待暂停活动计时。

### 业务状态与控制门禁

| 事实 | 表达方式 | 用户操作 |
| --- | --- | --- |
| 当前工作阶段 | `Issue.state` | 沿合法边转移，DONE 可显式重开 |
| 业务阻塞 | BLOCKED、原阶段和原因 | 阻塞后显式恢复到原阶段 |
| 人工暂停或执行失败 | USER / ERROR 暂停原因 | 检查后显式恢复 |
| 副作用结果不明 | FAILED Run 与 ERROR 暂停 | 核查后显式恢复 |
| 工具等待输入或审批 | Harness ToolInvocation | 在 Pane 或待处理入口回答/决定 |
| 新 Run 额度耗尽 | 阶段预算与 Run 历史计算 | 人工授权或重置 |

同一 Issue 只有一个 RUNNING/WAITING 主 Run。业务阻塞后停止新派发，在途调用按原 Run
身份继续，到安全点才置 WAITING；控制暂停在活动 Run 收尾后设置并阻止新派发。回答问卷后，业务 BLOCKED 仍需显式恢复。
输入等待恢复沿用原 Run，即使新 Run 额度已经用尽也可继续。

人工停止通过 STOP 操作发起，继续执行通过显式 resume 解除暂停门禁；执行失败也可产生 ERROR 暂停。
人工 Stop 对活动 Run 在同一事务中先读取执行事实，再以 Run 稳定请求标识和线程版本调用 Harness Stop，
以返回的权威 head 收尾；运行时不可用时停止失败，不把活动 Run 当作空闲处理。
正常收尾为 CANCELLED 并保留 USER 暂停；停止前在途结果不明时收尾为 FAILED 并保留 ERROR 暂停。
空闲时再次 Stop 不覆盖 ERROR 及其详情，执行需显式恢复。直接换阶段与 Project 归档要求无
活动 Run；Project 归档后其下的写操作与执行都被拒绝，恢复归档后才能继续。

### 接受、交接与报告

Run 接受在同一物理事务内完成状态和额度检查、Thread 选择或创建、起点冻结、显式
Agent/Model/Environment 命令与任务输入提交、Run 与 RUN 活动插入、
Harness 命令接受及 Work 登记。首次创建先写 Harness Session、
ROOT 和 Thread，再写产品绑定与 Run，满足即时外键；失败时全部回滚。

进入有 Agent 的阶段登记 Work。Agent 正常结束且未交接时，Run 终结并保持当前阶段；
再次执行需要接受新 Run 并检查额度。额度耗尽时等待人工授权。

Agent 使用 `issue_transition`（参数 `to_state`）请求交接，目标先写入 Run.next_state，
工具返回“已接受，收尾后生效”。用户输入继续受控接受，处理完后才能交接。

```text
接受 next_state
  -> 处理剩余输入、问答与在途调用，到安全点
  -> root Join 冻结 terminalEntryId / finalAnswerEntryId
  -> 事务复验 Run ID/version、历史区间、合法边与门禁
  -> 冻结报告，Run=COMPLETED
  -> 更新 Issue.state，记录活动，发布证据，请求后继 Work
```

暂停时保留原 Run 的交接目标，恢复后继续收尾；失败或取消终结该 Run。旧 Worker、
旧 Run 和迟到 callback 通过执行身份与版本围栏拒绝。崩溃恢复继续原 Run，沿用原额度。

Run 区间为 `(start_entry_id,end_entry_id]`，沿 Entry 父链解释；最终回答必须是区间内
真实可见 Entry（取自 root Join 冻结的 `finalAnswerEntryId`），缺少报告时展示终态原因。
Issue 的要求由需求和阶段定义，Agent 交接走 `issue_transition`；Issue Run 的任务输入是
可信 `CUSTOM_MESSAGE`，其 owner 命令不出现在公共 batch 端点。
Issue 工具说明、交接回执与当前 Run 的任务消息使用本模块的
[`prompts`](../../apps/project/src/main/resources/fun/fengwk/kkstudio/project/prompts/) Markdown 资源，
由 Harness 的严格模板渲染器按三花括号填入未转义的可信 Markdown 事实。

执行记录落在 model 行：
[`IssueAgentThread`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/model/IssueAgentThread.java)
保存 `(issueId, agentName) -> threadId` 稳定绑定；
[`IssueRun`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/model/IssueRun.java)
保存 `(startEntryId, endEntryId]`、Session/Thread、阶段与终态事实。每 Issue 唯一活动 Run、
终态必须冻结区间、FAILED 必须给出原因等不变量由 `project_issue_run` 的检查约束与
部分唯一索引最终保证；Entry 父链、跨表外键与锁序校验由 service 与 Runtime 负责，领域层不重复实现。

Run 的预算、等待与停止政策只消费 Runtime 投影的统一执行事实 [`ThreadExecutionFacts`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/api/thread/ThreadExecutionFacts.java)：存在在途工作（排队命令 / 合法本地 Model / `READY`、`RUNNING` 工具 / owned continuation）时 `reconcile` 只延后重检，绝不投递指示或收尾；只有 `WAITING_APPROVAL` / `WAITING_INPUT` 才把 Run 转入等待；`UNKNOWN` 工具是已收敛的模型反馈。人工停止时仍有在途工作或结果未定则收敛为 FAILED 和 ERROR 暂停。Project 通过 `ThreadExecutionFacts` 统一判断「在途」。

Project 的 `yoloEnabled` 只在首次创建该 `Issue + Agent` 执行根时初始化其
YOLO 开关；修改项目开关不改写既有 Thread。该根委派的后代读取根的开关，
后续人工开关只更新根行。每次 Run 由 Project 显式提交 `SET_AGENT` / `SET_EXECUTION` /
`SET_ENVIRONMENT` 与末尾任务输入；阶段 Environment 就来自该 Run 的
显式命令，Platform 不按 workflow 反查解析。它作为普通配置命令随 Turn 冻结，不是对已存在
Thread `BranchSettings` 的带外重写。

Run 的业务上下文只有一条 Markdown 任务消息：接受时由 Project 自己的模板渲染 Issue 事实、阶段说明
与可交接目标，以 `CUSTOM_MESSAGE` 随输入回合交给模型。Project 不写任何 branch custom state，也不在
收尾时回写 Harness 状态，因此压缩可能摘要任务消息，但交接规则始终随工具说明每轮发送。
`issue_transition` 只依据真实调用 Thread 解析 Project 自己的稳定绑定与活动 Run，在业务锁内校验
Run/Issue/阶段/版本与调用 Thread 身份，并要求本次调用的 assistant 入口落在该 Thread 当前历史路径上，
其所属 TURN_START 由该 Thread 创建且严格晚于活动 Run 的 `startEntryId`：旧 Run 的迟到调用、同 Thread 后续 Run 期间的旧 Turn、兄弟分支与
fork 都不获权。Run 完成只认本次
root Join 冻结的 `terminalEntryId` / `finalAnswerEntryId`（`COMPLETED` / `ERROR` / `CANCELLED` 分别
完成 / 失败 / 取消），既不按快照最新 head 猜测，也不等待永久子树 idle；工具 UNKNOWN 作为模型反馈。

## 用例事务与持久化

[`ProjectService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/ProjectService.java)、
[`IssueEditingService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueEditingService.java)、
[`IssueLifecycleService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueLifecycleService.java)、
[`IssueStageBudgetService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueStageBudgetService.java)、
[`IssueRunService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueRunService.java)
与 [`IssueEvidenceService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueEvidenceService.java)
是写用例的事务边界，`IssueEditingService.appendActivity` 负责评论与指示的追加、锁序与幂等回执；
读侧由 [`ProjectSnapshotAssembler`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/ProjectSnapshotAssembler.java) 与
[`IssueDetailAssembler`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueDetailAssembler.java)
在模块内部完成快照用例聚合；
`IssueReconciler.reconcile` 在 `reconcile` 包内以单事务推进一个有界动作，最终答复证据的预校验和发布由
`IssueReconcileEvidence` 在同一事务内负责；
[`IssueMutationSupport`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueMutationSupport.java)
集中 Issue 写路径共用的 `Project KEY SHARE -> Issue UPDATE` 锁序、版本与 Project 归档门禁、活动落库；
`IssueActivityIdempotency` 统一锁前、锁内两次 receipt 判定；Run 的完成、取消与失败由同一事务入口收尾。
阶段额度仓库读取额度时按 Run 历史聚合已消耗次数；其余仓库负责实体读写与 CAS 行数判定。
写入或删除 Project、Issue、Run、Activity、阶段预算、公开证据与 Agent Thread 绑定时，仓库在同一事务内
用 [`ProjectChangeNotifications`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/notification/ProjectChangeNotifications.java)
定义的 `project_issue_changed` channel 发送 `projectId` 快照失效提示；PostgreSQL 提交后投递、回滚不投递，
同一事务内相同 payload 合并。删除 Issue 前读取其 Project id；通知不依赖 HTTP 入口，调度与 worker
写入也会覆盖。Work 租约/唤醒只影响内部调度，走共享的 `durable_work` 通道，不产生项目快照失效信号。
幂等事实落在 `project_issue_activity`（`idempotencyKey` 精确重放；`body` 的非空性按 `kind` 固定：
COMMENT/INSTRUCTION 必须非空白且不超过 1 MiB，RUN、SPEC_CHANGE、STATE_CHANGE、CONTROL 必须为空；定向 INSTRUCTION 只投递给明确的当前 Run，普通评论用于时间线沟通，要影响当前执行时提交定向指令；指令接受与处理游标推进同事务完成）；
Stage 预算与 Evidence 分别落在 `project_issue_stage_budget`、`project_issue_evidence`；
Work 邮箱是共享 `durable_work` 表中 target type 为 `ISSUE` 的行。

[`IssueEvidenceService`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/service/IssueEvidenceService.java)
管 Issue **自有的**公开证据：`project_issue_evidence` 一行是 Issue 持有的一个已发布 Blob 引用，
`(issueId, blobId)` 唯一，首次发布时 retain。最终报告中的合法受管资源在收尾时发布，也支持人工上传；
文本文件交付需先物化为 Blob；公开证据展示与 Agent Session 的读取授权分别校验，未授权资源访问拒绝；
证据与 Session 引用各自独立计数，不由 Session 生命周期决定。人工附件经 `lockReadyUpload -> retain ->
deleteUpload` 在调用方事务内完成引用转移，展示名取自上传行而不是客户端；证据行已存在时不重复
retain，重复交付不双计。深删除 Session 只释放该 Session 自己的引用。

Project 深删除在一个事务内锁住 Project 行（`FOR UPDATE`），拒绝仍活动的 Run，
再按 Evidence（先于 Run 行，因为 `project_issue_evidence.run_id` 是 RESTRICT 外键）->
Activities -> Work -> Runs -> Stage 预算 -> Agent Session 深删除 -> Issue -> Project 的顺序清理；
Issue 与 Project 行用 `version` CAS 删除并检查受影响行数。

## Reconcile 调度

[`reconcile`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile/package-info.java)
承载 Issue 的部署级配置、邮箱处理与确定性推进：Web 组合根的
[`IssueReconcileRuntimeConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/runtime/IssueReconcileRuntimeConfiguration.java)
装配 bounded handoff 与 poll 生命周期，Issue 专属的领取、执行与归还由
[`IssueReconcileWorkHandler`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile/IssueReconcileWorkHandler.java)
处理；合并 wake 的单 drain、容量计数、stop 收尾与 executor
拒绝/失败分型复用 [Durable Work](work.md) 的
[`BoundedWorkDispatcher`](../../work/src/main/java/fun/fengwk/kkstudio/work/BoundedWorkDispatcher.java)，
[`IssueReconciler`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile/IssueReconciler.java)
在固定锁序下一次推进一个有界动作。调度只依赖数据库中的 work 行与 lease token，通知与 poll
提供发现入口；租约与调度参数由 [`IssueReconcileProperties`](../../apps/project/src/main/java/fun/fengwk/kkstudio/project/reconcile/IssueReconcileProperties.java)（前缀 `kk-studio.project.reconcile`）管理。

Work 的请求、claim、续租、完成与重排由共享的
[`DurableWorkStore`](../../work/src/main/java/fun/fengwk/kkstudio/work/DurableWorkStore.java)
完成，Project 只持有 target type 为 `ISSUE` 的实例 `issueWorkQueue`：时间一律取数据库
`statement_timestamp()` 并截断到毫秒，调用方传相对 `Duration`，Dispatcher 不持有 Clock；请求合并较早 due
并递增 wake_version，完成与重排以 lease token 围栏，处理期间到达的新 wake 不会被吞掉。
工作者拒绝、reconciler 失败与 stop 后未 handoff 的 claim，都由 handler 在短事务中先
`lockClaimed` 确认仍持有有效租约，再按对应延迟 `reschedule`；失权则不归还。
Harness 的调度时间域见 [Platform](platform.md#work-的-claim--lease--wake)。

## HTTP 接口

Project 与 Issue 的公开接口都在 `/api/project` 命名空间下：项目是
`/api/project/projects`，Issue 是 `/api/project/issues`，项目下创建 Issue 用
`/api/project/projects/{projectId}/issues`。列表直接返回数组，创建返回 201，删除返回 204；
`PUT` 整体替换可编辑属性并携带 `expectedVersion`，状态转换与 Project 归档用 `POST /{id}/<动作>`。
Issue 没有归档状态，删除是唯一的移除方式；Project 归档保留，默认列表只返回活跃项目，
`includeArchived=true` 同时返回活跃与已归档项目。
HTTP DTO 的工作流是结构化对象，数值坐标（包括 workflow 的 `maxRuns`、版本与序号）中
64 位整数使用规范十进制字符串；上例展示的是存储 codec 的形态，不是请求体。
时间字段是 epoch 毫秒整数，固定取值（暂停原因、Activity 种类与操作者、Run 状态）使用领域枚举。
完整路由表以生成的 `ApiRoutes` 为准。

## 错误映射

Project 与 Issue 使用 common 的
[`DomainException`](../../common/src/main/java/fun/fengwk/kkstudio/common/error/DomainException.java)
在客户端输入错误产生处表达校验、缺失与并发冲突；Web 统一将它们翻译为 problem 响应，具体字段与状态见
[HTTP 契约](../system-design.md#http-契约)。Agent 工具边界同样使用领域错误类别。

## 测试与验证

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -pl apps/project -am verify
```

`verify` 对 domain 包做 ≥90% 行覆盖检查，并运行确定性调度与 owner 归属单测。事务、锁序与平台适配的端到端语义由同模块的
Project/Issue 集成测试（如 [`IssueRunAcceptanceIntegrationTest.java`](../../apps/project/src/test/java/fun/fengwk/kkstudio/project/IssueRunAcceptanceIntegrationTest.java)）以真实 PostgreSQL 加平台测试宿主覆盖；平台通过
`platform-runtime` 的 test-jar 提供 Spring/PostgreSQL 测试基座。
