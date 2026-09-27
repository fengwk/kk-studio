# Builtin Task 测试映射与不变量验证

本文档面向维护本仓库**异步 Task 委派（Subagent Delegation）**能力的开发者，梳理从只读参考实现（`pi-base`）到本仓库当前架构的不变量映射关系、测试分布与刻意不适用的设计差异。

---

## 架构差异与心智模型

`pi-base` 与 `kk-studio` 在 Subagent 委派上的根本分歧源于**执行与交互模型**：

```
[pi-base: 同步阻塞]
Parent Turn ──(task call)──► [Runner 阻塞等待子会话运行] ──(tool_result 含最终报告)──► Parent 继续
                                      │ (实时 Watchdog / 内存 Registry / TUI Widget)

[kk-studio: 严格异步持久接受 + 两段状态机结算]
Parent Turn ──(task call)──► [TaskTool / SubagentTaskRunner] ──(单事务入库 OPEN) ──► 立即回执 (JSON {"thread_id","status":"accepted"})
                                                                                          │ (父本地 turn 结束，整体仍非空闲)
                             [后台结算扫描器 SubagentTaskSettlementScanner] ◄──────────────┘
                                  │
                                  ├─ 1. 子执行结清 ──(CAS)──► SETTLED (持久化 outcome/report/error，释放并发额度)
                                  │
                                  └─ 2. 父未停止 ──(同事务)──► DELIVERED + 父 Thread 注入 CUSTOM_MESSAGE (<task>...)
```

### 核心设计差异

1. **单事务持久接受（Durable Accept）**：
   - `pi-base`：`task` 工具在调用期间同步挂起当前 Turn，阻塞等待子 Session 执行完毕，直接在本次 `tool_result` 中交付 XML 包裹的最终结果。
   - `kk-studio`：`TaskTool.execute` 仅完成参数解析与持久接受，单事务内完成子 Thread 创建/继续与未结清委派记录（`harness_subagent_task`，状态 `OPEN`）写入，立即向父 Agent 返回唯一 JSON `{"thread_id":"...","status":"accepted"}` 的即时回执（`SubagentTaskMessages.accepted`），不重复 prompt，不等待子执行结束，不占用父 Agent 阻塞线程。
2. **两段状态机（`OPEN -> SETTLED -> DELIVERED`）**：
   - `OPEN`：子执行（含子树）正在运行，占用父级与树级并发额度，并且**无条件**计入子树活动——即使该 Thread 自身或其中间父已停止，运行中的执行仍是真实工作：停止尚未被确认传播到后代之前，绝不能把后代当成已经安全停止。
   - `SETTLED`：子执行结清后，终态（`COMPLETED` / `ERROR` / `CANCELLED`）、完整报告、部分结果、错误信息以及 `settledAt` 通过 CAS 持久化在 `harness_subagent_task` 表中；此时子执行不再占用并发额度，且子历史后续继续或被删除都不会篡改已终结的结果。记录在 SETTLED 后尚未注入父 Thread 时，按父状态分两种形态：父未停止是**待交付（pending）**，计入子树活动，扫描器在有限轮次内完成交付；父已显式停止是**停止挂起（stopped）**，扫描器既不交付也不唤醒父，也不计入子树活动（不占处理能力，不让 owner/Issue 永远 `processing`）。
   - `DELIVERED`：后台结算扫描器 `SubagentTaskSettlementScanner` 在同一 store 事务内，将结果作为一条独立 `CUSTOM_MESSAGE` 注入父 Thread，内容为 `SubagentTaskMessages.completion` 渲染的完成包络 `<subagent_result thread_id="..." agent="..." state="...">`，内含本次任务原文 `<task>` 与结果 `<result>`（失败/取消时是 `<error>` 与可选的 `<partial_result>`），并将任务状态推进为 `DELIVERED`。
3. **停止传播与停止门禁（Stop Gating）**：
   - 父 Thread 处于显式停止态（`STOPPED`）时，扫描器绝不注入结果或提醒（**不唤醒父**），而是将停止有界且幂等地传播给未结清子执行；结果仍安全保留在 `SETTLED` 状态，待父 Thread 恢复后再由扫描器交付。
   - 停止必须**先传播到后代**才允许把这次执行当成已结束：子执行自身被停止/取消但其子树里还有 `OPEN` 执行时，扫描器继续幂等传播停止并继续等待，绝不在子树未静止时结算。
   - **未交付 ≠ 有活动**：父停止后待交付记录依然存在（durable fact），但 `SubagentTaskActivity` 不再把"父已停止的挂起结果"算作活动，因此停止的父不会被自动唤醒；这与父存活时的 pending（必须计入活动、必须在有限轮次内交付）是两种处理。子树内任何 `OPEN` 执行则一律计入活动，与祖先停止状态无关。
   - **加锁边界（已知限制）**：交付路径的父停止门禁在持有父 Thread 锁的事务内原子完成；提醒路径的目标是子 Thread，Harness Store 的锁 rank 单调（Session 先于 Thread）与 Thread 锁按 id 升序规则都不允许再取父锁，因此它的父停止判定只是事务内快照读，属于软预算的尽力而为——父停止与提醒之间的竞态窗口由停止传播在有界轮次内收敛，这里不声称原子性。
4. **并发额度以持久记录为权威**：
   - `pi-base` 依赖内存注册表计数；`kk-studio` 以数据库持久记录为唯一权威。在接受事务内通过 PostgreSQL advisory 锁（`lockQuota`）将父级（`maxConcurrency`）与树级（`maxTotalConcurrency`）配额检查与插入串行化，彻底消除并发委派越限。
5. **软预算提醒机制**：
   - `pi-base` 在 tool call 同步等待循环内 steer 子 Agent；`kk-studio` 由后台扫描器周期性检测子 Thread 轮数，超过 `maxTurns` 阈值（及每递增 5 轮）且父未停止时，向子 Thread 注入包含 `subagent-max-turns.md` 的 `SystemReminder`，引导子代理自主返回阶段报告。
6. **嵌套委派与活动聚合**：
   - 当子 Thread 自身 Turn 结束但其子树仍有未结清委派时，本次执行不判定为结清（等待子树完成唤醒后的最终结果）；`SubagentTaskActivity` 统一聚合子树未交付状态（子树内任何 `OPEN` 执行无条件构成活动；`SETTLED` 只在直接父未停止时构成活动），确保父 Thread 在等待子结果时对外呈现 `processing`，避免 Issue 推进与 UI 误判空闲。

---

## 分组映射表

以下各表以 pi-base 测试库存为基线（`pi-base@1cdaec04`，`tests/subagent-*.test.ts` 共 12 个文件、128 条声明）：83 条映射到本仓库当前生效的测试，45 条属于终端专有语义并在「刻意不实现」逐条说明理由。标记为「本仓库新增不变量」的行没有对应的 pi-base 用例：它们属于持久接受 + 两段状态机引入的新契约（pi-base 的同步阻塞模型不存在这些失败面），保留在此是为了让维护者看到该契约的测试落点。

### 1. 委派接受与幂等重试（Acceptance & Idempotency）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-tool.test.ts`: "delegates a valid task and returns the completed envelope" | `TaskToolTest#acceptsValidArgumentsAndReturnsAcceptedReceipt`、`SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution` | **语义扩展**：pi-base 同步阻塞等待并返回完成包络；本仓库拆分为单事务持久接受返回 accepted 回执，结果由后台扫描器独立交付。 |
| `subagent-task-tool.test.ts`: "refreshes the Agent catalog before validating and resuming a task" | `SubagentTaskRunnerTest#continueChildConvergesSettingsThenQueuesPrompt`、`SubagentTaskRunnerTest#rejectsSubagentTypeNotAllowed` | **等价**：继续委派时动态读取目标 Agent 最新配置并收敛 settings。 |
| `subagent-task-tool.test.ts`: "advertises the configured default max_turns in the parameter schema"、"describes batching all ready independent delegations in one assistant turn" | `TaskToolTest#exposesCanonicalDescriptorContract` | **等价**：参数 schema 与 System Prompt 声明批量委派与默认预算契约。 |
| `subagent-task-tool.test.ts`: "uses task max_turns to override the configured budget" | `TaskToolTest#acceptsValidArgumentsAndReturnsAcceptedReceipt`、`SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution` | **等价**：单次调用参数覆盖全局/策略默认预算。 |
| `subagent-task-tool.test.ts`: "refuses concurrent resumes before the first session finishes opening"、"refuses to resume a session that is currently running" | `SubagentTaskRunnerTest#rejectsWhenChildThreadAlreadyHasOpenExecution`、`PostgresqlSubagentTaskRepositoryPostgresTest#childOpenUniqueConstraintEnforcedAndDeliveredPermitsReopening` | **语义扩展**：由数据库部分唯一索引 `uk_harness_subagent_task_child_open` 物理互斥，同一子 Thread 任一时刻至多一行 OPEN 记录。 |
| `subagent-runner.test.ts`: "spawns, marks running then done, and returns the report + session id" | `SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution`、`SubagentTaskSettlementScannerTest#terminalChildExecutionIsSettledThenDelivered` | **语义扩展**：生命周期拆分为持久接受入库与后台扫描结算交付两个阶段。 |
| `subagent-runner.test.ts`: "returns error state (not throwing) when the subagent prompt fails"、"returns an error result when the session cannot even be created" | `TaskToolTest#catchesRunnerExceptionsAndCompletesListenerWithError`、`SubagentTaskSettlementScannerTest#deletedChildThreadIsSettledAsExplicitError` | **等价**：接受失败或运行时异常收敛为错误 ToolResult / 错误终态，绝不逃逸未捕获异常。 |
| `subagent-task-tool.test.ts`（本仓库新增不变量：持久接受以 invocation 为幂等键） | `TaskToolTest#reportsReplayedAcceptanceOnIdempotentRetry`、`SubagentTaskRunnerTest#replayedInvocationReturnsPersistedChildWithoutAcceptingAgain`、`PostgresqlSubagentTaskRepositoryPostgresTest#insertAndFindByInvocationIdRoundtrip` | **语义扩展**：以 `invocationId` 为主键，重复调用先查持久记录，命中即返回既有子 Thread，不重复开启执行。 |

### 2. 子身份派生与环境/会话隔离（Identity Derivation & Isolation）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-real-factory.test.ts`: "persists agent/depth/root metadata before binding an isolated child session"、"creates a new child with the target agent model and thinking level before binding" | `SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution`、`SubagentTaskRunnerTest#childIdentityIsStableAndDerivedFromInvocation` | **等价**：子 Session 创建时携带 `SubagentContext` 冻结父 Thread、根 Thread、invocationId 和 depth，并注入独立 Agent settings。 |
| `subagent-real-factory.test.ts`: "reopens matching legacy session files and refreshes the requested agent type on resume" | `SubagentTaskRunnerTest#continueChildConvergesSettingsThenQueuesPrompt`、`SubagentTaskRunnerTest#continueChildSendsOnlyPromptWhenSettingsAlreadyMatch` | **等价**：继续既有子 Thread 时按目标 Agent 的 settings 差量发送 `SET_AGENT -> SET_MODEL -> SET_ENVIRONMENT` 收敛配置。 |
| （本仓库新增不变量：投影竞态与丢失的交付竞争） | `SubagentTaskDelegationPostgresIntegrationTest#staleTerminalNeverSettlesWhenTheChildExecutionAdvancesUnderTheScan`、`SubagentTaskDelegationPostgresIntegrationTest#stopBoundaryWinningTheDeliveryRaceNeverFakesDelivery` | **本仓库新增不变量**：终态是从锁外的子快照投影出来的，锁定复核发现 `version`/`head`/`nextCommandSequence` 已漂移时绝不结算（用确定性 barrier 制造竞态，下一轮以最新事实交付）；交付竞争中停止边界先行时 preflight 让事务整体回滚——队列 0 条、记录保持 `SETTLED`，绝不把丢失的交付修补成 `DELIVERED`。 |
| `subagent-real-factory.test.ts`: "throws a clear error when no persisted session file matches the requested id" | `SubagentTaskRunnerTest#continueChildRejectsThreadOwnedByAnotherParent` | **等价**：查找不到子 Thread 或归属不符时明确拒绝。 |
| `subagent-real-factory.test.ts`: "retains parallel tool results until source-ordered messages become visible" | `SubagentTaskRunnerTest#quotaLockAndCountingPrecedeTaskRecordInsert` | **语义收窄**：Harness Runtime 自身保证 Entry 顺序与 tool_result 事务原子追加，Task 只关注自身接受命令入队。 |
| `subagent-runner.test.ts`: "uses a hashed cwd-derived directory name to avoid lexical path collisions" | `SubagentTaskRunnerTest#childIdentityIsStableAndDerivedFromInvocation` | **语义收窄**：子身份由 UUID namespace + `invocationId` 稳定派生 UUID v3，环境隔离由 `BoundEnvironment` 承接。 |

### 3. 归属与静止态校验（Ownership & Quiescence Validation）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-foundation.test.ts`: "defaults the root session id to the current session and restores persisted child roots"、"ignores malformed root-session entries" | `SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution`、`SubagentTaskRunnerTest#nestedQuotaUsesRootThreadOfDelegationTree` | **等价**：根 Session/Thread 身份沿委派链透明传递与冻结。 |
| `subagent-runner.test.ts`: "preserves the caller's persisted root-session id in registry nodes" | `SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution` | **等价**：子 Session 的 `SubagentContext` 准确持久化 `rootThreadId`。 |
| `subagent-runner.test.ts`: "passes the current child depth when resuming through a new delegation layer"、`subagent-task-tool.test.ts`: "passes childDepth = parent depth + 1" | `SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution`、`SubagentTaskRunnerTest#rejectsDelegationBeyondMaxDepth` | **等价**：深度严格等于 parent depth + 1，达 `maxDepth` 拒绝。 |
| `subagent-foundation.test.ts`: "defaults to root depth when no depth entry exists"、"reads the latest depth entry and treats deeper sessions as non-root"、"ignores malformed depth values" | `SubagentTaskRunnerTest#newChildAcceptsDurableTaskAndRecordsOpenExecution`、`SubagentTaskRunnerTest#nestedQuotaUsesRootThreadOfDelegationTree` | **语义收窄**：pi-base 解析历史条目里的 depth 并容错畸形值；本仓库在创建子 Thread 时把 depth 冻结进 `SubagentContext`，畸形值不可能出现，深会话/非根判定改由根 Thread 归属表达。 |
| `subagent-task-tool.test.ts`: "refuses to resume a session that is currently running" | `SubagentTaskRunnerTest#continueChildRejectsNonQuiescentThread`、`SubagentTaskRunnerTest#continueChildRejectsThreadOwnedByAnotherParent` | **语义扩展**：严格校验目标子 Thread 归属当前父与当前根，且必须处于静止态（无排队命令、无活跃 Model、无 Tool siblings、非 continuation）。 |

### 4. 并发额度与事务级配额锁（Concurrency Quotas & Quota Locking）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-tool.test.ts`: "enforces the per-session concurrency cap for new spawns"、"enforces maxConcurrency across parallel task calls in the same turn"、"applies maxConcurrency to resumed subagent sessions too" | `SubagentTaskRunnerTest#parentConcurrencyQuotaRejectsBeforeAnyTaskRecord`、`SubagentTaskRunnerTest#quotaLockAndCountingPrecedeTaskRecordInsert`、`PostgresqlSubagentTaskRepositoryPostgresTest#countOpenByParentAndRootThreadIdFiltersOnlyOpenStatus`、`SubagentTaskDelegationPostgresIntegrationTest#concurrentChildAcceptanceSerializesQuotaAndRollsBackLosers` | **语义扩展**：pi-base 依赖内存计数；本仓库在接受事务内通过 PostgreSQL advisory 锁 `lockQuota` 串行化父/根配额检查，以持久 `OPEN` 记录为权威。 |
| `subagent-task-tool.test.ts`: "enforces maxTotalConcurrency across the whole root delegation tree"、"enforces maxTotalConcurrency across parallel starts under the same root" | `SubagentTaskRunnerTest#totalConcurrencyQuotaRejectsBeforeAnyTaskRecord`、`SubagentTaskRunnerTest#nestedQuotaUsesRootThreadOfDelegationTree`、`PostgresqlSubagentTaskRepositoryPostgresTest#lockQuotaAppliesAdvisoryLockInTransactionAndReleasesOnCommit` | **语义扩展**：树级总量并发配额在根 Thread 粒度加锁并统计持久 `OPEN` 行。 |
| `subagent-config.test.ts`: "applies defaults when no subagent config is present"、"reads explicit project overrides"、"fills only the missing field with its default"、"treats idleTimeoutMs=0 as disabled"、"preserves modelMaxRetries=0 so delegated model retries can be disabled"、"rejects non-positive maxDepth at load time"、"rejects non-integer maxConcurrency and maxTotalConcurrency at load time"、"rejects negative idleTimeoutMs/modelMaxRetries and non-positive maxTurns at load time" | `SubagentConfigTest#preservesValidValues`、`SubagentConfigTest#rejectsNegativeMaxTotalConcurrency`、`SubagentConfigTest#rejectsNonPositiveCoreBudgets`、`SubagentConfigTest#rejectsInvalidIdleTimeout`、`SubagentConfigTest#exposesCanonicalErrorMessages` | **等价**：配置模型校验核心预算正数、非负总并发、毫秒整倍数空闲超时。 |

### 5. 终态推导与投影（Terminal State Projection）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "uses the final assistant error state even when prompt resolves normally"、"reports a length-truncated terminal response as an error with partial output"、"clears a transient assistant error after a successful retry" | `SubagentTaskTerminalProjectionTest#completedExecutionProjectsLastAssistantReport`、`SubagentTaskTerminalProjectionTest#failedExecutionKeepsErrorAndPartialReportSeparate` | **等价**：从子 Thread 快照推导终态：`TurnEndOutcome.COMPLETED` 投影为 COMPLETED + report；`FAILED` 投影为 ERROR + error + partialResult。 |
| `subagent-runner.test.ts`: "does not expose text from an interrupted assistant turn that contains tool calls"、"bounds interrupted output before returning the structured task result" | `SubagentTaskTerminalProjectionTest#stoppedExecutionKeepsLastSafeAssistantText`、`SubagentTaskTerminalProjectionTest#runningExecutionHasNoTerminal` | **语义收窄**：从 Entry 链提取最后一段安全的 Assistant 文本，未到 `TURN_END` 终界前不产生 Terminal。 |
| `subagent-persisted-view.test.ts`: "marks a persisted length-truncated terminal response as an error"、"selects by file name before parsing persisted transcripts"、"does not mistake a longer underscore-suffixed session id for an exact match" | `SubagentTaskTerminalProjectionTest#failedExecutionKeepsErrorAndPartialReportSeparate`、`PostgresqlSubagentTaskRepositoryTest#findByInvocationIdMapsRowAndMissingRow` | **语义收窄**：基于 PostgreSQL 关系表主键精准查找，不遍历文件系统 JSONL。 |
| `subagent-runner.test.ts`: "does not create or resume a session when cancellation already happened"（边界检测为本仓库新增） | `SubagentTaskTerminalProjectionTest#abortedBeforeStartRequiresQuiescenceAndNoNewEntries`、`SubagentTaskSettlementScannerTest#abortedBeforeStartExecutionIsSettledAsCancelled` | **语义扩展**：识别静止且执行边界后无条目的取消委派，结算为 CANCELLED，防止记录永久停在 OPEN 导致假性永不静止。 |
| （本仓库新增不变量：显式 idle Stop 必须留下 durable 停止边界） | `HarnessRuntimeStopIdleTest#idleStopPersistsStopBarrierTurnAndBumpsVersionOnce`、`#idleWithQueuedCommandsCancelsAllAtOneNowAndBumpsVersionExactlyOnce`、`#ownOpenTurnWithoutLiveExecutionIsClosedInsideItself`、`#ownOpenTurnWithCompleteAssistantResultIsClosedByReusingIt`、`#ownOpenInputTurnWithoutInputIsClosedAsHistoryCutBeforeTheStopBoundary`、`#ownOpenTurnWithUnansweredToolCallIsClosedAsHistoryCutWithoutFakingCompletion`、`#foreignOpenTurnStopOnlyCancelsCommandsWithoutWritingAMarker`、`HarnessRuntimeStopReplayTest#repeatedIdleStopWithTheSameKeyReplaysItsOwnStopBarrierTurn`、`#foreignOwnedIdleStopReplaysItsQueuedOnlyReceipt`、`HarnessStoreEntryTreeContract#stopBarrierTurnRoundTripsThroughTheStore`、`EntryPathTest#acceptsStopBarrierTurnAndUnchangedFollowingTurns`、`#rejectsStopTurnShapeViolations`、`SubagentTaskDelegationPostgresIntegrationTest#idleStopOfParentIsDurableAndDefersDeliveryUntilHeadAdvances`、`#idleStopPropagatesToRunningChildAndDeliversCancelledExactlyOnceAfterResume`、`SubagentTaskTerminalProjectionTest#turnsAreCountedAfterBoundaryExcludingCompactionAndStop` | **本仓库新增不变量**：`TurnStartReason.STOP` 只在 Stop 事务内完整写入（唯一 `CANCELLED` 取消屏障 + `STOPPED`/`closeRequestId` 的 `TURN_END`），不消费 Command、不调度模型也不计入模型工作轮数；本线程的 open Turn 若无 live Invocation，能按 `STOPPED` 关闭就复用已有完整 assistant 结果（不重复 assistant），否则按 history cut 收尾后再写停止边界；open Turn 属其它线程时本线程没有自己的停止边界可写、只取消排队 Command。 |

### 6. 结果结算、交付与同事务原子推进（Settlement & Atomic Delivery）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "spawns, marks running then done, and returns the report + session id"（结果交付部分） | `SubagentTaskSettlementScannerTest#terminalChildExecutionIsSettledThenDelivered`、`PostgresqlSubagentTaskRepositoryPostgresTest#settleResultCasTransitionsOpenToSettledAndRejectsRepeatedOrDelivered`、`PostgresqlSubagentTaskRepositoryPostgresTest#markDeliveredCasTransitionsSettledToDeliveredAndRejectsRepeatedOrOpen` | **语义扩展**：两段状态机：子执行结清 CAS 推进为 SETTLED 并持久化结果；随后作为父 `CUSTOM_MESSAGE` 入队并在同一事务内 CAS 推进为 DELIVERED。 |
| `subagent-runner.test.ts`: "returns the last assistant text and counts Pi toolCall blocks"、`subagent-task-tool.test.ts`: "returns interrupted assistant text together with the terminal error" | `SubagentTaskTerminalProjectionTest#completedExecutionProjectsLastAssistantReport`、`SubagentTaskTerminalProjectionTest#runningExecutionHasNoTerminal`、`SubagentTaskTerminalProjectionTest#turnsAreCountedAfterBoundaryExcludingCompaction` | **语义收窄**：只从执行边界之后的 Entry 链提取最后一段安全文本，并按非压缩 turn 计数，工具调用块不单独计一轮。 |
| `subagent-runner.test.ts`（本仓库新增不变量：交付竞争、稳定幂等键与重放确认） | `SubagentTaskSettlementScannerTest#deliveryPreflightMarksDeliveredInSameTransaction`、`SubagentTaskSettlementScannerTest#replayedDeliveryConfirmsDeliveredStatus`、`SubagentTaskSettlementScannerTest#settleResultCasMissNeverDelivers`、`SubagentTaskSettlementScannerTest#lostRaceWithoutConvergenceIsReportedInsteadOfSwallowed` | **语义扩展**：交付命令携带稳定幂等键，preflight 在事务内原子校验并推进状态，重放确认已交付状态。 |
| `subagent-real-factory.test.ts`: "throws a clear error when no persisted session file matches the requested id"（父级联删除的保守结清为本仓库新增） | `SubagentTaskSettlementScannerTest#deletedChildThreadIsSettledAsExplicitError`、`SubagentTaskSettlementScannerTest#missingParentThreadIsSettledConservatively`、`SubagentTaskSettlementScannerTest#deliverySkipsParentThatNoLongerExists` | **语义扩展**：子 Thread 缺失结算为明确 ERROR 并通知父；父 Thread 缺失保守结清释放配额。 |

### 7. 停止传播与停止门禁（Stop Propagation & Gating）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "cascades parent-turn cancellation to the subagent and reports cancelled"、"propagates a parent abort to already-running descendant subagents in the same tree" | `SubagentTaskSettlementScannerTest#stoppedParentHoldsDeliveryAndPropagatesIdempotentStop`、`SubagentTaskSettlementScannerTest#stoppedParentKeepsSettledResultUndelivered`、`SubagentTaskDelegationPostgresIntegrationTest#stoppedParentDefersDeliveryUntilHeadAdvancesAndThenDeliversExactlyOnce`、`SubagentTaskDelegationPostgresIntegrationTest#concurrentStopBoundaryRaceKeepsDeliveryAtomic` | **语义扩展**：父停止时扫描器将停止幂等传播给未结清子执行，同时挂起交付，绝不唤醒已停止的父；真实 PostgreSQL 下验证交付与 head 前进/停止并发时 `DELIVERED` 与结果入队严格原子。 |
| `subagent-runner.test.ts`: "reports a terminal assistant abort as cancelled without a parent abort signal"、"treats a prompt that resolves after abort as cancelled instead of completed"、"keeps terminal response text when parent cancellation resolves the prompt" | `SubagentTaskTerminalProjectionTest#stoppedExecutionKeepsLastSafeAssistantText`、`SubagentTaskSettlementScannerTest#stoppedParentKeepsSettledResultUndelivered` | **等价**：子执行中止或取消时投影为 CANCELLED 终态，并完整保留已产生的部分输出。 |
| `subagent-runner.test.ts`: "does not create or resume a session when cancellation already happened"、"cancels while session creation is in flight and disposes a late session" | `SubagentTaskSettlementScannerTest#abortedBeforeStartExecutionIsSettledAsCancelled`、`SubagentTaskSettlementScannerTest#deliveryPreflightRejectsExplicitlyStoppedParent` | **等价**：在未启动或执行前被中止的委派结清为 CANCELLED，交付 preflight 拒绝向停止的父注入消息。 |
| `subagent-permission-relay.test.ts`: "stops waiting for the root permission UI when the subagent request is aborted"、"preserves a subagent host selector error when its signal is not aborted" | `SubagentTaskSettlementScannerTest#stoppedParentHoldsDeliveryAndPropagatesIdempotentStop` | **不适用+等价覆盖**：kk-studio 无 TUI permission relay 交互弹窗；停止传播由扫描器直接调用 `runtime.stop`。 |

### 8. 软预算与轮数提醒（Soft Budget & Turn Reminders）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "steers the child at the turn limit without hard-aborting it"、"does not steer a final-only assistant turn that exactly reaches maxTurns"、"re-steers every five effective tool-driving turns after maxTurns"、"does not count errored or aborted assistant messages toward maxTurns" | `SubagentTaskSettlementScannerTest#reminderIsSentOnceAtThresholdWithStableIdempotencyKey`、`SubagentTaskSettlementScannerTest#reminderIsSkippedForIdleChildAndBelowThreshold`、`SubagentTaskSettlementScannerTest#reminderPreflightRejectsStoppedParent`、`SubagentTaskTerminalProjectionTest#turnsAreCountedAfterBoundaryExcludingCompaction`、`PostgresqlSubagentTaskRepositoryPostgresTest#updateReminderTurnCasRequiresOpenStatusAndMatchingPreviousTurn` | **语义扩展**：pi-base 在 tool call 同步阻塞循环内 steer；本仓库由扫描器周期检测，达到 `maxTurns` 阈值且每 5 轮通过 `SystemReminder` 注入 `subagent-max-turns.md`，数据库 CAS 记录 `reminderTurn` 防止重复提醒，父停止时不注入。 |

### 9. 嵌套委派与活动聚合（Nested Delegation & Activity Aggregation）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-injection.test.ts`: "injects \`task\` for an agent with subagents while below maxDepth"、"withholds \`task\` when depth has reached maxDepth"、"filters unknown subagents at load time so task is never injected for them" | `SubagentTaskRunnerTest#rejectsDelegationBeyondMaxDepth`、`SubagentTaskRunnerTest#rejectsSubagentTypeNotAllowed`、`SubagentTaskRunnerTest#rejectsParentInvocationThatForbidsDelegation` | **等价**：深度限制与 subagent allowlist 校验。 |
| `subagent-runner.test.ts`: "propagates a parent abort to already-running descendant subagents in the same tree"（子树结算延迟为本仓库新增） | `SubagentTaskSettlementScannerTest#nestedDelegationDefersSettlementUntilSubtreeIsSettled` | **语义扩展**：子 Thread 自身到达 TURN_END 但其子树仍有未结清委派时延后结算，避免把中间态报告当最终结果。 |
| `subagent-foundation.test.ts`: "tracks nodes, children, and running counts"、"filters snapshots by root session" | `SubagentTaskActivityTest#openExecutionUnderLiveParentIsActivity`、`SubagentTaskActivityTest#settledPendingUnderLiveParentIsActivity`、`SubagentTaskActivityTest#settledPendingUnderStoppedParentIsNotActivity`、`SubagentTaskActivityTest#openExecutionIsActivityEvenWhenQueryThreadItselfIsStopped`、`SubagentTaskActivityTest#pureSettledHangOnStoppedSelfIsNotActivity`、`SubagentTaskActivityTest#subtreeWithoutUndeliveredDelegationIsNotActivity`、`PostgresqlSubagentTaskRepositoryPostgresTest#listSettledParentThreadIdsInSubtreeReturnsOnlyUndeliveredSettledParents`、`PostgresqlSubagentTaskRepositoryPostgresTest#hasOpenInSubtreeTraversesHierarchyAndIgnoresNonOpenStatuses` | **语义扩展**：由 `SubagentTaskActivity` 结合递归查询 `listUndeliveredParentThreadIdsInSubtree` 聚合子树活动，并严格排除已停止的父 Thread。 |
| `subagent-task-injection.test.ts`: "injects task instructions and available subagents into the system prompt when delegating"、"uses the global maxTurns setting when the project does not override it" | `TaskToolTest#exposesCanonicalDescriptorContract`、`SubagentTaskSettlementScannerTest#reminderFallsBackToConfiguredMaxTurnsWhenCallBudgetIsUnset` | **等价**：`task` 的 descriptor 与 System Prompt 声明可用 subagent 及默认预算；调用未给 `max_turns` 时按当前 policy 默认预算生效。 |
| `subagent-foundation.test.ts`: "tracks nodes, children, and running counts"（父本地 turn 结束后仍 processing 为本仓库新增） | `HarnessRuntimeResponseMapperTest#aggregatesPendingDelegatedWorkIntoProcessingWithoutChangingStatus`、`HarnessOwnerQueryServiceTest#threadSummaryProcessingAggregatesPendingDelegatedWork`、`IssueReconcilerIntegrationTest#pendingDelegatedTaskDefersRunCompletionUntilDelivery`、`StudioHarnessThreadControllerTest#snapshotKeepsProcessingWhileDelegatedWorkIsPending` | **语义扩展**：父 Thread 本地 turn 已结束，但整体仍非空闲（子树仍有未交付委派），因此 Thread 快照/摘要与 Issue 检查都以 `processing` 呈现聚合状态，避免误判静止；该聚合只计入父未停止的未交付委派（pending），父已停止的未交付记录（stopped）不产生 `processing`。 |

### 10. 公平扫描与存活/自愈（Fair Scanning & Resiliency）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-foundation.test.ts`: "removes one root snapshot without affecting other roots"、"emits change events on upsert/update/remove"、"returns copies so external mutation cannot corrupt state" | `SubagentTaskSettlementScannerTest#settleOnceRotatesFairCursorAndWrapsAtTail`、`SubagentTaskSettlementScannerTest#settleOnceIsolatesFailingRecords`、`PostgresqlSubagentTaskRepositoryPostgresTest#listUndeliveredAfterKeysetPaginatesConsistentlyAndExcludesDelivered` | **语义扩展**：摒弃易受外部修改污染的内存 registry，采用 `(created_at, invocation_id)` keyset 游标在数据库中做公平分页轮转，异常记录单条隔离不阻塞批次。 |
| （本仓库新增不变量：Runtime 未挂载时安全跳过，等待下一周期） | `SubagentTaskSettlementScannerTest#settleOnceReturnsZeroWhenRuntimeIsUnavailable`、`SubagentTaskActivityTest#unavailableRuntimeIsNotActivity` | **本仓库新增不变量**：Runtime 未挂载时安全跳过并记录，等待下一周期重新尝试；pi-base 的内存注册表不存在该失败面。 |

### 11. 消息渲染与 XML 文本契约（Message Rendering & XML Contracts）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-runner.test.ts`: "wraps a completed report and includes the session id for resume"、"renders a placeholder when the completed child produced no textual report"、"emits an error envelope with the session id for failures"、"includes a bounded partial report in a failed task envelope" | `SubagentTaskMessagesTest#rendersCompletedMessageWithPromptAndReport`、`SubagentTaskMessagesTest#rendersFailedMessageWithSeparatedErrorAndPartial`、`SubagentTaskMessagesTest#rendersCancelledMessage`、`SubagentTaskMessagesTest#fallsBackToPlaceholdersForMissingText` | **等价**：完成消息外层 `<subagent_result thread_id agent state>`，内含本次任务原文 `<task>` 与结果 `<result>`，失败/取消时分离 `<error>` 与 `<partial_result>`，缺失文本使用明确占位符。 |
| `subagent-runner.test.ts`: "escapes task attributes and closing-tag payloads in reports and errors" | `SubagentTaskMessagesTest#escapesAttributeAndBodyText` | **等价**：XML 5 大特殊字符（`& < > " '`）转义，防止标签闭合逃逸，且反向解码可逆。 |
| `subagent-runner.test.ts`: "wraps a completed report and includes the session id for resume"（不截断正文与回执省略 prompt 为本仓库新增契约） | `SubagentTaskMessagesTest#neverTruncatesLongBodies`、`SubagentTaskMessagesTest#acceptedReceiptOmitsPrompt`、`TaskToolTest#acceptsValidArgumentsAndReturnsAcceptedReceipt` | **语义扩展**：即时回执是唯一 JSON `{"thread_id","status":"accepted"}`，坚决不重复 prompt，且不提供 XML/别名形状；完成消息坚决不截断正文，长文本保留由平台资源化机制处理。 |

### 12. 参数校验与配置约束（Arguments Validation & Config Constraints）

| pi-base 用例名 | 本仓库对应的测试类#方法 | 映射关系 |
| --- | --- | --- |
| `subagent-task-tool.test.ts`: "marks the definition with the owning pi-base module instance"、"rejects missing required args"、"reports a missing subagent_type with the currently available agents"、"rejects an existing subagent_type outside the allowlist"、"rejects a non-positive task max_turns override" | `TaskToolTest#rejectsSchemaViolationsOnRequestCreation`、`TaskToolTest#rejectsSemanticViolationsWithDescriptiveErrors`、`SubagentTaskRunnerTest#rejectsSubagentTypeNotAllowed` | **等价**：必填字段、非法 JSON、非正整轮数、前后空格、非规范 UUID 严格校验并拒绝。 |
| `subagent-task-tool.test.ts`: "advertises the configured default max_turns in the parameter schema" | `TaskToolTest#handlesOptionalMaxTurnsAndThreadId`、`SubagentTaskRecordTest#draftAcceptsMinimalAndOptionalMaxTurns` | **等价**：缺省时为 null，走默认预算与新建任务。 |
| `subagent-runner.test.ts`: "aborts a subagent that stays idle past idleTimeoutMs"、"observes asynchronous abort failures from the idle watchdog"、"does not count long-running tool execution silence toward idleTimeoutMs" | `SubagentConfigTest#preservesValidValues`、`SubagentConfigTest#rejectsInvalidIdleTimeout` | **语义收窄**：保留 `idleTimeout` 配置校验，运行时空闲由 Runtime 统一的状态机与 Thread change source 驱动，不另设独立的 tool-level 看门狗线程。 |

---

## 刻意不实现的 pi-base 语义与替代机制

以下用例属于 `pi-base` 作为终端交互式 CLI/TUI 特有的机制，在 `kk-studio` 的服务端/平台化架构中**刻意不实现**：

### 1. 终端 UI 状态栏组件（`subagent-widget.test.ts`，共 6 条）

- **涉及用例**：
  - `returns undefined when nothing is running`
  - `lists only running subagents as a true parent/child tree`
  - `shows zero live counters before a running subagent reports progress`
  - `truncates the latest activity to keep every tree node on one physical line`
  - `wires the registry to the root session widget and isolates foreign roots`
  - `clears the widget and cancels queued renders on session shutdown`
- **不适用原因**：`pi-base` 为终端单行状态栏设计的 ANSI 截断与即时计数器；`kk-studio` 是 Web/API 架构，活动状态由平台侧 `SubagentTaskActivity` 统一聚合，通过 `HarnessRuntimeResponseMapper` 暴露给前端 React 组件渲染。

### 2. TUI 命令行与全屏覆盖层交互（`subagent-command.test.ts`，共 10 条）

- **涉及用例**：
  - `always selects before opening a running session`
  - `routes fullscreen PageUp through TuiAltScreen to the focused session panel`
  - `does not rewrite fullscreen bindings in regular TUI mode`
  - `restores fullscreen bindings if session panel construction fails`
  - `restores bindings when an explicit live target disappears before the overlay mounts`
  - `rejects unsupported contexts and reports empty or missing selections without opening an overlay`
  - `reports ambiguous persisted session prefixes`
  - `opens the persisted transcript when a selected running session finishes`
  - `prevents concurrent viewer overlays and allows reopening after close`
  - `opens a completed persisted session directly by explicit id`
- **不适用原因**：`pi-base` 专有的 `/subagents` TUI 命令与全屏 `TuiAltScreen` 视图切换；`kk-studio` 中子会话即标准 durable Thread，前端通过独立 Tab / Thread 视图直接查看，无需终端多路复用与按键捕获。

### 3. TUI 会话详情面板交互（`subagent-session-panel.test.ts`，共 8 条）

- **涉及用例**：
  - `renders live assistant text and tool execution with the main Pi components`
  - `replays completed parallel tools when opened while a sibling is still running`
  - `settles a pending tool from its persisted result when the execution-end event was missed`
  - `rebuilds persisted and active tool state, then handles live error and navigation events`
  - `stops following the tail while scrolling and cleans up on close`
  - `uses configured fullscreen viewport keys for page and edge navigation`
  - `keeps following new output when top is pressed before scrolling is possible`
  - `preserves regular-mode Home behavior on a short transcript`
- **不适用原因**：`pi-base` 终端 TUI 面板的滚动跟踪、按键绑定与实时事件消费；`kk-studio` 子会话具备完整的 Entry 历史投影与前端 SSE/WebSocket 实时流。

### 4. 终端交互式权限审批中继（`subagent-permission-relay.test.ts` 除停止外 3 条 + `subagent-foundation.test.ts` 权限 host 2 条）

- **涉及用例**：
  - `uses the same Yes/No actions as the normal permission prompt`
  - `replaces the previous root host on repeated session starts`
  - `discards an old root decision that resolves after the host is replaced`
  - `subagent-foundation.test.ts`（`subagent permission host` 套件）: `returns null when no host is registered`、`forwards to the registered root host and clears by identity`
- **不适用原因**：`pi-base` 将子 Agent 的权限审批同步阻塞冒泡到根终端 UI 弹窗；`kk-studio` 环境工具权限由项目配置与 YOLO 模式权威管理，无需终端 UI 阻塞弹窗。

### 5. 终端 Tool Call 实时展开/折叠渲染（`subagent-task-tool.test.ts`，共 6 条）

- **涉及用例**：
  - `renders only the task command and prompt while running regardless of expansion`
  - `uses configured collapsed task result budgets until expanded`
  - `keeps task errors visible when successful result previews are disabled`
  - `does not offer expansion when a disabled task preview shows the complete error`
  - `renders partial failed output after the error from a raw task envelope`
  - `keeps child progress out of task partial updates while updating the registry`
- **不适用原因**：`pi-base` 终端渲染专有的展开/折叠预算截断；`kk-studio` 通过 `SubagentTaskMessages` 生成完整 XML 消息，文本展示与截断由前端渲染层统一处理。

### 6. Node.js 扩展绑定与 symlink 隔离（`subagent-real-factory.test.ts`，共 6 条）

- **涉及用例**：
  - `rejects a symlinked path that would load a second pi-base module instance`
  - `fails fast and disposes when the child loader contains %s`
  - `does not mutate a persisted session when resume fails the extension identity check`
  - `applies a child-only model retry override through an isolated settings manager`
  - `inherits Pi retry settings when no child retry override is configured`
  - `disposes a child session when extension binding fails`
- **不适用原因**：`pi-base` 特有的 Node.js CJS/ESM 模块实例混用防范与 SettingsManager 分层；`kk-studio` 基于 Spring Boot / HarnessRuntime 进行进程级依赖管理与 Session 隔离。

### 7. CLI 启动参数与向后兼容（`subagent-integration.test.ts` & `subagent-runner.test.ts`，共 4 条）

- **涉及用例**：
  - `subagent-integration.test.ts`: `uses --agent startup selection to drive a delegating agent and execute task`
  - `subagent-runner.test.ts`: `keeps backward compatibility with legacy tool-use block aliases`
  - `subagent-runner.test.ts`: `publishes only the read-only view while the runner owns the live session`
  - `subagent-runner.test.ts`: `emits live progress, updates widget counters, and ignores registration hook failures`
- **不适用原因**：`kk-studio` 没有 CLI `--agent` 启动入口（Agent 由 Platform API / Issue 驱动），不保留旧版 `tool_use` 别名（使用统一 `ToolCall`），通过 HarnessStore 事务隔离而非内存读写视图。
