# Project 模块

Project 是业务状态的事实源；Canvas 组织资源，不决定 Issue 的执行顺序。项目把工作阶段定义在一份
`workflow` JSON 中，Issue 保存当前阶段，Run 记录一次已接受执行的历史坐标；Agent 身份由
`Issue + Agent` 唯一绑定到一条持久 Thread，同一阶段的预算按 `Issue + state` 计数，
不会因为切换 Agent 重置。先从[系统设计](../system-design.md)理解模块边界；
完整的事务边界和表关系见[Canvas / Project](../canvas-project.md)。

[`project/`](../../project/pom.xml) 集中提供领域规则、Issue 用例、
PostgreSQL 持久化与 REST 调度，经
[`ProjectAutoConfiguration`](../../project/src/main/java/fun/fengwk/kkstudio/project/ProjectAutoConfiguration.java)
装配进宿主应用。HTTP DTO 映射由 Web 提供，Environment 校验、Session 深删除与 Blob 操作
通过下文端口调用 [Platform](platform.md) 的适配。生产依赖包括
`harness-runtime` 的协议类型（`BranchSettings`、`AcceptedCommands`）与 Spring Boot、MyBatis、Jackson 通用框架，
[`ProjectArchitectureTest`](../../project/src/test/java/fun/fengwk/kkstudio/project/ProjectArchitectureTest.java)
同时锁定「生产依赖集合精确相等」与 import 白名单。

## 模块结构

| 包 | 内容 |
| --- | --- |
| [domain](../../project/src/main/java/fun/fengwk/kkstudio/project/domain) | workflow 配置、阶段流转与阶段预算授权规则 |
| [model](../../project/src/main/java/fun/fengwk/kkstudio/project/model) | Project、Issue、Run、Activity、Evidence、Work、Agent Thread 绑定、阶段预算行与暂停原因等值 |
| [repo](../../project/src/main/java/fun/fengwk/kkstudio/project/repo) | 仓库契约与 PostgreSQL 实现（`repo/impl` 下另有 mapper 与 DO） |
| [service](../../project/src/main/java/fun/fengwk/kkstudio/project/service) | 用例级事务边界：Project/Issue/Run/Evidence 与 Work 邮箱 |
| [controller](../../project/src/main/java/fun/fengwk/kkstudio/project/controller) | Issue 调度的部署级配置与确定性推进 |
| [port](../../project/src/main/java/fun/fengwk/kkstudio/project/port) | 宿主必须实现的跨宿主能力 |
| [error](../../project/src/main/java/fun/fengwk/kkstudio/project/error) | 模块自有错误模型与稳定的 Web 映射 |

## 跨宿主端口

[`port`](../../project/src/main/java/fun/fengwk/kkstudio/project/port) 是模块唯一的对外能力面；
[`ProjectArchitectureTest`](../../project/src/test/java/fun/fengwk/kkstudio/project/ProjectArchitectureTest.java)
校验主源码与 POM 的依赖方向：Project 使用协议类型和通用框架，宿主实现端口。

| 端口 | 宿主必须提供的语义 | 事务要求 |
| --- | --- | --- |
| [`EvidenceBlobPort`](../../project/src/main/java/fun/fengwk/kkstudio/project/port/EvidenceBlobPort.java) | 锁定并消费一次已 READY 上传、对 Blob 引用做 retain/release、判定 Blob 是否仍可引用 | 必须加入调用方已有事务；失败不得吞成「不可用」 |
| [`HarnessCommandAcceptancePort`](../../project/src/main/java/fun/fengwk/kkstudio/project/port/HarnessCommandAcceptancePort.java) | 以 `Issue + Agent` owner 接受 Harness 命令并返回 root entry | 与调用方写入同一事务 |
| [`AgentBranchSettingsPort`](../../project/src/main/java/fun/fengwk/kkstudio/project/port/AgentBranchSettingsPort.java) | 按宿主 Agent/Model catalog 物化分支设置 | 只读 |
| [`DelegatedWorkActivityPort`](../../project/src/main/java/fun/fengwk/kkstudio/project/port/DelegatedWorkActivityPort.java) | 查询 Thread 委派子树是否仍有未交付工作：宿主按 Thread 持久 `status` 非 `IDLE` 判定，为真时禁止 Run 提前收尾 | 加入协调器已有事务读取 |
| [`IssueAgentSessionDeletionPort`](../../project/src/main/java/fun/fengwk/kkstudio/project/port/IssueAgentSessionDeletionPort.java) | 深删除该 `Issue + Agent` 名下的 Harness Session 与 Blob 引用 | 在调用方锁序内，逐 owner 调用 |

端口用领域类型声明语义。宿主适配器将平台异常译为 Project 错误，例如上传不存在为
not found，未 READY/已 cleanup 为 validation。

## 工作流配置

[`ProjectWorkflowJsonCodec`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/ProjectWorkflowJsonCodec.java)
严格解析 `{"states":[...]}`：拒绝重复字段、未知字段、尾随内容和非法类型；编码为确定性 JSON，
可用于请求指纹。每个 state 使用项目内唯一的大写自然编码（`[A-Z][A-Z0-9_]{0,63}`），
阶段数量由项目配置决定。默认流程为 `INIT -> WORK -> DONE`，另含 `BLOCKED`；默认 WORK 是人工阶段。
`INIT`、`BLOCKED`、`DONE` 必须存在；正常边只指向已声明阶段，不通往 `BLOCKED`。
启用工作阶段须从 `INIT` 可达，且存在到 `DONE` 的正常路径。

[`IssueStateTransitions`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueStateTransitions.java)
区分正常转移、业务阻塞与恢复，以及从 DONE 显式重开。问卷等待与人工暂停分别由 Runtime 等待事实和 Issue 暂停字段表达。

## 预算与运行记录

[`IssueStageBudget`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueStageBudget.java)
承载授权事实：只面向 workflow 中启用且有 Agent 的工作阶段，`maxRuns` 为正，重置高水位只能推进到
`nextRunOrdinal - 1`，不能回退而重新授权历史 Run。消耗由持久化按本 Issue、本阶段、序号超过高水位的全部
Run 计数，失败、取消和 UNKNOWN 也消耗一次；额度只限制**新 Run**，不限制已接受 Run 的恢复。

执行记录落在 model 行：
[`IssueAgentThread`](../../project/src/main/java/fun/fengwk/kkstudio/project/model/IssueAgentThread.java)
保存 `(issueId, agentName) -> threadId` 稳定绑定；
[`IssueRun`](../../project/src/main/java/fun/fengwk/kkstudio/project/model/IssueRun.java)
保存 `(startEntryId, endEntryId]`、Session/Thread、阶段与终态事实。每 Issue 唯一活动 Run、
终态必须冻结区间、FAILED/UNKNOWN 必须给出原因等不变量由 `project_issue_run` 的检查约束与
部分唯一索引最终保证；Entry 父链、跨表外键与锁序校验由 service 与 Runtime 负责，领域层不重复实现。

Project 的 `yoloEnabled` 只在首次创建该 `Issue + Agent` Thread 时随 `NEW_SESSION` 写入；
修改项目开关不改写既有 Thread。阶段 Environment 则在每个 live turn 由 Platform 从当前 workflow
解析，未配置时本 turn 不选择环境；它不是重写 Thread `BranchSettings` 的命令。两者的作用域不同。

## 用例事务与持久化

[`ProjectServiceImpl`](../../project/src/main/java/fun/fengwk/kkstudio/project/service/impl/ProjectServiceImpl.java)、
[`IssueServiceImpl`](../../project/src/main/java/fun/fengwk/kkstudio/project/service/impl/IssueServiceImpl.java)、
[`IssueRunServiceImpl`](../../project/src/main/java/fun/fengwk/kkstudio/project/service/impl/IssueRunServiceImpl.java)、
[`IssueEvidenceServiceImpl`](../../project/src/main/java/fun/fengwk/kkstudio/project/service/impl/IssueEvidenceServiceImpl.java) 与
[`IssueWorkStoreImpl`](../../project/src/main/java/fun/fengwk/kkstudio/project/service/impl/IssueWorkStoreImpl.java)
是写用例的事务边界，`IssueReconciler.reconcile` 在 controller 内以单事务推进一个有界动作；
`repo/impl` 的 PostgreSQL 实现只做单表读写与 CAS 行数判定。
写入或删除 Project、Issue、Run、Activity、阶段预算、公开证据与 Agent Thread 绑定时，仓库在同一事务内
用 `pg_notify('project_issue_changed', projectId)` 发送快照失效提示；PostgreSQL 提交后投递、回滚不投递，
同一事务内相同 payload 合并。删除 Issue 前读取其 Project id；通知不依赖 HTTP 入口，调度与 worker
写入也会覆盖。Work 租约/唤醒只影响内部调度，走独立通道 `project_issue_work_due`，不产生项目快照失效信号。
幂等事实落在 `project_issue_activity`（`idempotencyKey` 精确重放；`body` 的非空性按 `kind` 固定：
COMMENT/INSTRUCTION 必须非空白且不超过 1 MiB，RUN、SPEC_CHANGE、STATE_CHANGE、CONTROL 必须为空）；
Stage 预算、Work 邮箱与 Evidence 分别落在 `project_issue_stage_budget`、
`project_issue_work`、`project_issue_evidence`。

[`IssueEvidenceServiceImpl`](../../project/src/main/java/fun/fengwk/kkstudio/project/service/impl/IssueEvidenceServiceImpl.java)
管 Issue **自有的**公开证据：`project_issue_evidence` 一行是 Issue 持有的一个已发布 Blob 引用，
与 Session 引用各自独立计数，不由 Session 生命周期决定。人工附件经 `lockReadyUpload -> retain ->
deleteUpload` 在调用方事务内完成引用转移，展示名取自上传行而不是客户端；证据行已存在时不再
retain，重复交付不双计。深删除 Session 只释放该 Session 自己的引用。

Project 深删除在一个事务内锁住 Project 行（`FOR UPDATE`），拒绝仍活动的 Run 与未解除的 `UNKNOWN` 暂停，
再按 Evidence（先于 Run 行，因为 `project_issue_evidence.run_id` 是 RESTRICT 外键）->
Activities -> Work -> Runs -> Stage 预算 -> Agent Session 深删除 -> Issue -> Project 的顺序清理；
Issue 与 Project 行用 `version` CAS 删除并检查受影响行数。

## REST 调度

[`controller`](../../project/src/main/java/fun/fengwk/kkstudio/project/controller/package-info.java)
承载 Issue 的部署级配置与确定性推进：`IssueControllerDispatcher` 只做 `project_issue_work` 的
claim、bounded handoff 与 poll 生命周期，
[`IssueReconciler`](../../project/src/main/java/fun/fengwk/kkstudio/project/controller/IssueReconciler.java)
在固定锁序下一次推进一个有界动作。调度只依赖数据库中的 work 行与 lease token，通知与 poll
提供发现入口；租约参数见 [Platform](platform.md) 的配置表。

Work 请求、claim、续租、完成与重排统一以数据库 `statement_timestamp()` 为权威时间，
截断到毫秒；调用方传相对 `Duration`，Dispatcher 不持有 Clock。
请求在冲突时用 SQL least 保留较早 due_at 并增加 wake_version。
重排同时校验 lease token 与有效租约、清空租约：wake_version 未变时采用数据库当前时间
加延迟，不保留本次 claim 的旧 past-due；有新 wake 时才取现有 due_at 与目标时刻的较早值。
实现见 [`IssueControllerDispatcher`](../../project/src/main/java/fun/fengwk/kkstudio/project/controller/IssueControllerDispatcher.java)、
[`IssueWorkStoreImpl`](../../project/src/main/java/fun/fengwk/kkstudio/project/service/impl/IssueWorkStoreImpl.java)
及 [`IssueWorkMapper`](../../project/src/main/java/fun/fengwk/kkstudio/project/repo/impl/mapper/IssueWorkMapper.java)。
Harness 的调度时间域见 [Harness Infra](harness-infra.md#work-的-claim--lease--wake)。

## 错误映射

模块内的 `ProjectValidationException`、`ProjectNotFoundException`、
`ProjectVersionConflictException`、`ProjectDuplicateException` 在 Web 边界由
`StudioProjectErrorAdvice` 统一映射：validation 400、not found 404、version/duplicate/runtime conflict 409、
`IllegalStateException` 500，并输出稳定的 `PROJECT_*` 错误码。在 Agent 工具边界由 platform 的
`platform.project.tool` 译成 `AiValidationException` / `AiVersionConflictException` 等平台错误类型。

## 测试与验证

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -pl project -am verify
```

`verify` 对本模块的 domain 包做 ≥90% 行覆盖检查，并运行
[`ProjectArchitectureTest`](../../project/src/test/java/fun/fengwk/kkstudio/project/ProjectArchitectureTest.java)
与确定性调度单测。事务、锁序与宿主适配的端到端语义由
[platform 侧的 Project/Issue 集成测试](../../platform/src/test/java/fun/fengwk/kkstudio/platform/project/)
以真实 PostgreSQL 加真实适配器覆盖：同一份用例在两侧各自验证自己拥有的部分，不互相复制。
