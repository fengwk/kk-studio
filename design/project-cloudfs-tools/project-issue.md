# Project 与 Issue

## 1. 职责

Project 是目标、约束和 Issue 集合。Project Coordinator 负责决定做什么：

- 阅读 Project description 和 CFS 中的材料；
- 创建、修改、排序和依赖化 Issue；
- 为 Issue 指定 Executor 与 Reviewer；
- 在失败、未知或人类介入后调整计划。

Issue Controller 是确定性 reconciler，负责保证怎么执行：

- 计算 dependency blocked；
- 为可运行 Issue 创建 durable IssueRun 和 Harness Session；
- 观察 Harness quiescence 并发送 continuation；
- 校验 submit/review 围栏并转换 Issue；
- 处理等待人类、取消、deadline 和进程崩溃；
- 通过数据库 claim/lease/fencing 支持多 App 节点。

Harness 继续负责 Agent Loop。Project/Issue 不直接调用 Provider、不执行 Tool、不复制 Harness Work 或审批状态机。

## 2. Project

### 2.1 字段

| 字段 | 语义 |
| --- | --- |
| `id` | UUID 稳定身份。 |
| `title` | 展示标题，不充当路径或外部标识。 |
| `description` | 自洽目标、约束、验收方式及相关 CFS 路径。 |
| `coordinator_agent_name` | 必填，引用一个现有 `AgentDefinition`。 |
| `next_issue_number` | Project 内单调 Issue 编号分配器。 |
| `version` | Project 字段 CAS，不随子 Issue 更新。 |
| `archived_at` | 可空；归档后不接受新的自动运行。 |
| `created_at` / `updated_at` | 时间事实。 |

Project 不保存 status、priority、进度百分比、Environment、workdir 或 CFS node id。进度由 Issue 状态实时投影。

### 2.2 Coordinator Session

每个 Project 最多关联一个长期存在的 Coordinator Harness Session：

```text
project
  `-- project_session(project_id PK, session_id UNIQUE)
        `-- ordinary Harness Session / ROOT Thread
```

- Session 在第一次向 Coordinator 发消息时惰性创建。
- 创建 Session、初始命令和 `project_session` 关系使用一个数据库事务。
- Coordinator 使用 Project 当前配置的 Agent；修改 Coordinator 后，下一条 Project 命令显式写入 `SET_AGENT`，不重写历史。
- Coordinator 可以正常静默结束一个 turn；它没有 submit terminal gate，也不因静默自动结束 Project。
- Issue 进入 `FAILED`、`UNKNOWN` 或 `WAITING_HUMAN` 时，Controller 可以用基于 `runId + stateVersion` 的 idempotency key 向已存在的 Coordinator Session 追加一条注意事项。普通 `DONE` 不自动制造 Coordinator 对话。

## 3. Issue

### 3.1 字段

| 字段 | 语义 |
| --- | --- |
| `id` | UUID 稳定身份。 |
| `project_id` / `number` | Project 归属和 Project 内单调编号，组合唯一。 |
| `title` / `description` | 执行 spec；验收要求直接写在 description 中。 |
| `status` | 六态生命周期。 |
| `assignee_agent_name` | 可空；为空时不自动执行。 |
| `reviewer_agent_name` | 可空；为空表示进入人工 Review。 |
| `version` | 所有 Issue 行修改的 UI/API CAS。 |
| `spec_revision` | 仅执行相关 spec、分配和依赖改变时递增。 |
| `input_sequence` | 每次人类输入或 Review feedback 递增。 |
| `archived_at` | 可空；只允许终态 Issue 归档。 |
| `created_at` / `updated_at` | 时间事实。 |

首版不增加 priority、label、parent、stage、自定义字段、日期和自定义状态。

### 3.2 状态

```text
BACKLOG
   |
   v
 TODO --[deps done + assignee]--> IN_PROGRESS
                                      |
                               issue_submit
                                      |
                                      v
                                  IN_REVIEW
                                  /         \
                     request changes       approve
                              |               |
                              v               v
                            TODO             DONE

任意非终态 --cancel--> CANCELED
DONE/CANCELED --explicit reopen--> TODO
```

规则：

- `BACKLOG -> TODO` 表示已准备进入自动执行候选。
- 只有 Controller 能执行 `TODO -> IN_PROGRESS`。
- 只有当前 Executor Run 成功 `issue_submit` 才能执行 `IN_PROGRESS -> IN_REVIEW`。
- 只有当前 Reviewer Run 或人工 Review 才能执行 `IN_REVIEW -> DONE|TODO`。
- request changes 写入一条 Review feedback，并递增 `input_sequence`。
- FAILED/UNKNOWN Run 不改变 Issue status；明确 retry 追加 `RETRY` 输入并创建新
  ordinal，绝不复活旧 Run。
- terminal Issue 的 reopen 是明确的人类/Coordinator 操作，进入 `TODO` 并递增
  `spec_revision`。
- `archived_at` 不参与状态机。归档后的 Issue 不运行，取消归档也不隐式改变 status。

### 3.3 Dependency 与 blocked

`issue_dependency(issue_id, depends_on_issue_id, project_id)` 表示：

```text
issue_id 只有在 depends_on_issue_id == DONE 时才解除这一条阻塞
```

- 两端必须属于同一 Project。
- 拒绝 self dependency 和有向环。
- 使用复合外键约束同 Project；应用在固定锁序下使用 recursive CTE 检查环。
- 添加或移除依赖会递增被阻塞 Issue 的 `spec_revision` 并请求 Controller Work。
- 依赖处于 `CANCELED` 不算满足；Coordinator 或人类必须删除依赖、取消下游 Issue 或重新规划。
- blocked 是查询投影：存在非 `DONE` dependency 即为 true；数据库不保存 `BLOCKED` status 或布尔列。
- 依赖只允许在目标 Issue 为 `BACKLOG` 或 `TODO` 时修改，避免运行中改变执行前提。

## 4. Issue 输入

`issue_input` 是给当前或下一次 Run 的 append-only 输入流：

| 字段 | 语义 |
| --- | --- |
| `issue_id` / `sequence` | Issue 内单调坐标，组合主键。 |
| `kind` | `HUMAN`、`REVIEW_FEEDBACK`、`RETRY` 或 `SYSTEM`。 |
| `body` | 非空纯文本。 |
| `idempotency_key` | 同 Issue 唯一，保护 REST 重试。 |
| `created_at` | 接受时间。 |

追加输入和递增 `issue.input_sequence` 在同一事务。输入不改写 Issue description，不复制到 CFS，也不自动改变 Issue status。

## 5. IssueRun

### 5.1 字段

| 字段 | 语义 |
| --- | --- |
| `id` | UUID。 |
| `issue_id` / `ordinal` | Issue 内单调运行编号，组合唯一。 |
| `role` | `EXECUTOR` 或 `REVIEWER`。 |
| `actor_type` | `AGENT` 或 `HUMAN`；Executor 必须是 AGENT。 |
| `agent_name` | AGENT Run 创建时冻结的 AgentDefinition 名称；HUMAN 为空。 |
| `submission_run_id` | REVIEWER 必填，指向本次 Review 的 SUBMITTED Executor Run。 |
| `status` | `RUNNING`、`WAITING_HUMAN`、`COMPLETED`、`FAILED`、`CANCELLED`、`UNKNOWN`。 |
| `outcome` | terminal 时可为 `SUBMITTED`、`APPROVED`、`CHANGES_REQUESTED`。 |
| `observed_spec_revision` | 最近一次已投递给 Agent 的 spec revision。 |
| `observed_input_sequence` | 最近一次已投递给 Agent 的输入游标。 |
| `continuation_count` / `max_continuations` | 防止无 terminal tool 的无限自动 turn。 |
| `deadline` | 整个 Run 的绝对截止时间，不因 continuation 重置。 |
| `waiting_reason` | 等待人类或失败的稳定说明。 |
| `result` | 有界 JSON，保存 submit/review 摘要和验证说明。 |
| `terminal_action_id` | 可空且唯一；`tool:{invocationId}` 或 `command:{idempotencyKey}`。 |
| `version` | Run CAS。 |
| 时间字段 | `created_at`、`updated_at`、`completed_at`。 |

Agent Run 与 Harness Session 使用独立关系表；立即完成的 HUMAN Reviewer Run
没有 Session：

```text
issue_run_session(run_id PK, session_id UNIQUE)
```

这让 Harness Session 继续只有一个产品 owner，并复用现有 Session 删除与 Blob 引用清理模式。

### 5.2 不复制 Harness 运行事实

IssueRun 不保存 Model/Tool attempt、approval、Environment route、Provider token、Harness head 或第二套 heartbeat。实际 Agent turn 的 claim、lease、heartbeat、retry、cancel 和 UNKNOWN 都由 Harness 表负责。

Issue Controller 只通过 `issue_controller_work` 的短租约执行 bounded reconcile。它读取权威 Harness Snapshot，再更新 IssueRun。这样避免一个 Agent turn 同时被 Harness lease 和 IssueRun lease 双重拥有。

## 6. Issue Controller

### 6.1 Durable Work

`issue_controller_work` 每个 Issue 最多一行：

| 字段 | 语义 |
| --- | --- |
| `issue_id` | 主键。 |
| `wake_version` | 每次 request 递增，防止 drain 时丢 wake。 |
| `due_at` | 下次可 claim 时间。 |
| `lease_token` / `lease_until` | 多节点 claim 和 fencing。 |
| `updated_at` | 诊断。 |

任何影响运行的 Issue、dependency、input、Run 或 Harness quiescence 变化都在提交事务中 `requestWork` 并发出 `NOTIFY`。Controller 使用 `FOR UPDATE SKIP LOCKED` claim 和周期扫描；通知丢失只增加延迟。

一次 claim 只执行一个 bounded action：

1. 锁定 Issue、当前 Run 和必要 dependency。
2. 读取关联 Harness Thread 的权威状态。
3. 计算一个确定性 transition。
4. 提交 transition，必要时再次 request work。
5. 事务外不持有 Project/Issue 行锁等待模型、Tool 或网络。

### 6.2 Reconcile 顺序

```text
archived/canceled
  -> cancel active Harness run best-effort，收敛 Run

TODO + unblocked + assignee
  -> 原子创建 Executor IssueRun + Harness Session
  -> Issue = IN_PROGRESS

IN_PROGRESS + active Executor
  -> terminal outcome 已记录：等待/推进状态
  -> WAITING_HUMAN：静默
  -> Harness active：静默
  -> Harness quiescent 且仍有预算：追加 continuation
  -> deadline/预算耗尽：Run FAILED，Issue 保持 IN_PROGRESS 等人处理
  -> Harness UNKNOWN：Run UNKNOWN，Issue 保持 IN_PROGRESS

IN_REVIEW + reviewer
  -> 当前 submission 尚无 Reviewer Run 时创建 Reviewer Run
  -> 最新 Reviewer FAILED/UNKNOWN 时等待明确 RETRY
  -> 之后使用相同 quiescence 规则

IN_REVIEW + reviewer 为空
  -> 等待人工 Review

IN_PROGRESS/IN_REVIEW + 最新 Run FAILED/UNKNOWN + 新 RETRY 输入
  -> 创建同角色的新 ordinal 与 Harness Session
```

依赖已满足的不同 Issue 可以同时被不同 Controller Worker claim 并启动，不设置 Project 级串行锁或并发槽。

### 6.3 Harness quiescence

quiescence 表示当前 Thread 没有待处理 Command、Model/Tool Invocation 或 Work，不表示 IssueRun 成功。

若 Run 仍为 `RUNNING`：

- 有未投递的新 `issue_input`：发送包含增量输入和最新游标的 continuation。
- 没有新输入但 Agent 未调用 terminal tool：发送简短 continuation，要求继续并最终调用角色对应工具。
- 达到 `max_continuations` 或 `deadline`：Run 进入 `FAILED`，不自动新建另一 Run。
- Harness 出现未知副作用：Run 进入 `UNKNOWN`，不自动继续或重放。

Continuation 使用 `runId + continuation_count + observed cursors` 构造 idempotency key；同一 continuation 最多接受一次。

## 7. Agent 角色与上下文

### 7.1 角色来源

Project/Issue 只引用普通 `AgentDefinition`：

- Agent 的 Model、Variant、Environment、Skill、MCP 和 selectable tools 仍由 Catalog 配置。
- 进入 Project/Issue Session 时，Platform 根据 Session owner 投影角色上下文和角色 Tool。
- 不创建 CoordinatorAgent、IssueAgent 或 ReviewerAgent 子类型。

### 7.2 角色 Prompt

Coordinator 上下文包含：

- Project id、title、description 和 coordinator identity；
- 当前 Issue 列表、status、blocked、assignee、reviewer 和 revision；
- 只允许通过结构化 Issue Tool 改变 Project/Issue 事实。

Executor 上下文包含：

- Issue spec、已满足的 dependency、全部未消费输入；
- `spec_revision`、`input_sequence`、Run deadline；
- 必须以 `issue_submit` 或 `issue_request_input` 收敛，普通文本结束不算完成。

Reviewer 上下文包含：

- Issue spec、Executor submission、验证证据和 Review feedback 历史；
- 同样的 revision/input fence；
- 必须调用 `issue_review`，不能以普通文本批准。

Project description 和 Issue description 可以写 CFS 虚拟绝对路径。系统不解析文本并建立 FK；Agent 使用 `cloud_read` 等工具访问。

## 8. 角色 Tool

模型可见名称使用下划线，稳定 `AgentToolId` 使用点号命名空间。

### 8.1 Coordinator

| 模型名称 | 关键参数 | 作用 |
| --- | --- | --- |
| `project_read` | 无 | 读取当前 Project 与 Issue Snapshot。 |
| `issue_read` | `issue_id` | 读取一个 Issue、依赖、输入和 Runs。 |
| `issue_list` | 可选 status/archived filter | 列举当前 Project Issues。 |
| `issue_create` | title、description、assignee、reviewer、initial status | 创建 Issue，返回 id/number/version/spec revision。 |
| `issue_update` | issue_id、expected_version、可变字段 | CAS 修改非运行中 Issue spec/分配。 |
| `issue_add_dependency` | issue_id、depends_on_issue_id、expected_version | 增加依赖并检查环。 |
| `issue_remove_dependency` | 同上 | 删除依赖。 |
| `issue_set_status` | issue_id、status、expected_version | 只允许 Coordinator 可执行的显式 transition。 |
| `issue_cancel` | issue_id、expected_version、reason | 取消 Issue 并唤醒 Controller。 |

约束：

- `issue_create.initial_status` 只有 `BACKLOG` 或 `TODO`。
- `issue_update` 只允许 `BACKLOG`/`TODO`。
- `issue_set_status` 只允许 `BACKLOG <-> TODO` 以及 `DONE|CANCELED ->
  TODO` 的明确 reopen；不能绕过 Controller、submit 或 review 写入
  `IN_PROGRESS`、`IN_REVIEW`、`DONE`。

### 8.2 Executor

`issue_submit`：

```json
{
  "observed_spec_revision": 4,
  "observed_input_sequence": 2,
  "summary": "Implemented ...",
  "verification": "Tests ..."
}
```

接受条件：

- 调用 Thread 精确属于当前 `EXECUTOR` Run；
- Run 为 `RUNNING`，Issue 为 `IN_PROGRESS`；
- 两个 observed cursor 等于 Issue 当前值；
- terminal action 尚未记录；
- dependency 仍全部 `DONE`。

成功后 Run=`COMPLETED/SUBMITTED`，Issue=`IN_REVIEW`，并请求 Controller
Work。后续 Reviewer Run 的 `submission_run_id` 固定指向本次 Executor Run。

`issue_request_input`：

```json
{
  "observed_spec_revision": 4,
  "observed_input_sequence": 2,
  "question": "需要选择 A 或 B",
  "context": "两者的可观察差异 ..."
}
```

成功后 Run=`WAITING_HUMAN`，Issue 仍为 `IN_PROGRESS`。人类追加输入后，同一 Run 恢复并收到增量 continuation。

### 8.3 Reviewer

`issue_review`：

```json
{
  "observed_spec_revision": 4,
  "observed_input_sequence": 2,
  "decision": "APPROVE",
  "summary": "Verified ...",
  "verification": "..."
}
```

`decision` 只有 `APPROVE` 和 `REQUEST_CHANGES`：

- APPROVE：Run=`COMPLETED/APPROVED`，Issue=`DONE`。
- REQUEST_CHANGES：Run=`COMPLETED/CHANGES_REQUESTED`，写入 Review feedback，Issue=`TODO`。

所有角色 Tool 通过 `ToolExecutionContext.threadId` 反查 Project/Run，不让模型提供 projectId、issueId 或 runId 来冒充执行上下文。Observed cursors 必须由模型显式回传，不能由服务端静默取最新值。

人工 Review 使用同一 transition service，并在事务内创建
`actor_type=HUMAN`、无 Harness Session、直接 COMPLETED 的 REVIEWER Run；因此批准和
返工仍有统一、可审计的 durable fact。

## 9. 人类操作

人类与 Agent 使用相同 application service 和状态规则：

- 编辑 Project/Issue：`expectedVersion` CAS。
- 向 Issue 追加输入：idempotency key；WAITING_HUMAN Run 恢复。
- 人工 Review：仅 `reviewer_agent_name == null` 且 Issue=`IN_REVIEW` 时允许 APPROVE/REQUEST_CHANGES。
- Cancel：Issue 进入 `CANCELED`；best-effort stop Harness，不能宣称远端副作用回滚。
- Retry：对 FAILED/UNKNOWN Run 追加幂等 `RETRY` 输入，Controller
  生成同角色的新 ordinal 和 Session；不复活旧 Run。
- Reassign：只允许 `BACKLOG`/`TODO`；活动 Run 必须先取消 Issue 或等待终态。
- Archive：只允许 `DONE`/`CANCELED`，只是列表过滤。

Tool approval 是 Harness Tool 级决策，与 Issue 人工 Review、WAITING_HUMAN 和 cancel 是三种不同事实，不能复用一个 status。

## 10. REST 与 Snapshot

### 10.1 Project

```text
GET    /api/projects
POST   /api/projects
GET    /api/projects/{projectId}
PUT    /api/projects/{projectId}
DELETE /api/projects/{projectId}              # 无 active/unknown Run 时编排删除
POST   /api/projects/{projectId}/archive
POST   /api/projects/{projectId}/unarchive
POST   /api/projects/{projectId}/commands      # 给 Coordinator Session 发消息
GET    /api/projects/{projectId}/snapshot
```

### 10.2 Issue

```text
POST   /api/projects/{projectId}/issues
GET    /api/issues/{issueId}
PUT    /api/issues/{issueId}
POST   /api/issues/{issueId}/status
POST   /api/issues/{issueId}/dependencies
DELETE /api/issues/{issueId}/dependencies/{dependsOnIssueId}
POST   /api/issues/{issueId}/inputs
POST   /api/issues/{issueId}/review
POST   /api/issues/{issueId}/cancel
POST   /api/issues/{issueId}/retry
POST   /api/issues/{issueId}/archive
POST   /api/issues/{issueId}/unarchive
```

Command 型 POST 携带 `idempotencyKey`；修改型请求携带 `expectedVersion`。DTO 使用 canonical UUID 和 decimal string 表示 Java long。

Project Snapshot 是页面权威读取面，包含：

- Project；
- 未归档 Issue；
- dependency edges；
- 每个 Issue 的 blocked 投影和当前/最近 Run 摘要；
- Coordinator Session id 和必要 Thread projection。

PostgreSQL `NOTIFY project_issue_changed` 和浏览器事件只发送 `projectId` 作为 refetch 提示，不承担事件日志。首次进入、重连、通知丢失或 payload 异常都重新读取 Snapshot。

## 11. UI

### 11.1 Projects

- Projects 页面使用现有 CreateCard/Card/Dialog 风格。
- Project 卡片只显示 title、Coordinator、Issue 进度和更新时间。
- 创建/编辑表单只有 title、description、Coordinator Agent。
- 默认隐藏 archived Project。

### 11.2 Project Detail

```text
+---------------- Project header ----------------+
| description / coordinator / archive            |
+----------------------+--------------------------+
| Issue board/list     | Coordinator conversation |
| backlog ... done     | ordinary Harness thread  |
+----------------------+--------------------------+
```

- 六列状态固定，不提供自定义 workflow。
- blocked、WAITING_HUMAN、FAILED、UNKNOWN 使用 badge，不增加列。
- 同一 Issue 只显示一个当前 Run；历史 Runs 在详情中列出。
- Issue detail 提供 spec、依赖、Agent/Reviewer、输入、submission/review 和关联 Session。
- CAS 冲突保留用户草稿并要求重新加载，不覆盖更新版本。

## 12. 数据与删除

目标表：

```text
project
project_session
issue
issue_dependency
issue_input
issue_run
issue_run_session
issue_controller_work
```

删除顺序由 application service 显式完成：

```text
controller work
-> run-session relations
-> run Harness Sessions（复用 SessionDeletionOrchestrator）
-> issue runs / inputs / dependencies / issues
-> coordinator session relation + Session
-> project
```

FK 默认 `ON DELETE RESTRICT`；不使用级联掩盖 Blob release、Harness Session 清理或活动 Run 检查。删除有活动/不确定 Run 的 Project 明确拒绝，先 cancel 并完成收敛。

## 13. 故障语义

| 场景 | 结果 |
| --- | --- |
| Agent/Model/Tool 明确失败，Harness 已收敛 | Controller 可 continuation；预算耗尽后 Run FAILED。 |
| Tool 副作用是否发生未知 | Harness 与 IssueRun 均 UNKNOWN；不重放、不自动新建 Run。 |
| App 在创建 Run 事务前退出 | 无 Run 事实；Work lease 过期后重新 reconcile。 |
| App 在原子创建 Run+Session 后退出 | Run 和 Harness Work 都存在；其他节点继续。 |
| NOTIFY 丢失 | 周期扫描发现 due Controller Work。 |
| Agent 普通文本声称完成 | Run 保持 RUNNING；quiescent 后 continuation。 |
| submit/review cursor 过期 | Tool 明确冲突失败；Controller 投递新 spec/input。 |
| 人类 cancel 时远端 Tool 在途 | Issue=CANCELED；Harness best-effort cancel，未知调用保持 UNKNOWN。 |
| Coordinator Session 不存在 | 普通 Issue 执行不受影响；需要通知时不自动创建对话。 |

## 14. 验收

- 状态图中每条合法和非法 transition 有单元测试。
- PostgreSQL 集成测试覆盖 Project issue number 分配、CAS、同 Project dependency、环检测、Work claim/lease/fence 和并发 submit/review。
- 两个 ready Issue 可并发创建 Run；一个 blocked Issue 不启动。
- quiescent 文本回复不会完成 Run，且 continuation idempotent。
- submit/review 在 spec 或 input cursor 变化后必须拒绝。
- WAITING_HUMAN 追加输入后恢复同一 Run；FAILED/UNKNOWN 只能明确 retry 为新 Run。
- Tool context 不能跨 Thread/Run 提交另一个 Issue。
- Session 删除、Project 删除和 Blob 引用释放顺序经过真实 PostgreSQL 测试。
- 前端 Snapshot 重连、CAS 草稿保留、blocked/attention badge 和人工 Review 有自动化测试。
