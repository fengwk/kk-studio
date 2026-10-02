# 内置 Task 与 Thread Join 的行为验证

`task` 是异步持久接受，不是等子 Agent 完成才返回的同步工具。
验证必须同时观察即时 tool_result、子执行、固定 receipt 与父完成消息；只看 accepted 回执不能证明任务完成。
设计见[内置工具与异步委派](../modules/builtin-tools-design.md)，全局边界见[系统设计](../system-design.md)。

## 先区分接受和交付

```text
父 task 调用
  -> 同事务接受源命令、不可变父子关系、join 凭据与 Work
  -> 立即 tool_result {"thread_id":"…","status":"accepted"}
子执行首次 Idle
  -> 同事务冻结 receipt、向父入队 CUSTOM_MESSAGE、父重新标为活跃
  -> 父收到 SystemReminder，内层为 <subagent_result thread_id agent state>
```

join 保存身份、接受版本、固定回执与交付引用，不复制 prompt/报告/错误，也没有独立状态枚举。
递归 IDLE 要求本地无工作且全部永久直接孩子 IDLE；未交付 join 数量不决定生命周期。
显式停止的父只保存 receipt，不自动唤醒；它接受真正新输入时才原子交付旧结果。

## 按层运行

从仓库根目录使用 JDK 21。前三组以纯函数、内存 Store 和测试替身为主；
Platform 中的集成测试以及后两组需要 Docker/Testcontainers。
容器数据库由测试创建，不得替换成部署数据库。测试模型是回显替身，不产生真实模型费用。

```bash
# 接受、递归状态、回执、提醒与内存 Store 契约
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/runtime -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='HarnessRuntimeJoinAcceptanceTest,ThreadProcessorIdleJoinDeliveryTest,ThreadProcessorSoftBudgetTest,ThreadJoin*Test,HarnessRuntimeStopRecursivePropagationTest,HarnessRuntimeStopIdleTest,InMemoryJoinTest,ModelProcessorTest,SystemReminderTest'

# 工具解析、即时回执、配置与 Contributor 目录
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/builtin -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='TaskToolTest,SubagentTaskRequestTest,SubagentTaskMessagesTest,SubagentConfigTest,BuiltinHarnessContributorTest'

# Platform 接受编排、Agent 物化、设置、归属与清理
env JAVA_HOME="$JAVA_HOME_21" mvn -pl platform -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='SubagentTaskRunnerTest,AgentBranchSettingsMaterializerTest,AgentPromptComposerTest,HarnessOneShotServiceTest,DatabaseTurnResolverTest,BuiltinHarnessContributorConfigurationTest,SystemSettingsTest,SessionDeletionOrchestratorTest,HarnessOwnerQueryServiceTest,IssueReconcilerIntegrationTest'

# PostgreSQL 事务、全局准入锁、树锁、回滚与停止/交付竞争
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/infra -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PostgresqlJoinAcceptanceRollbackTest,PostgresqlParentStopChildJoinConcurrencyTest,PostgresqlJoinTest'

# Runtime + dispatcher/Processor + PostgreSQL 执行链路，以及公开状态投影
env JAVA_HOME="$JAVA_HOME_21" mvn -pl web -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='ThreadJoinDelegationPostgresIntegrationTest,HarnessRuntimeResponseMapperTest,StudioHarnessThreadControllerTest'
```

`HarnessStoreJoinContract` 是抽象测试基座，由 `InMemoryJoinTest` 与 `PostgresqlJoinTest` 执行，
不能只过滤抽象类就声称存储契约通过。目标模块 `target/surefire-reports` 应有实际执行记录；
Docker 不可用时的跳过不算数据库事务已验证。
覆盖率在各模块 `target/site/jacoco`，对应 `verify` 门禁按各 POM 的类清单检查，
过滤集合不代表全模块覆盖率。

## 参数、授权、身份与幂等

| 行为 | 主要证据 |
| --- | --- |
| 必填参数、语义错误、可选 max_turns/thread_id、durable 上下文 | `TaskToolTest`、`SubagentTaskRequestTest` |
| 唯一即时回执形状 | `SubagentTaskMessagesTest.acceptedContainsOnlyStableChildIdentity`、`TaskToolTest.acceptsValidArgumentsAndReturnsAcceptedReceipt` |
| invocation 身份稳定，冻结事实相同才能重放 | `SubagentTaskRunnerTest.stableDerivationAndRequestHashDistinguishDelegations`、`acceptedInvocationReplaysWithoutTouchingRuntimeAcceptance`、`replayedInvocationWithAnyDifferentFrozenFactIsRejected` |
| 并发重复接受只有一个子执行，失败无孤儿行 | `concurrentDuplicateLosingTheRaceReplaysTheWinnersJoin`、`HarnessRuntimeJoinAcceptanceTest.quotaAndInvocationReuseRejectWithoutOrphanSource`、`ThreadJoinDelegationPostgresIntegrationTest.duplicateJoinAcceptanceKeepsExactlyOneChildExecution` |
| 最新 Agent/model/variant 与环境继承物化 | `AgentBranchSettingsMaterializerTest`，包括 `materializesLatestAgentModelAndVariant`、`inheritsParentEnvironmentOnlyWhenSubagentEnablesIt` |
| 冻结 allowlist 是唯一授权来源，foreign parent/无归属调用拒绝 | `DatabaseTurnResolverTest.freezesAllowedSubagentsAndComposesTaskPrompt`、`taskDelegationComesFromLatestAllowlistOnly`；`SubagentTaskRunnerTest.rejectsUnauthorizedSubagentTypeWithAvailableNames`、`rejectsResumingAThreadOfAnotherParent` |

子只持久化不可变 `parent_thread_id`，根与深度从 `findAncestorChain` 派生，不从历史文本或冗余 ROOT
字段猜测。ROOT payload 不承载运行树元数据。继续委派可立即向忙碌子追加 prompt，按最新 settings
收敛，下一次真实 Idle 再结算；`busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix`、
`resumeCursorConflictRetriesWithFreshSnapshot` 与数据库集成测试固定这条行为。

## 额度与事务回滚

`SubagentConfigTest`、`BuiltinHarnessContributorConfigurationTest` 与 `SystemSettingsTest`
固定 `maxDepth`、`maxConcurrency`、`maxTotalConcurrency`、`maxTurns` 四个设置：
全局 `maxTotalConcurrency=0` 表示不限，其余核心预算必须为正。没有子会话独立的重试配置层。

`HarnessRuntimeJoinAcceptanceTest` 与 `HarnessStoreJoinContract` 验证深度、父直接活跃孩子和
跨所有根的全局活跃子 Thread 上限；根不计入，同一忙碌子多个 join 不重复占额。
`PostgresqlJoinAcceptanceRollbackTest` 验证并发全局准入锁、树锁和失败回滚：
源命令、关系、join、Work 必须一起提交或全部回滚。
创建子必须附带 join，父与 expected head 一致，父 STOPPED 时拒绝新委派；
重放不能改写冻结身份、版本、时间、receipt 或 reminder 进度。

## 首次 Idle、receipt 与提醒

`ThreadProcessorIdleJoinDeliveryTest` 验证首次 Idle 与父入队同事务、多 join 按序交付、
多层递归状态上溯和 root ticket。`ThreadJoinProjectorTest` 固定源命令的执行边界到 result head
这一历史范围；之后子继续运行不会改写旧 receipt，不拾取旧任务的回答。
执行前取消、运行失败、STOPPED 部分输出、缺失错误载荷各有独立投影断言。
`HarnessOneShotServiceTest` 验证无父 root ticket 只读固定 receipt，不用新 thread head 代替结果。

`ThreadJoinCompletionRendererTest` 只生成内层 `<subagent_result>`：
任务与结果分开，失败有 `<error>` 与可选 `<partial_result>`，XML 特殊字符转义，缺文本有明确占位，
长正文不截断。外层 USER 角色的 `<system-reminder>` 来自 Runtime 的
[`SystemReminder`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/SystemReminder.java)，
由 `SystemReminderTest` 验证；不能把 join renderer 用例当成外层包裹的证据。
这是 CUSTOM_MESSAGE 的运行时提醒，不是产品 HTTP 面允许提交的普通用户命令。

`ThreadProcessorSoftBudgetTest` 固定实际 turn 计数、继续边界的软预算提醒、durable 去重、
共享 turn 只计一次、compaction/stop 不计数和父停止抑制。
已经完成且 Idle 的子不会因“刚好到 maxTurns”被开启新 turn；软预算不是硬取消。

## 停止、恢复和删除

`HarnessRuntimeStopRecursivePropagationTest` 验证停止在同事务覆盖全部永久执行后代，
与有无 join 无关；停止中间子只影响其子树，迟到回调被 ownership fence 拒绝。
`HarnessRuntimeStopIdleTest` 验证 Idle 的 durable STOP barrier，不是按空闲时长自动取消的 watchdog。

`PostgresqlParentStopChildJoinConcurrencyTest` 验证父停止与子终态的真实竞争、
advisory 树锁阻塞、错误回滚、stale version 零写入和恰一次交付。
`ThreadJoinDelegationPostgresIntegrationTest` 进一步验证父停止后保留 receipt、真正恢复后只交付一次，
以及 dispatcher 仅靠 durable Work/Join 在无通知情况下恢复。

`HarnessRuntimeResponseMapperTest`、`StudioHarnessThreadControllerTest`、
`HarnessOwnerQueryServiceTest` 与 `IssueReconcilerIntegrationTest` 验证 WAITING_CHILDREN/QUEUED
按递归生命周期投影为 processing，Issue Run 不在子树未收敛时结束。
`SessionDeletionOrchestratorTest` 和两种 Store 契约验证整树删除及拒绝遗留子或关键 receipt 引用，
不以静默 CASCADE 掩盖归属错误。树锁顺序是应用协议，数据库不会自动强制所有调用方遵守。

## 真实模型证据的范围

上述确定性测试证明委派机制，不证明模型一定愿意调用 `task`。
真实模型链路是 E2E `real.task_delegation`（L2，requires real/tools），需显式付费授权：

```bash
./scripts/dev/verify/e2e/run.sh --real --with-tools --only real.task_delegation
```

它分别验证 accepted tool_result 与同一子 Thread 的 durable 完成提醒，以及 parentThreadId/ROOT
投影；不能把即时受理当作完成。准备、凭据与报告见[开发与测试](development-and-testing.md#e2e)。
终端状态栏、按键、CLI viewer 和 TUI 审批中继不属于这些测试的证据面；
Studio Thread 面板交互由前端测试另行验证。
