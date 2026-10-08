# 内置 Task 与 Thread Join 的行为验证

`task` 是异步持久接受，不是等子 Agent 完成才返回的同步工具。
验证必须同时观察即时 tool_result、子执行、固定 receipt 与父完成消息；只看 accepted 回执不能证明任务完成。
设计见[内置工具与异步委派](../modules/builtin-tools-design.md)，全局边界见[系统设计](../system-design.md)。

## 先区分接受和交付

```text
父 task 调用
  -> 同事务接受源命令、不可变父子关系、join 凭据与 Work
  -> 立即 tool_result {"thread_id":"…","status":"accepted"}
子执行到达收敛终态边界（源输入已应用，且无未完成直接子 Join / 未送达子回执 / 待处理输入）
  -> 同事务冻结 terminal/final-answer 回执、向父入队 SUBAGENT_RESULT NOTIFICATION
  -> 父 RUNNABLE 时唤醒；父 STOPPED 时通知只固化进历史
  -> 父收到内层 <subagent_result thread_id agent state>
```

join 保存身份、源命令、冻结的终态/最终回答回执与交付引用，不复制 prompt/报告/错误，也没有独立状态枚举。
Thread 的空闲只描述自身：等待子 join 不影响父的 `IDLE`，父也不空转模型或保留等待线程；
但本地 `IDLE` 不等同于整次委派已可回执——子执行仍有未完成后代或待处理输入时，它的本地 final 不会被交付，只有消费这些义务后的下一个收敛终态才结算并向上交付。
父为 `STOPPED` 时完成通知仍持久固化、不唤醒模型；父接受新的任务输入后由 INPUT 一并消费未越过水位的通知。

## 按层运行

从仓库根目录使用 JDK 21。前三组以纯函数、内存 Store 和测试替身为主；
Platform 中的集成测试以及后两组需要 Docker/Testcontainers。
容器数据库由测试创建，不得替换成部署数据库。测试模型是回显替身，不产生真实模型费用。

```bash
# 接受、终态结算、回执与内存 Store 契约
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/runtime -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='HarnessRuntimeJoinAcceptanceTest,ThreadLifecycleCoordinatorJoinTest,ThreadInputDemandTest,ThreadProcessorSoftBudgetTest,ThreadJoin*Test,HarnessRuntimeStopSubtreeTest,HarnessRuntimeStopReplayTest,HarnessRuntimeThreadSemanticsScenarioTest,HarnessRuntimeStopIdleTest,InMemoryJoinTest,ModelProcessorTest,SystemReminderTest'

# 工具解析、即时回执、配置与 Contributor 目录
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/builtin -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='TaskToolTest,SubagentTaskRequestTest,SubagentTaskMessagesTest,SubagentConfigTest,BuiltinHarnessContributorTest'

# Platform 接受编排、Agent 物化、设置、归属与清理
env JAVA_HOME="$JAVA_HOME_21" mvn -pl platform -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='SubagentTaskRunnerTest,AgentBranchSettingsMaterializerTest,AgentPromptComposerTest,HarnessOneShotServiceTest,DatabaseTurnResolverTest,BuiltinHarnessContributorConfigurationTest,SystemSettingsTest,SessionDeletionOrchestratorTest,HarnessOwnerQueryServiceTest,IssueReconcilerIntegrationTest'

# PostgreSQL 事务、准入与树锁、忙时继续、分支隔离、停止/交付竞争与委派收敛
env JAVA_HOME="$JAVA_HOME_21" mvn -pl harness/infra -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PostgresqlJoinAcceptanceRollbackTest,PostgresqlParentStopChildJoinConcurrencyTest,PostgresqlJoinQuiescenceConcurrencyTest,PostgresqlSubagentContinuationTest,PostgresqlJoinTest'

# Runtime + dispatcher/Processor + PostgreSQL 执行链路，以及公开状态投影
env JAVA_HOME="$JAVA_HOME_21" mvn -pl web -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='ThreadJoinDelegationPostgresIntegrationTest,HarnessRuntimeResponseMapperTest,StudioHarnessThreadControllerTest'
```

`HarnessStoreJoinContract` 是抽象测试基座，由 `InMemoryJoinTest` 与 `PostgresqlJoinTest` 执行，
不能只过滤抽象类就声称存储契约通过。目标模块 `target/surefire-reports` 应有实际执行记录；
Docker 不可用时的跳过不算数据库事务已验证。
覆盖率在各模块 `target/site/jacoco`。上述 `test` 命令生成报告；配置了 JaCoCo `check` 的模块
在 `verify` 阶段按各 POM 的类清单执行门禁，[harness/infra](../../harness/infra/pom.xml) 不绑定该门禁。
[harness/builtin](../../harness/builtin/pom.xml) 的门禁检查 Contributor/GoalStateCodec，而非全部 Task 实现。
过滤集合的覆盖率只代表本次执行范围。

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
收敛，在下一次 turn 边界消费；`busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix`、
`resumeCursorConflictRetriesWithFreshSnapshot` 与数据库集成测试固定这条行为。

[`PostgresqlSubagentContinuationTest`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlSubagentContinuationTest.java)
通过真实独立子 Session 和 RUNNING 模型验证完整 SET_* + prompt batch 排队，不中断或覆盖当前请求；
旧 final 不提前交付，后续 INPUT 消费后，两条未完成 Join 在最新收敛终态各交付一次，重放不重复。
另一个用例验证 ROOT fork 为独立执行根：原有子执行与回执仍属于原父，新分支的命令和历史不受后续交付影响，
从新分支向旧子执行添加 Join 因直接父不匹配原子拒绝。

## 额度与事务回滚

`SubagentConfigTest`、`BuiltinHarnessContributorConfigurationTest` 与 `SystemSettingsTest`
固定 `maxDepth`、`maxConcurrency`、`maxTotalConcurrency`、`maxTurns` 四个设置：
全局 `maxTotalConcurrency=0` 表示不限，其余核心预算必须为正。

`HarnessRuntimeJoinAcceptanceTest` 与 `HarnessStoreJoinContract` 验证深度、单父未完成子 Join 和
跨所有根的全局未完成子 Join 上限；root ticket 不计入，同一忙碌子多个未匹配 join 各占一份额度。
`busyChildDuplicateJoinsQuotaCountsEachUnmatchedJoinNotThreadState` 固定该计数与 Thread 自身执行状态无关。
`PostgresqlJoinAcceptanceRollbackTest` 验证并发全局准入锁、树锁和失败回滚：
源命令、关系、join、Work 必须一起提交或全部回滚。
创建子必须附带 join，父与 expected head 一致，父 STOPPED 时拒绝新委派；
重放不能改写冻结身份、版本、时间、receipt 或 reminder 进度。

## 收敛终态结算、receipt 与提醒

`ThreadLifecycleCoordinatorJoinTest` 验证源输入未应用时不结算、普通结算的收敛判据（未完成直接子 Join、未送达子回执、待处理输入）以及结算后只投递一次；
`ThreadInputDemandTest` 以 typed 矩阵锁定被复用的输入需求判据（QUEUED 消息/通知与越过水位前已物化的 APPLIED 通知构成需求，设置命令不构成）。
`HarnessRuntimeThreadSemanticsScenarioTest`、`HarnessRuntimeStopSubtreeTest` 与 PostgreSQL 委派用例
覆盖父 `RUNNABLE` 唤醒、父 `STOPPED` 只固化通知及多层委派。
`ThreadJoinProjectorTest` 固定源命令的执行边界到冻结的 `terminalEntryId` / `finalAnswerEntryId`
这一历史范围；之后子继续运行不会改写旧 receipt，不拾取旧任务的回答。
执行前取消、运行失败、STOPPED 部分输出、缺失错误载荷各有独立投影断言。
`HarnessOneShotServiceTest` 验证无父 root ticket 只读固定 receipt，不用新 thread head 代替结果。

`ThreadJoinCompletionRendererTest` 只生成内层 `<subagent_result>`：
任务与结果分开，失败有 `<error>` 与可选 `<partial_result>`，XML 特殊字符转义，缺文本有明确占位，
长正文不截断。该渲染结果就是 `SUBAGENT_RESULT` `NOTIFICATION` 命令的正文，由
[`ThreadJoinCompletion`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/join/ThreadJoinCompletion.java)
构造并冻结，notificationId 由 invocationId 确定性派生，重复交付幂等、不从子最新 head 重建旧结果。
`SystemReminderTest` 单独覆盖运行时的 system reminder 识别，与 join 交付是两条独立证据链。
NOTIFICATION 是系统通知，不是产品 HTTP 面允许提交的普通用户输入。

`ThreadProcessorSoftBudgetTest` 固定实际 turn 计数、继续边界的 `TASK_BUDGET` 提醒、durable 去重、
共享 turn 只计一次、compaction/stop 不计数和停止后不再提醒。
已经到达终态的子不会因“刚好到 maxTurns”被开启新 turn；软预算不是硬取消。

## 停止、恢复和删除

`HarnessRuntimeStopSubtreeTest` 验证停止在同事务覆盖全部永久执行后代，
与有无 join 无关；停止中间子只影响其子树，迟到回调被 ownership fence 拒绝。
`HarnessRuntimeStopReplayTest` 固定同 `stopRequestId` 精确重放返回原回执、不按当前树重算范围。
`HarnessRuntimeStopIdleTest` 验证 Idle 的 durable STOP barrier，不是按空闲时长自动取消的 watchdog。

`PostgresqlParentStopChildJoinConcurrencyTest` 验证父停止与子终态的真实竞争、
advisory 树锁阻塞、错误回滚、stale version 零写入和恰一次交付。
`PostgresqlJoinQuiescenceConcurrencyTest` 覆盖 B 先完成、C 先交付两种调度：B 必须消费子回执并完成新回合，才能以新的成功 final 向 A 结算。测试还用真实 PG 树锁与被 latch 的写入代理证明：子冻结结算与父通知入队在同一事务、同一把树锁内，父快照查询在该窗口被阻塞，提交后看到完整通知；该窗口内回滚则 entries/joins/commands/work 全部回退，重试恰交付一次。
`ThreadJoinDelegationPostgresIntegrationTest` 进一步验证父停止后结果直接固化、接受新输入不重复交付、多层委派向上传播，
以及 dispatcher 仅靠 durable Work/Join 在无通知情况下恢复。

`HarnessRuntimeResponseMapperTest`、`StudioHarnessThreadControllerTest`、
`HarnessOwnerQueryServiceTest` 与 `IssueReconcilerIntegrationTest` 验证 Thread 只按自身事实投影
`executionControl`（`RUNNABLE`/`STOPPED`）与逐 Thread `processing`，不递归子树；Issue Run 只按
root Join 冻结的终态回执收尾，不等待永久子树收敛。
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
Studio Thread 面板的导航与审批交互由前端组件测试验证，真实浏览器行为选择 UI 矩阵。
