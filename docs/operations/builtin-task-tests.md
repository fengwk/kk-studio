# Builtin Task 测试映射与不变量验证

这份文档回答两个问题：异步 `task` 委派（thread join）在 kk-studio 里由哪些自动化测试承接，以及
`pi-base` 的 subagent 测试用例逐条对应到哪里、哪一条有意不迁移。

事实源是仓库里的测试代码本身；本文只记录映射关系与边界，不复制测试数量，也不声明某一次运行的结果。可执行入口是
「运行方式」里的命令，通过与失败以各模块 `target/surefire-reports` 为准。部分 `pi-base` 语义在本仓库被整体替换（见各表
「映射关系」列），映射表因此只列仍然成立的不变量。

## 心智模型：从同步阻塞到持久接受 + 首次 Idle 交付

`pi-base` 在父 Agent 的 tool call 内同步阻塞，等子 Agent 跑完后把最终报告放进本次 `tool_result`。
kk-studio 把同一件事拆成两个持久化动作，中间由 Thread 的递归生命周期衔接：

```text
[pi-base]
Parent Turn ──(task call)──► [Runner 阻塞等待子会话] ──(tool_result 含最终报告)──► Parent 继续

[kk-studio]
Parent Model Turn ──(task call)──► TaskTool 持久接受（同事务：源命令 + 父子关系 + join 凭据 + Work）
                                        │
                                        └─► 立即回执 tool_result {"thread_id":"…","status":"accepted"}
                                              │（父本地 turn 结束，可能仍有活跃直接孩子）
                子执行首次 Idle ──► Runtime 同事务：冻结 receipt + 父 Thread 入队完成消息 + 父重新标为活跃
                                        │
                                        └─► 父 Thread 收到独立 CUSTOM_MESSAGE（SystemReminder 形态）
```

关键不变量：

- **接受即持久**：`TaskTool` 只做参数归一化与转发，`SubagentRunner` 在同一事务内完成子 Thread 命令接受
  与 join 凭据写入后就返回；工具只有一个阶段、一次 tool_result。
- **join 只记凭据**：`harness_thread_join` 保存源命令身份、`after_version`、固定回执与交付引用，不复制
  prompt / 报告 / 错误，也没有独立状态枚举。匹配版本与结果 head 只冻结一次，且必须晚于接受版本。
- **递归 Idle**：`IDLE` 要求本地无待处理输入、无适用调用、无续接义务，并且所有永久直接孩子都 `IDLE`；
  未交付的 join 不改变判定。等待子结果用 `WAITING_CHILDREN` 表达，而不是伪造本地工作。
- **首次 Idle 原子交付**：首个「非空闲 -> Idle」的版本推进、join 匹配、父 `CUSTOM_MESSAGE` 入队与父重新
  标为活跃在同一事务内完成；父显式停止时只保存 receipt，绝不自动唤醒。
- **结果固定**：receipt 固定在源命令 `applied_turn_start_entry_id` 到 `resultHeadEntryId` 的历史范围，
  子线程后续继续推进不会改写旧回执；执行前被取消的委派单独投影为取消。
- **只读入口**：`findJoin`、`projectJoinReceipt`、`findAncestorChain`。

## 运行方式

从仓库根目录执行，用 JDK 21；下表涉及的类可按类名或方法名过滤：

```bash
# join 接受、回执投影、完成消息渲染（纯函数 + 内存 Store）
env JAVA_HOME=$JAVA_HOME_21 mvn -pl harness/runtime -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='HarnessRuntimeJoinAcceptanceTest,ThreadProcessorIdleJoinDeliveryTest,ThreadProcessorSoftBudgetTest,ThreadJoin*Test,HarnessRuntimeStopRecursivePropagationTest'

# task 工具解析、即时回执与配置
env JAVA_HOME=$JAVA_HOME_21 mvn -pl harness/builtin -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='TaskToolTest,SubagentTaskRequestTest,SubagentTaskMessagesTest,SubagentConfigTest'

# Platform 侧接受编排、Agent 物化与设置映射
env JAVA_HOME=$JAVA_HOME_21 mvn -pl platform -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='SubagentTaskRunnerTest,AgentBranchSettingsMaterializerTest,AgentPromptComposerTest'

# 真实 PostgreSQL 事务、树锁、删除与恢复（Testcontainers）
env JAVA_HOME=$JAVA_HOME_21 mvn -pl harness/infra -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='PostgresqlJoinAcceptanceRollbackTest,PostgresqlParentStopChildJoinConcurrencyTest,PostgresqlJoinTest'

# 端到端执行树：真实 Runtime + 真实 dispatcher/Processor + 真实 PostgreSQL（模型用回显替身）
env JAVA_HOME=$JAVA_HOME_21 mvn -pl web -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest='ThreadJoinDelegationPostgresIntegrationTest'
```

Testcontainers 用例需要可用的隔离数据库，不得指向部署数据库。

## 分组映射表

### 1. 委派接受与幂等重试（Acceptance & Idempotency）

| pi-base 用例名（`tests/subagent-*.test.ts`） | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-tool.test.ts`: "delegates a valid task and returns the completed envelope" | `TaskToolTest#acceptsValidArgumentsAndReturnsAcceptedReceipt`、`SubagentTaskRunnerTest#newDelegationAcceptsAtomicallyWithStableIdentityAndAdmissionLimits`、`HarnessRuntimeJoinAcceptanceTest#parentAndNestedChildArePermanentAndJoinFailureRollsBackEverything` | **语义扩展**：pi-base 同步阻塞并把完成包络放进本次 tool_result；本仓库只回执 `{"thread_id","status":"accepted"}`，结果由首次 Idle 匹配的 join 独立交付，接受失败整事务回滚。 |
| `subagent-task-tool.test.ts`: "refreshes the Agent catalog before validating and resuming a task" | `SubagentTaskRunnerTest#busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix`、`AgentBranchSettingsMaterializerTest#materializesLatestAgentModelAndVariant` | **等价**：继续既有子 Thread 时按最新 Agent 配置物化 settings 再发固定前缀命令。 |
| `subagent-task-tool.test.ts`: "advertises the configured default max_turns in the parameter schema"、"describes batching all ready independent delegations in one assistant turn" | `TaskToolTest#exposesCanonicalDescriptorContract`、`AgentPromptComposerTest#rendersTaskInstructionsWithConfiguredDefaultMaxTurns` | **等价**：descriptor / System Prompt 声明批量委派与默认预算语义。 |
| `subagent-task-tool.test.ts`: "uses task max_turns to override the configured budget" | `SubagentTaskRunnerTest#explicitMaxTurnsIsFrozenAndCommandKeysAreDeterministic`、`AgentPromptComposerTest#rendersLiveDefaultMaxTurnsInTaskInstructions` | **等价**：单次调用参数冻结为该次 join 的软预算，覆盖 policy 默认值。 |
| `subagent-task-tool.test.ts`: "refuses concurrent resumes before the first session finishes opening"、"refuses to resume a session that is currently running" | `SubagentTaskRunnerTest#busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix`、`SubagentTaskRunnerTest#rejectsResumingAThreadOfAnotherParent` | **语义收窄 + 扩展**：本仓库允许向仍在执行的子追加源 prompt，由下一次真实 Idle 结算；互斥不再靠内存注册表，而由不可变父子关系与 join 幂等键表达。 |
| `subagent-runner.test.ts`: "spawns, marks running then done, and returns the report + session id" | `ThreadProcessorIdleJoinDeliveryTest#rapidChildCompletionMatchesJoinDeliversCustomMessageToParentAndSetsParentActive`、`HarnessRuntimeJoinAcceptanceTest#rootTicketAcceptsCommandAndReceiptInSameTransactionAndReplays` | **语义扩展**：生命周期拆为「同事务接受」与「首次 Idle 匹配交付」两个持久化动作。 |
| `subagent-runner.test.ts`: "returns error state (not throwing) when the subagent prompt fails"、"returns an error result when the session cannot even be created" | `TaskToolTest#catchesRunnerExceptionsAndCompletesListenerWithError`、`SubagentTaskRunnerTest#runtimeUnavailabilityAndRuntimeRejectionBecomeTypedTaskFailures`、`ThreadProcessorIdleJoinDeliveryTest#childTurnErrorDeliversErrorOutcomeXmlToParent` | **等价**：接受失败收敛为错误 ToolResult，运行期失败在交付时作为 `<error>` 终态。 |
| （本仓库新增不变量：invocation 为幂等键的持久接受） | `TaskToolTest#reportsReplayedAcceptanceOnIdempotentRetry`、`SubagentTaskRunnerTest#acceptedInvocationReplaysWithoutTouchingRuntimeAcceptance`、`SubagentTaskRunnerTest#replayedInvocationWithAnyDifferentFrozenFactIsRejected`、`SubagentTaskRunnerTest#concurrentDuplicateLosingTheRaceReplaysTheWinnersJoin`、`ThreadJoinDelegationPostgresIntegrationTest#duplicateJoinAcceptanceKeepsExactlyOneChildExecution` | **语义扩展**：同 `invocationId` 重放返回既有子 Thread，不重复开启执行；冻结事实不同则拒绝。 |
| （本仓库新增不变量：接受失败绝不留下孤儿行） | `HarnessRuntimeJoinAcceptanceTest#quotaAndInvocationReuseRejectWithoutOrphanSource`、`PostgresqlJoinAcceptanceRollbackTest#acceptCommandsAndJoinPreflightFailureRollsBackAllTablesWithoutOrphans`、`PostgresqlJoinAcceptanceRollbackTest#acceptCommandsAndJoinReusedInvocationIdRejectionLeavesNoOrphans` | **本仓库新增不变量**：源命令、父子关系、join 与 Work 要么一起提交，要么一行都不写。 |

### 2. 子身份派生与环境/会话隔离（Identity Derivation & Isolation）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-real-factory.test.ts`: "persists agent/depth/root metadata before binding an isolated child session" | `SubagentTaskRunnerTest#stableDerivationAndRequestHashDistinguishDelegations`、`HarnessStoreJoinContract#findAncestorChainReturnsSingleRootAndHierarchy` | **语义收窄**：只持久化不可变 `parent_thread_id`；根与深度由递归祖先链派生，不再物化 root/depth 冗余字段。 |
| `subagent-real-factory.test.ts`: "creates a new child with the target agent model and thinking level before binding" | `AgentBranchSettingsMaterializerTest#materializesLatestAgentModelAndVariant`、`AgentBranchSettingsMaterializerTest#honorsExplicitAgentVariantOverride`、`SubagentTaskRunnerTest#newDelegationAcceptsAtomicallyWithStableIdentityAndAdmissionLimits` | **等价**：新建子线程按目标 Agent + 显式 variant 物化独立 settings。 |
| `subagent-real-factory.test.ts`: "reopens matching legacy session files and refreshes the requested agent type on resume" | `AgentBranchSettingsMaterializerTest#inheritsParentEnvironmentOnlyWhenSubagentEnablesIt`、`AgentBranchSettingsMaterializerTest#keepsNullEnvironmentWhenParentHasNone`、`SubagentTaskRunnerTest#busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix` | **语义收窄**：不再有会话文件；继续委派按目标 Agent 的最新 settings 差量收敛，环境继承由 Agent 配置决定。 |
| `subagent-real-factory.test.ts`: "throws a clear error when no persisted session file matches the requested id" | `SubagentTaskRunnerTest#rejectsResumingAThreadOfAnotherParent`、`SubagentTaskRunnerTest#rejectsDetachedOrForeignInvocationWithoutTouchingAcceptance` | **等价**：目标 Thread 不存在或归属不符时明确拒绝。 |
| `subagent-runner.test.ts`: "uses a hashed cwd-derived directory name to avoid lexical path collisions" | `SubagentTaskRunnerTest#stableDerivationAndRequestHashDistinguishDelegations` | **语义收窄**：子身份由 UUID namespace + `invocationId` 稳定派生；工作目录由 Environment 承接，不在委派层派生。 |
| `subagent-foundation.test.ts`: "defaults to root depth when no depth entry exists"、"reads the latest depth entry and treats deeper sessions as non-root"、"ignores malformed depth values" | `HarnessStoreJoinContract#findAncestorChainReturnsSingleRootAndHierarchy`、`HarnessRuntimeJoinAcceptanceTest#joinAcceptanceBoundaryRejections` | **语义收窄**：不再解析历史里的 depth / root 条目；深度与根身份每次由 `findAncestorChain` 的递归祖先链派生，畸形条目不可能出现。 |
| `subagent-runner.test.ts`: "preserves the caller's persisted root-session id in registry nodes" | `HarnessStoreJoinContract#findAncestorChainReturnsSingleRootAndHierarchy`、`HarnessRuntimeJoinAcceptanceTest#rootTicketAcceptsCommandAndReceiptInSameTransactionAndReplays` | **等价**：根身份由祖先链给出，并在接受事务内用于树锁，不依赖内存注册表。 |

### 3. 归属、深度与并发额度（Ownership, Depth & Quotas）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-tool.test.ts`: "enforces the per-session concurrency cap for new spawns"、"enforces maxConcurrency across parallel task calls in the same turn"、"applies maxConcurrency to resumed subagent sessions too" | `SubagentTaskRunnerTest#newDelegationAcceptsAtomicallyWithStableIdentityAndAdmissionLimits`、`HarnessRuntimeJoinAcceptanceTest#quotaAndInvocationReuseRejectWithoutOrphanSource`、`HarnessRuntimeJoinAcceptanceTest#quotaExistingBusyChildAndHierarchyQuotas`、`PostgresqlJoinAcceptanceRollbackTest#acceptCommandsAndJoinQuotaExceededRollsBackEntirely`、`ThreadJoinDelegationPostgresIntegrationTest#concurrentJoinAcceptanceSerializesParentQuotaAndRollsBackLosers` | **语义扩展**：pi-base 依赖内存注册表；本仓库在接纳事务内以持久直接孩子计数为准，并发超额整体回滚。 |
| `subagent-task-tool.test.ts`: "enforces maxTotalConcurrency across the whole root delegation tree"、"enforces maxTotalConcurrency across parallel starts under the same root" | `HarnessStoreJoinContract#countActiveSubagentThreadsCountsActiveExecutionChildrenAcrossAllRoots`、`HarnessStoreJoinContract#busyChildDuplicateJoinsQuotaCountsDistinctActivePermanentThreadsNotJoins`、`HarnessRuntimeJoinAcceptanceTest#globalSubagentConcurrencyCapSpansRootTreesAndReleasesOnIdle`、`PostgresqlJoinAcceptanceRollbackTest#globalJoinAdmissionSerializesConcurrentNewSessionsAcrossRootTreesAndEnforcesCap` | **语义扩展**：平台设置为跨所有根的全局活跃子 Thread 上限；根不计入，同一忙碌子上的多个 join 不重复占额。全局准入锁保护额度判定与创建，超限整体回滚。 |
| `subagent-task-tool.test.ts`: "passes childDepth = parent depth + 1"、"withholds `task` when depth has reached maxDepth" | `HarnessRuntimeJoinAcceptanceTest#joinAcceptanceBoundaryRejections`、`ThreadJoinDelegationPostgresIntegrationTest#joinDepthQuotaRejectionRollsBackGrandChild` | **等价**：深度由递归祖先链计算，超限拒绝且整事务回滚。 |
| `subagent-task-injection.test.ts`: "filters unknown subagents at load time so task is never injected for them"、"withholds `task` when depth has reached maxDepth" | `SubagentTaskRunnerTest#rejectsUnauthorizedSubagentTypeWithAvailableNames`、`DatabaseTurnResolverTest#freezesAllowedSubagentsAndComposesTaskPrompt`、`DatabaseTurnResolverTest#taskDelegationComesFromLatestAllowlistOnly`、`DatabaseTurnResolverTest#rejectsMissingSubagentWithoutSilentFallback` | **等价**：冻结的 subagent allowlist 是唯一授权来源，缺失即拒绝，绝不静默回退。 |
| `subagent-foundation.test.ts`: "defaults the root session id to the current session and restores persisted child roots"、"ignores malformed root-session entries" | `HarnessRuntimeJoinAcceptanceTest#ancestorIdleTransitionToWaitingChildrenOnChildSessionAndThreadCommands`、`HarnessStoreJoinContract#findAncestorChainReturnsSingleRootAndHierarchy` | **语义收窄**：根身份由祖先链推导而非解析历史条目，畸形 root 记录不可能出现。 |
| `subagent-config.test.ts`: "applies defaults when no subagent config is present"、"reads explicit project overrides"、"fills only the missing field with its default"、"rejects non-positive maxDepth at load time"、"rejects non-integer maxConcurrency and maxTotalConcurrency at load time"、"rejects negative maxTotalConcurrency and non-positive maxTurns at load time" | `SubagentConfigTest#preservesValidValues`、`SubagentConfigTest#rejectsNegativeMaxTotalConcurrency`、`SubagentConfigTest#rejectsNonPositiveCoreBudgets`、`SubagentConfigTest#exposesCanonicalErrorMessages`、`BuiltinHarnessContributorConfigurationTest#subagentConfigMapsAllAiRuntimeFields`、`SystemSettingsTest#rejectsInvalidSubagentBudgets` | **语义收窄**：配置只保留 `maxDepth`、`maxConcurrency`、`maxTotalConcurrency`（0 = 不限）与 `maxTurns`；它们的来源与校验由 `aiRuntime.subagent*` 设置锁定。 |

### 4. 停止传播与停止门禁（Stop Propagation & Gating）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "cascades parent-turn cancellation to the subagent and reports cancelled"、"propagates a parent abort to already-running descendant subagents in the same tree" | `HarnessRuntimeStopRecursivePropagationTest#threeLevelHierarchyIsStoppedRecursivelyInSingleTransaction`、`HarnessRuntimeStopRecursivePropagationTest#recursiveStopStopsDescendantsRegardlessOfNoJoinOrPendingJoin`、`HarnessRuntimeStopRecursivePropagationTest#stoppingIntermediateChildOnlyStopsItsDescendantsAndLeavesRootUntouched` | **语义扩展**：停止在单事务内传播到全部执行后代，与是否存在 join 无关。 |
| `subagent-runner.test.ts`: "reports a terminal assistant abort as cancelled without a parent abort signal"、"keeps terminal response text when parent cancellation resolves the prompt" | `ThreadJoinProjectorTest#stoppedTurnProducesCancelledOutcomeWithPartialReport`、`ThreadJoinProjectorTest#cancelledTurnProducesCancelledOutcomeWithPartialReport`、`ThreadProcessorIdleJoinDeliveryTest#parentStoppedReceiptIsHeldAndNotDeliveredUntilNextInput` | **等价**：取消终态完整保留已产生的部分输出。 |
| `subagent-runner.test.ts`: "does not create or resume a session when cancellation already happened" | `ThreadJoinProjectorTest#cancelledBeforeExecutionProducesCancelledOutcomeWithoutOlderAssistant`、`HarnessRuntimeStopRecursivePropagationTest#stopBeforeSourceExecutedProjectsCancelledOutcome` | **等价**：源命令执行前被取消的委派投影为取消，绝不误拾取更早的回答。 |
| （本仓库新增不变量：停止的父不自动唤醒，恢复时原子交付） | `ThreadJoinDelegationPostgresIntegrationTest#stoppedParentHoldsMatchedReceiptUntilGenuineResumeDeliversExactlyOnce`、`HarnessRuntimeJoinAcceptanceTest#haltedDeliveryFlushOnNextUserInputAndReplayOrder`、`HarnessRuntimeStopRecursivePropagationTest#parentStoppedBarrierHoldsDeliveryUntilRealInput`、`PostgresqlParentStopChildJoinConcurrencyTest#childTerminalApplyDeliversReceiptOnceThenParentStopCancelsQueuedDeliveryOnPostgres` | **本仓库新增不变量**：显式停止的父只保存已匹配 receipt；父真实接受新输入时同一事务原子交付旧结果，重放不产生第二次交付。 |
| （本仓库新增不变量：迟到结果失去所有权） | `HarnessRuntimeStopRecursivePropagationTest#lateCallbackOnDescendantLosesOwnershipAfterParentStop`、`HarnessRuntimeStopRecursivePropagationTest#terminalApplyPendingOnChildBlocksStopAndRollsBackEntireTree` | **本仓库新增不变量**：停止后到达的模型/工具回调被既有 ownership fence 拒绝，不污染已提交终态。 |
| `subagent-permission-relay.test.ts`: "stops waiting for the root permission UI when the subagent request is aborted" | `HarnessRuntimeStopRecursivePropagationTest#threeLevelHierarchyIsStoppedRecursivelyInSingleTransaction` | **不迁移 + 等价覆盖**：kk-studio 没有终端权限中继界面；停止传播由 Runtime 直接完成。 |
| `subagent-runner.test.ts`: "aborts a subagent that stays idle past idleTimeoutMs"、"observes asynchronous abort failures from the idle watchdog"、"does not count long-running tool execution silence toward idleTimeoutMs" | `HarnessRuntimeStopIdleTest#idleStopPersistsStopBarrierTurnAndBumpsVersionOnce`、`HarnessRuntimeStopIdleTest#idleWithQueuedCommandsCancelsAllAtOneNowAndBumpsVersionExactlyOnce`、`HarnessRuntimeStopIdleTest#foreignOpenTurnStopOnlyCancelsCommandsWithoutWritingAMarker` | **不迁移 + 等价覆盖**：委派没有按空闲时长自动中止的看门狗；`IDLE` 是稳定状态，只有显式 Stop 才在唯一事务内写入 durable 停止边界并取消排队命令。 |

### 5. 首次 Idle 匹配与递归收敛（First-Idle Match & Recursive Convergence）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "spawns, marks running then done, and returns the report + session id"（交付部分） | `ThreadProcessorIdleJoinDeliveryTest#rapidChildCompletionMatchesJoinDeliversCustomMessageToParentAndSetsParentActive`、`ThreadProcessorIdleJoinDeliveryTest#propagateIdleDeliversJoinAndRequestsWorkForParent`、`ThreadJoinDelegationPostgresIntegrationTest#acceptedWorkIsRecoveredFromDurableRowsWithoutAnyNotification` | **语义扩展**：首个非空闲 -> Idle 的版本推进、匹配与父入队同事务完成，不存在假 Idle 窗口。 |
| （本仓库新增不变量：固定回执不被后续子历史改写） | `ThreadJoinProjectorTest#fixedHeadSnapshotIgnoresSubsequentTurns`、`ThreadJoinProjectorTest#sourceRangeSlicingIgnoresOlderTurns`、`ThreadJoinDelegationPostgresIntegrationTest#firstIdleFreezesReceiptAndLaterChildHistoryNeverChangesIt` | **本仓库新增不变量**：receipt 固定在结果 head，子线程继续推进不改写旧结果。 |
| （本仓库新增不变量：多 join 命中同一子） | `ThreadProcessorIdleJoinDeliveryTest#multipleJoinsOnSameChildMatchInOrderAndDeliverConsecutiveCommandsToParent`、`ThreadJoinProjectorTest#handlesMultiInputAndCustomCommandPayloads` | **本仓库新增不变量**：同一子上的多个 join 按序匹配并各自交付，同一 turn 的多输入可共享结果。 |
| （本仓库新增不变量：忙碌子 resume 到下一次 Idle 才结算） | `ThreadJoinDelegationPostgresIntegrationTest#busyChildResumeIsAcceptedImmediatelyAndSettlesAtNextIdle`、`SubagentTaskRunnerTest#busyChildAcceptsAdditionalPromptWithoutPriorDeliveryAndWithFixedPrefix`、`SubagentTaskRunnerTest#resumeCursorConflictRetriesWithFreshSnapshot` | **本仓库新增不变量**：resume 不要求子已 Idle，立即接受，绝不同步等待。 |
| `subagent-foundation.test.ts`: "tracks nodes, children, and running counts"、"filters snapshots by root session" | `ThreadProcessorIdleJoinDeliveryTest#waitingChildrenPropagationParentTransitionsToIdleWhenLastChildBecomesIdle`、`ThreadProcessorIdleJoinDeliveryTest#multiLevelHierarchyPropagationWithoutJoinsPropagatesRecursivelyToRoot`、`ThreadProcessorIdleJoinDeliveryTest#advanceHeadWithActiveChildrenTransitionsToWaitingChildren`、`ThreadProcessorIdleJoinDeliveryTest#propagateIdleTransitionsAncestorWithLocalWorkToActive`、`ThreadProcessorIdleJoinDeliveryTest#propagateIdleTransitionsAncestorWithActiveChildrenToWaitingChildren`、`ThreadProcessorIdleJoinDeliveryTest#settleStoppedTreeTransitionsActiveThreadToIdle`、`ThreadProcessorIdleJoinDeliveryTest#hasLocalWorkRecognizesActiveAndPendingContexts` | **语义扩展**：用持久递归状态替代内存节点表；子活动上溯把父标为 `WAITING_CHILDREN`，最后一个孩子 Idle 后父回到 `IDLE`。 |
| （本仓库新增不变量：三层树逐级带回结果，跨 dispatcher 重启收敛） | `ThreadJoinDelegationPostgresIntegrationTest#threeLevelTreePropagatesResultsUpTheParentChain`、`ThreadJoinDelegationPostgresIntegrationTest#parentWaitsChildrenOnlyWhileChildIsActiveAndReturnsToIdleAfterwards`、`ThreadProcessorIdleJoinDeliveryTest#rootTicketMatchedWhenRootThreadBecomesIdle` | **本仓库新增不变量**：结果沿不可变父链逐级传递，父在子活跃期间保持 `WAITING_CHILDREN`。 |
| （本仓库新增不变量：内部 root one-shot 的 completion ticket） | `HarnessRuntimeJoinAcceptanceTest#rootTicketAcceptsCommandAndReceiptInSameTransactionAndReplays`、`HarnessOneShotServiceTest#submitAcceptsSourcePromptAndRootTicketInOneTransaction`、`HarnessOneShotServiceTest#awaitReturnsReceiptReportWithoutReadingAnyThreadHead`、`HarnessOneShotServiceTest#awaitReReadsOnlyTheFixedReceiptOnVersionSignal` | **本仓库新增不变量**：parent 为空的 join 不投递父消息，只接受源 prompt 与回执，消除了 subscribe-after-start 的丢失窗口。 |

### 6. 软预算与轮数提醒（Soft Budget & Turn Reminders）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "steers the child at the turn limit without hard-aborting it"、"does not steer a final-only assistant turn that exactly reaches maxTurns"、"re-steers every five effective tool-driving turns after maxTurns"、"does not count errored or aborted assistant messages toward maxTurns" | `ThreadProcessorSoftBudgetTest#continueBoundaryAtMaxTurnsEnqueuesSystemReminderAndAdvancesReminderTurn`、`ThreadProcessorSoftBudgetTest#noReminderOnTerminalIdleEvenWhenReachingMaxTurns`、`ThreadProcessorSoftBudgetTest#modelContinuePlanBoundaryEnqueuesReminder`、`ThreadProcessorSoftBudgetTest#compactionAndStopTurnsDoNotCountTowardMaxTurns`、`ThreadProcessorIdleJoinDeliveryTest#countActualTurnsReturnsZeroWhenSourceNotAppliedOrMissingFromPath` | **语义扩展**：pi-base 在同步阻塞循环内 steer；本仓库按源命令实际 turn 计数，在已确定继续运行的边界用 durable reminder 进度入队，不为已经 Idle 的已完成子线程开启新 turn。 |
| `subagent-runner.test.ts`（本仓库新增：提醒幂等与父停止抑制） | `ThreadProcessorSoftBudgetTest#retryIdempotenceDoesNotDuplicateReminderOrThrow`、`ThreadProcessorSoftBudgetTest#multiInputSharedTurnCountsCorrectly`、`ThreadProcessorSoftBudgetTest#parentStoppedSkipsReminderInjection` | **本仓库新增不变量**：提醒以 durable 进度去重，多输入共享 turn 只计一次，父停止时不注入。 |

### 7. 接受边界与参数校验（Acceptance Boundaries & Arguments）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-tool.test.ts`: "rejects missing required args"、"reports a missing subagent_type with the currently available agents"、"rejects a non-positive task max_turns override" | `TaskToolTest#rejectsSchemaViolationsOnRequestCreation`、`TaskToolTest#rejectsSemanticViolationsWithDescriptiveErrors`、`SubagentTaskRequestTest#rejectsInvalidDelegationFacts` | **等价**：必填字段、非法 JSON、非正整轮数与格式错误严格拒绝且给出可读原因。 |
| `subagent-task-tool.test.ts`: "advertises the configured default max_turns in the parameter schema" | `TaskToolTest#handlesOptionalMaxTurnsAndThreadId`、`SubagentTaskRequestTest#preservesExplicitFactsAndOptionalDefaults` | **等价**：缺省即 null，走 policy 默认预算并新建子线程。 |
| `subagent-task-tool.test.ts`: "marks the definition with the owning pi-base module instance" | `TaskToolTest#requiresDurableExecutionContext`、`BuiltinHarnessContributorTest#catalogFreezesExactInventoryOf12ToolsAndAssociatedCapabilities` | **语义收窄**：工具归属由 Contributor 目录表达，执行必须携带 durable 上下文。 |
| `subagent-foundation.test.ts` 权限宿主部分 | `SubagentTaskRunnerTest#rejectsUnauthorizedSubagentTypeWithAvailableNames` | **不迁移 + 等价覆盖**：委派授权来自 Agent 的 subagent allowlist，而非终端权限宿主。 |
| （本仓库新增不变量：join 接纳边界） | `HarnessRuntimeJoinAcceptanceTest#joinAcceptanceBoundaryRejections`、`HarnessRuntimeJoinAcceptanceTest#replayPrecedingNonDeliveryRejectionAndNonContiguousRejection`、`HarnessRuntimeJoinAcceptanceTest#replayInitialAndAcceptNewThreadValidation`、`HarnessRuntimeJoinAcceptanceTest#goalAndCustomMessageInputRecognition`、`ThreadJoinRequestTest#rejectsInconsistentParentAndHead`、`ThreadJoinRequestTest#rejectsInvalidAdmissionLimits` | **本仓库新增不变量**：创建子必须附带 join、父标识与 expected head 必须一致、父停在 `STOPPED` 边界拒绝新委派、重放命令缺失 join 或凭据被改用不同事实时拒绝。 |
| （本仓库新增不变量：join 记录不变量） | `ThreadJoinTest#rejectsInconsistentMatchedReceipt`、`ThreadJoinTest#rejectsInvalidDeliveryCommandSequence`、`ThreadJoinTest#validateTransitionRejectsFrozenReceiptMutation`、`ThreadJoinTest#validateTransitionRejectsIdentityMutation`、`ThreadJoinTest#validateTransitionRejectsTimeAndReminderRegressions`、`ThreadJoinReceiptTest#rejectsInvalidConstructorArguments`、`ThreadJoinOutcomeTest#outcomeValuesMatchWireNames` | **本仓库新增不变量**：匹配与交付同空/同非空、只写一次、晚于接受版本；身份、时间与 reminder 进度不得回退。 |

### 8. 回执投影与完成消息（Receipt Projection & Completion Rendering）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "wraps a completed report and includes the session id for resume"、"renders a placeholder when the completed child produced no textual report" | `ThreadJoinCompletionRendererTest#rendersCompletedMessageWithPromptAndReport`、`ThreadJoinCompletionRendererTest#fallsBackToPlaceholdersForMissingText`、`ThreadJoinCompletionRendererTest#rendersReceiptDirectly` | **等价**：完成消息外层 `<subagent_result thread_id agent state>`，内含本次任务原文 `<task>` 与结果 `<result>`，缺失文本用明确占位符。 |
| `subagent-runner.test.ts`: "emits an error envelope with the session id for failures"、"includes a bounded partial report in a failed task envelope" | `ThreadJoinCompletionRendererTest#rendersFailedMessageWithSeparatedErrorAndPartial`、`ThreadJoinCompletionRendererTest#rendersCancelledMessageWithOrWithoutPartialResult`、`ThreadJoinProjectorTest#failedTurnWithAssistantTextAndErrorPayloadSeparatesPartialResultAndError`、`ThreadJoinProjectorTest#failedTurnWithErrorPayloadOnlyProducesNullPartialResultAndDurableError`、`ThreadJoinProjectorTest#failedTurnWithoutErrorPayloadFallsBackToSynthesizedReason` | **等价**：失败/取消分离 `<error>` 与可选 `<partial_result>`，无错误载荷时回退为合成原因。 |
| `subagent-runner.test.ts`: "escapes task attributes and closing-tag payloads in reports and errors" | `ThreadJoinCompletionRendererTest#escapesAttributeAndBodySpecialCharacters`、`ThreadJoinCompletionRendererTest#neverTruncatesLongBodies`、`ThreadJoinCompletionRendererTest#rejectsBlankRequiredText` | **等价**：XML 5 大特殊字符转义，正文不截断，必填文本为空即拒绝。 |
| `subagent-runner.test.ts`: "returns the last assistant text and counts Pi toolCall blocks"、"does not expose text from an interrupted assistant turn that contains tool calls"、"bounds interrupted output before returning the structured task result"、"returns interrupted assistant text together with the terminal error" | `ThreadJoinProjectorTest#completedTurnProducesCompletedOutcomeWithLastReport`、`ThreadJoinProjectorTest#handlesOtherCommandPayloadTypesWithoutTextPrompt`、`ThreadJoinProjectorTest#nonTurnEndHeadThrowsIllegalStateException`、`ThreadJoinProjectorTest#stoppedTurnProducesCancelledOutcomeWithPartialReport` | **语义收窄**：只从源命令执行边界之后的 Entry 链提取最后一段安全文本；工具调用块不单独计一轮，未到 `TURN_END` 的 head 不产生终态回执。 |
| `subagent-runner.test.ts`: "uses the final assistant error state even when prompt resolves normally"、"reports a length-truncated terminal response as an error with partial output"、"clears a transient assistant error after a successful retry" | `ThreadJoinProjectorTest#failedTurnWithErrorPayloadOnlyProducesNullPartialResultAndDurableError`、`ThreadJoinProjectorTest#failedTurnWithoutErrorPayloadFallsBackToSynthesizedReason`、`ThreadJoinProjectorTest#stoppedTurnWithErrorPayloadUsesDurableErrorMessage`、`ModelProcessorTest#transientFailureRetriesWithPolicyDelay` | **等价**：失败终态以 durable 错误为准并保留部分结果；截断与瞬时失败由 Invocation 的重试策略与终态归并处理，不写进委派记录。 |
| （本仓库新增不变量：即时回执唯一形状） | `SubagentTaskMessagesTest#acceptedContainsOnlyStableChildIdentity`、`TaskToolTest#acceptsValidArgumentsAndReturnsAcceptedReceipt` | **本仓库新增不变量**：即时回执只有 `thread_id` 与 `status`，不重复 prompt，也不提供 XML 或别名形状。 |
| （本仓库新增不变量：完成交付是运行时提醒形态，不是 USER 输入） | `ThreadProcessorIdleJoinDeliveryTest#rapidChildCompletionMatchesJoinDeliversCustomMessageToParentAndSetsParentActive`、`ThreadJoinCompletionRendererTest#rendersCompletedMessageWithPromptAndReport`、`HarnessRuntimeResponseMapperTest#projectsWaitingChildrenParentAndQueuedThreadAsProcessing` | **本仓库新增不变量**：未匹配的 join 不产生任何消息；父收到的完成消息是 USER 角色、`<system-reminder>` 包裹的 CUSTOM_MESSAGE，因此既进入模型上下文，又不被当成真实用户输入、也不会唤醒已停止的父。 |

### 9. 状态投影与持久化清理（Status Projection & Durability）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-injection.test.ts`: "injects \`task\` for an agent with subagents while below maxDepth"、"withholds \`task\` when depth has reached maxDepth"、"filters unknown subagents at load time so task is never injected for them" | `SubagentTaskRunnerTest#rejectsUnauthorizedSubagentTypeWithAvailableNames`、`SubagentTaskRunnerTest#rejectsResumingAThreadOfAnotherParent`、`PostgresqlJoinAcceptanceRollbackTest#globalJoinAdmissionSerializesConcurrentNewSessionsAcrossRootTreesAndEnforcesCap` | **等价**：深度与 subagent allowlist 由 join 接纳边界与永久父子关系强制，未授权或断链的委派在接纳阶段即被拒绝。 |
| `subagent-runner.test.ts`: "propagates a parent abort to already-running descendant subagents in the same tree" | `HarnessRuntimeStopRecursivePropagationTest#threeLevelHierarchyIsStoppedRecursivelyInSingleTransaction`、`HarnessRuntimeStopRecursivePropagationTest#recursiveStopStopsDescendantsRegardlessOfNoJoinOrPendingJoin`、`ThreadProcessorIdleJoinDeliveryTest#settleStoppedTreeTransitionsActiveThreadToIdle` | **等价**：停止在同一事务内传播到全部永久执行后代，子树由叶到根收敛；未交付 join 只冻结结果、绝不投递。 |
| `subagent-task-injection.test.ts`: "injects task instructions and available subagents into the system prompt when delegating"、"uses the global maxTurns setting when the project does not override it" | `TaskToolTest#exposesCanonicalDescriptorContract`、`TaskToolTest#handlesOptionalMaxTurnsAndThreadId`、`ThreadProcessorSoftBudgetTest#continueBoundaryAtMaxTurnsEnqueuesSystemReminderAndAdvancesReminderTurn` | **等价**：`task` 的 descriptor 与 System Prompt 声明可用 subagent 及默认预算；调用未给 `max_turns` 时按当前 policy 默认预算生效，软预算提醒只在确定继续运行的边界入队。 |
| `subagent-foundation.test.ts`: "tracks nodes, children, and running counts"（父等待子树时的 processing 投影为本仓库新增） | `HarnessRuntimeResponseMapperTest#projectsEveryReachableRuntimeStatusFromThreadSnapshot`、`HarnessRuntimeResponseMapperTest#projectsWaitingChildrenParentAndQueuedThreadAsProcessing`、`StudioHarnessThreadControllerTest#snapshotProjectsWaitingChildrenStatusAndParentThreadId`、`HarnessOwnerQueryServiceTest#threadSummaryProcessingAggregatesPendingDelegatedWork`、`IssueReconcilerIntegrationTest#pendingDelegatedChildThreadDefersRunCompletionUntilSettled` | **语义扩展**：`processing` 直接来自递归生命周期投影（`WAITING_CHILDREN` 与 `QUEUED` 都是处理中），不由「未交付 join 数量」推测；Run 的安全静止点同样只认持久子树状态。 |
| `subagent-foundation.test.ts`: "removes one root snapshot without affecting other roots"、"emits change events on upsert/update/remove"、"returns copies so external mutation cannot corrupt state" | `HarnessStoreJoinContract#deleteThreadsRejectsParentWhenSurvivingChildExists`、`HarnessStoreJoinContract#deleteThreadsSucceedsDeletingHierarchyInSingleBatch`、`HarnessStoreJoinContract#fullCleanupValidDeliveredChildWithParentAndMultiSessionMultiTree`、`SessionDeletionOrchestratorTest#deletesTaskChildSessionsAndParentlessForksInTheSameTree`、`PostgresqlJoinAcceptanceRollbackTest#garbageCollectionAndFullLifecyclePostgresVerification` | **语义扩展**：摒弃易被外部修改污染的内存 registry；删除按树锁排序并拒绝静默 CASCADE 关键 receipt 引用。 |
| `subagent-persisted-view.test.ts`: "marks a persisted length-truncated terminal response as an error"、"selects by file name before parsing persisted transcripts"、"does not mistake a longer underscore-suffixed session id for an exact match" | `HarnessStoreJoinContract#insertAndFindJoinRoundTripWithParent`、`HarnessStoreJoinContract#findJoinNonExistentReturnsEmpty`、`ThreadJoinProjectorTest#failedTurnWithErrorPayloadOnlyProducesNullPartialResultAndDurableError` | **语义收窄**：回执按 `invocationId` 主键精确查找与投影，不遍历文件系统、也不按前缀猜测身份；join 不存在就是空结果，绝不回退到同前缀记录。 |
| `subagent-runner.test.ts`: "publishes only the read-only view while the runner owns the live session"（恢复面在 durable work 为本仓库新增） | `ThreadJoinDelegationPostgresIntegrationTest#acceptedWorkIsRecoveredFromDurableRowsWithoutAnyNotification`、`PostgresqlJoinAcceptanceRollbackTest#concurrentAcceptCommandsAndJoinOnSameTreeSerializesViaTreeLockOnPostgres` | **本仓库新增不变量**：接受阶段不依赖任何通知；重启后 dispatcher 仅凭 durable work 行与 join 事实完成整条链路。 |
| （本仓库新增不变量：并发停止与交付的确定性） | `PostgresqlParentStopChildJoinConcurrencyTest#concurrentParentStopAndChildTerminalSerializeDeterministicallyWithoutDuplicateDelivery`、`PostgresqlParentStopChildJoinConcurrencyTest#advisoryTreeLockBlocksConcurrentChildProcessorUntilParentStopCommitsOnPostgres`、`PostgresqlParentStopChildJoinConcurrencyTest#abortedChildTerminalTransactionRollsBackEntirelyLeavingPostgresConsistent`、`PostgresqlParentStopChildJoinConcurrencyTest#staleVersionParentStopRollsBackEntireTransactionLeavingTreeUntouchedOnPostgres`、`HarnessRuntimeStopRecursivePropagationTest#treeAndRowLocksFollowCanonicalOrderAcrossAllDescendants` | **本仓库新增不变量**：真实 PostgreSQL 下树锁与行锁按声明顺序取锁，停止与交付竞争不产生重复交付或半提交。 |

## 有意不迁移的 pi-base 语义

以下用例属于 `pi-base` 作为终端交互式 CLI/TUI 特有的机制，在 kk-studio 的服务端/平台化架构中刻意不
实现；本节只记录不迁移的原因与替代路径，不用 Java 断言伪造覆盖。这些 `pi-base` 侧用例在本地没有对应断言，
替代路径由「分组映射表」的用例承接，运行入口见「运行方式」。

### 终端 UI 状态栏与计数器（`subagent-widget.test.ts`）

- **涉及用例**：`returns undefined when nothing is running`、`lists only running subagents as a true parent/child tree`、`shows zero live counters before a running subagent reports progress`、`truncates the latest activity to keep every tree node on one physical line`、`wires the registry to the root session widget and isolates foreign roots`、`clears the widget and cancels queued renders on session shutdown`。
- **不迁移原因**：终端单行状态栏的 ANSI 截断与即时计数器；kk-studio 的活动状态由递归生命周期投影经 Web API 暴露给前端 React 组件渲染。

### TUI 命令行与全屏覆盖层（`subagent-command.test.ts`）

- **涉及用例**：`always selects before opening a running session`、`routes fullscreen PageUp through TuiAltScreen to the focused session panel`、`does not rewrite fullscreen bindings in regular TUI mode`、`restores fullscreen bindings if session panel construction fails`、`restores bindings when an explicit live target disappears before the overlay mounts`、`rejects unsupported contexts and reports empty or missing selections without opening an overlay`、`reports ambiguous persisted session prefixes`、`opens the persisted transcript when a selected running session finishes`、`prevents concurrent viewer overlays and allows reopening after close`、`opens a completed persisted session directly by explicit id`。
- **不迁移原因**：`pi-base` 专有的 `/subagents` TUI 命令与全屏视图切换；kk-studio 的子会话就是标准 durable Thread，前端经 `/threads/:threadId` 独立面板查看。

### TUI 会话详情面板（`subagent-session-panel.test.ts`）

- **涉及用例**：`renders live assistant text and tool execution with the main Pi components`、`replays completed parallel tools when opened while a sibling is still running`、`settles a pending tool from its persisted result when the execution-end event was missed`、`rebuilds persisted and active tool state, then handles live error and navigation events`、`stops following the tail while scrolling and cleans up on close`、`uses configured fullscreen viewport keys for page and edge navigation`、`keeps following new output when top is pressed before scrolling is possible`、`preserves regular-mode Home behavior on a short transcript`。
- **不迁移原因**：终端面板的滚动跟踪与按键绑定；kk-studio 的 Thread 面板基于 Entry 历史投影与 WebSocket 实时流。

### 终端交互式权限审批中继（`subagent-permission-relay.test.ts` 与 `subagent-foundation.test.ts` 的权限宿主部分）

- **涉及用例**：`uses the same Yes/No actions as the normal permission prompt`、`replaces the previous root host on repeated session starts`、`discards an old root decision that resolves after the host is replaced`、`returns null when no host is registered`、`forwards to the registered root host and clears by identity`。
- **不迁移原因**：kk-studio 的工具审批由 Thread 上的 durable 审批记录与 YOLO 策略管理，不把子 Agent 请求冒泡到终端弹窗。

### 终端 Tool Call 展开/折叠渲染（`subagent-task-tool.test.ts`）

- **涉及用例**：`renders only the task command and prompt while running regardless of expansion`、`uses configured collapsed task result budgets until expanded`、`keeps task errors visible when successful result previews are disabled`、`does not offer expansion when a disabled task preview shows the complete error`、`renders partial failed output after the error from a raw task envelope`、`keeps child progress out of task partial updates while updating the registry`。
- **不迁移原因**：终端渲染专有的展开/折叠预算截断；kk-studio 的完成消息由 `ThreadJoinCompletionRenderer` 生成完整 XML，展示与截断由前端渲染层统一处理。

### Node.js 扩展绑定与 symlink 隔离（`subagent-real-factory.test.ts`）

- **涉及用例**：`rejects a symlinked path that would load a second pi-base module instance`、`fails fast and disposes when the child loader contains %s`、`does not mutate a persisted session when resume fails the extension identity check`、`applies a child-only model retry override through an isolated settings manager`、`inherits Pi retry settings when no child retry override is configured`、`disposes a child session when extension binding fails`。
- **不迁移原因**：`pi-base` 特有的 Node CJS/ESM 模块实例混用防范与 SettingsManager 分层；kk-studio 基于 Spring Boot 进程级依赖管理与 Session 隔离。模型重试策略由 Provider/Processor 配置统一给出，不按子会话分层。

### CLI 启动参数与向后兼容（`subagent-integration.test.ts` & `subagent-runner.test.ts`）

- **涉及用例**：`uses --agent startup selection to drive a delegating agent and execute task`、`keeps backward compatibility with legacy tool-use block aliases`、`emits live progress, updates widget counters, and ignores registration hook failures`。
- **不迁移原因**：kk-studio 没有 CLI `--agent` 启动入口（Agent 由 Platform API / Issue 驱动），不保留旧 `tool_use` 别名，也没有终端进度计数器。

## 已知限制

- **真实模型不参与委派验证。** 委派链路的自动化证据来自真实 Runtime + 真实 dispatcher/Processor + 真实
  PostgreSQL（模型用回显替身）；模型是否愿意调用 `task`、以及真实 provider 下的流式行为，只有显式付费开关的
  E2E 用例覆盖，因此本页不对「模型一定委派」下结论。
- **join 只有接受与交付两个边界，没有独立状态枚举。** 因此不存在「结算中间态」相关的用例；任何在
  匹配与交付之间观察状态的做法都被设计排除。
- **树锁协议依赖应用层顺序。** 锁序与树锁前置只在真实 PostgreSQL 契约测试中被证明是可用的，而不是数据库
  自身能强制的约束。
- **实现与文档的差异需人工确认。** 映射表引用的是测试类与方法名；若方法被重命名，请以测试代码为准更新本页。
