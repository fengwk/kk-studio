# Harness Runtime

## 定位

`harness-runtime` 是纯 Java durable Agent Runtime，承载 Session Entry Tree、Thread command mailbox、Model/Tool invocation、Work mailbox、三个 target processor、compaction、Stop、approval、Provider/Tool ports、permission、cache、usage/cost 与 subagent TaskTool。PostgreSQL、Spring、Provider SDK、HTTP、WebSocket 和具体 Tool 实现在外部适配。

同步 command/control/query 的唯一 facade 是 [`HarnessRuntime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java)。所有持久化写入通过 [`HarnessStore`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) typed transaction 完成；每个 facade 方法只执行一个 Store transaction，Stop durable commit 后才进行同 JVM 的 best-effort execution cancel。

## Goals / Non-goals

### Goals

- 以 append-only Entry Tree 保存可恢复的会话语义，以 Thread head 保存 branch cursor。
- 让 Model、Tool 的长执行由 durable Invocation + Work lease 驱动，进程退出后可重新 claim。
- 用 processor 的单 action reducer、CAS、claim fencing 和严格 Entry materialization 保证状态一致。
- 将 Provider、Tool、permission、Resource history materialization、realtime 和 Thread change source 收敛为窄 port。
- 让 compaction、Stop、approval、retry、cache、usage/cost 和 subagent 都复用同一 durable loop。

### Non-goals

- 不实现数据库、事务框架、dispatcher、Provider SDK、Environment connection 或 Platform catalog。
- 不把 Entry 变成 event-sourcing replay log；Invocation 是当前 workflow state，Work 是调度 mailbox。
- 不在 Runtime 内实现具体 Agent/Provider/Tool 配置查询；Resolver 和 gateway 由组合根注入。
- 不以 realtime notification、进程内 registry 或 scheduler memory 作为恢复事实。

## 依赖边界

```text
infra / platform / web composition root
        │         ┌───────────────┐
        ├────────▶│ HarnessRuntime│
        │         └───────┬───────┘
        │                 │
        │        ┌────────▼────────┐
        └───────▶│ HarnessStore    │
                 │ ModelGateway    │
                 │ ToolGateway     │
                 │ TurnResolver    │
                 │ RealtimeSink    │
                 └────────┬────────┘
                          ▼
                    harness-tool
```

主源码依赖 `harness-tool`、Jackson、SLF4J；JGit 只由 permission path matcher 所在实现使用。POM 与架构约束见 [`pom.xml`](../../harness/runtime/pom.xml) 和 [`RuntimeModuleArchitectureTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/RuntimeModuleArchitectureTest.java)。Runtime 不得依赖具体 `harness.plugins.*`、infra、daemon、platform 或 web。

## 核心模型 / API

### Session、Entry Tree 与 Thread

每个 Session 组织一棵 append-only Entry Tree。`EntryType` 的固定集合为：

```text
ROOT
TURN_START
MESSAGE
CUSTOM
MODEL_ATTEMPT_FAILURE
CUSTOM_MESSAGE
ASSISTANT_ERROR
ASSISTANT_ABORTED
COMPACTION
TURN_END
```

`ROOT` 是唯一无 parent 节点，保存初始 `BranchSettings` 和可选 `SubagentContext`。`TURN_START` 保存完整 branch settings、owner Thread、reason（`INPUT` / `CONTINUATION` / `COMPACTION`）以及 resolved context window/output budget。`MESSAGE` 保存 USER/ASSISTANT/TOOL；`CUSTOM` 是透明插件状态；`CUSTOM_MESSAGE` 是可见的 SYSTEM/USER 扩展消息；`MODEL_ATTEMPT_FAILURE` 是 retry audit；`ASSISTANT_ERROR`、`ASSISTANT_ABORTED` 是 assistant barrier；`COMPACTION` 只保存 summary；`TURN_END` 保存 outcome 和 continuation obligation。

[`EntryPath`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryPath.java) 是 root-to-head 的不可变连续路径：同 Session、parent 连续、createdAt 不早于 parent、ROOT 唯一，并由 [`TurnPathValidator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/TurnPathValidator.java) 校验 turn grammar。`CUSTOM` 不打开/关闭 turn；INPUT 必须有 user-like message；Tool result 必须按 ordinal 前缀；closed turn 不允许再次追加消息。

`ThreadState` 只保存：

```text
id / sessionId / headEntryId / materializationHash
yoloEnabled / nextCommandSequence / version
createdAt / updatedAt
```

Environment、branch settings、status、open turn、runnable flag、execution epoch 和 processor lease 都由 Entry/Invocation/Work 投影。`ThreadState.validateTransition` 保证 identity 不变、sequence/version/time 不回退，任何可见变化的 `version` 精确增加 1；exact replay 不产生变化。

### Thread command mailbox

`acceptCommands` 的 sealed target：

- `NEW_SESSION`：原子创建 Session、ROOT、Thread、初始 Commands 和 THREAD Work；
- `ENTRY`：在既有 Session 的指定 Entry 下创建 Thread，不复制 Entry；
- `THREAD`：按 `expectedHeadEntryId` + `expectedNextCommandSequence` 接收新 batch。

Command 类型固定为 `USER_MESSAGE`、`CUSTOM_MESSAGE`、`SET_ENVIRONMENT`、`SET_AGENT`、`SET_MODEL`。SET 前缀顺序固定为 environment → agent → model；Agent 工具选择来自最新 Agent definition 的 `config.toolIds`，不进入 branch 或 command mailbox。初始化 batch 以一条末尾 user-like message 结束；THREAD batch 是一条用户消息或一条 SYSTEM steering。YOLO 不进 mailbox，而由 `setThreadYolo` 直接控制。

Command 的 durable state 由 marker 派生：无 marker 为 `QUEUED`，有 `consumedTurnStartEntryId` 为 `APPLIED`，有 `cancelRequestId/cancelledAt` 为 `CANCELLED`。同 `clientCommandId` + 同 `requestHash` 是 ordered replay；不同 hash、部分 replay、序号不连续和新 batch cursor 不匹配分别产生 typed conflict。

### Invocation 与 Work

`ModelInvocation` 保存 `basisHeadEntryId`、`turnStartEntryId`、冻结 `ModelRequestSpec`、status、attempt、stream checkpoint、terminal result/error、`resultEntryId` 和 append-only failed attempts。状态为：

```text
READY -> DISPATCHING -> RUNNING
  ├─> SUCCEEDED / FAILED / CANCELLED / UNKNOWN
  └─> READY（retry）
```

只有确认 Provider start 才使 attempt +1；BUSY 或确定未发送的拒绝不增加 attempt。`RUNNING -> READY` 只追加一个 retryable failure、清除 checkpoint，retry 重新 materialize 同一 spec。

`ModelRequestSpec` 是本次调用的唯一 durable request contract：

```text
providerType / model / variant / preambleMessages
toolBindings / skillBindings / subagentBindings
cacheControl
```

完整 history、Provider tools、顶层 Environment、YOLO、contextWindow、credential、endpoint 和 compaction transient metadata 不复制进 spec。[`ModelRequestMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelRequestMaterializer.java) 从 immutable EntryPath + spec 纯投影内存 ProviderRequest；普通请求只投影 MESSAGE、CUSTOM_MESSAGE、ASSISTANT_ABORTED 和 complete summary，compaction 请求只生成 summarization SYSTEM + USER 且无 tools。

`ToolInvocation` 保存冻结 `ToolCall`、完整 `ToolBinding`（`AgentToolDefinition` + 可选 Environment/plugin provenance）、assistant Entry、ordinal、approval、result、effects 和 error。状态为：

```text
WAITING_APPROVAL -> READY -> DISPATCHING -> RUNNING
       └───────────────> FAILED / CANCELLED
                         └─> SUCCEEDED / FAILED / CANCELLED / UNKNOWN
```

terminal Tool 行表示 outcome 尚未进入 Tool Result Entry；batch apply 在同一事务追加 Entry 后删除全部 Tool siblings 和 parent ModelInvocation。`ToolEffectBatch` 最多 16 条 CUSTOM effect，只有 SUCCEEDED 可携带非空 effects。

`Work` 是 `(THREAD|MODEL|TOOL, targetId)` 的唯一 durable mailbox：`availableAt`、`wakeVersion`、`leaseToken`、`leaseUntil`。Wake 合并最早 available time 并增加 wakeVersion；lease token 与 wakeVersion 一起 fence stale worker。

### HarnessRuntime 同步 API

[`HarnessRuntime`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java) 对外提供：

```text
acceptCommands
findThreadCommand
stop
decideToolApproval
setThreadYolo
getThreadSnapshot
getSessionEntries
listThreadsBySession
```

`getThreadSnapshot` 在单事务中锁 Thread、读取 queued Commands、加载 EntryPath 并按 `ThreadContextClassifier` 暴露最小适用状态：

```text
IdleOrHistorical
ContinuationDue
ModelActive
ModelTerminalPending
ToolActive
ToolTerminalPending
```

IDLE/continuation 不暴露 invocation；Model context 暴露 Model 和未物化 retry failures；Tool context 暴露 Model 和全部 siblings。分类器遇到 ownership、basis、result、ordinal、tool-call 数量等不变量破坏时抛 `IllegalStateException`，不把坏状态转换成业务 kind。

### Provider、Tool、permission 与 realtime ports

- [`TurnResolver`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/TurnResolver.java)：同步、无副作用、事务外；输入 candidate EntryPath，输出冻结 spec/context window/output budget 或 deterministic `Rejected`。异常代表临时基础设施失败，ThreadProcessor reschedule。
- [`ModelGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ModelGateway.java)：`Started`、`Busy`、`Rejected`、`Indeterminate`；Started 使用两阶段 `start` → durable RUNNING → `Handle.activate`，回调由 Runtime fence。
- [`ToolGateway`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolGateway.java)：先 `preflight` 得到 Allow/Ask/Deny，再 `start` 得到 Started/Busy/Overloaded/Rejected/Indeterminate；YOLO 由 ToolProcessor 在锁内短路，不由 gateway 查询 Store。
- Provider adapter 只使用 Runtime 的 [`ProviderRequest`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderRequest.java)、[`ProviderResponse`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/model/provider/ProviderResponse.java) 和 `ProviderStreamEvent`，SDK 类型留在 Platform。
- [`ToolResultHistoryMaterializer`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/ToolResultHistoryMaterializer.java) 在 Tool outcome Entry 插入前把 Resource 物化为 durable blob-backed message；缺少该 port 时 Resource result fail closed。
- [`RealtimeEventSink`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/port/RealtimeEventSink.java) 只发布有损 `MODEL_DELTA`/`TOOL_PARTIAL` overlay，sink 失败不改变 durable terminal。

### usage、cost、cache 与 admission

`ModelUsage` 固定七项：`inputTokens`、`outputTokens`、`cacheReadTokens`、`cacheWriteTokens`、`cacheWriteLongTokens`、`reasoningTokens`、`providerTotalTokens`，全部非负；`ModelCost` 保存 currency、六类分项成本和 total，total 必须等于分项和。

[`PromptCacheAffinityKeyFactory`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/cache/PromptCacheAffinityKeyFactory.java) 生成 `pc1-` + SHA-256 Base64URL key，输入是 session、provider/model、leading SYSTEM 内容和按序 tool name/description/schema 的 length-prefixed frame；动态 history 和 sampling 参数不进 key。`PromptCacheRequestFinalizer` 是唯一控制覆盖点：NONE 或不支持的 capability 输出 `none()`；AFFINITY 生成 affinity key；BREAKPOINTS 取 provider capability 与请求实际 SYSTEM/TOOLS 的交集。

[`ConcurrencyAdmission`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/admission/ConcurrencyAdmission.java) 是进程内、无等待、无队列的 semaphore admission；容量不足立即返回 empty，Lease 关闭幂等且恰好归还一个 permit。Model/Tool gateway 可用它限制外部 execution，lease 覆盖完整 invocation 生命周期。

## 执行 / 状态 trace

```mermaid
flowchart TD
  A[Command / continuation / compaction] --> B[THREAD Work]
  B --> C[ThreadProcessor: classify one action]
  C -->|plan| D[TurnResolver outside transaction]
  D --> E[TURN_START + ModelInvocation]
  E --> F[MODEL Work]
  F --> G[ModelProcessor: materialize + gateway]
  G --> H[Provider terminal]
  H --> I[THREAD Work]
  I --> J[Model terminal apply]
  J -->|no tools| K[Assistant/Error + TURN_END + delete Model]
  J -->|tool calls| L[ToolInvocation siblings + TOOL Work]
  L --> M[ToolProcessor: permission + gateway]
  M --> N[Tool terminal]
  N --> I
  I --> O[Tool batch append + TURN_END(continueModel)]
  O --> B
```

### ThreadProcessor

[`ThreadProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java) 对每次 THREAD claim 恰执行一个 action：

1. Work-only 校验 claim，再进入 per-thread admission guard；
2. 锁 Thread，构造 EntryPath，纯分类；
3. `MODEL_TERMINAL_PENDING` 原子写 Assistant/Error/Compaction result；
4. `TOOL_TERMINAL_PENDING` 按 ordinal 经 [`ToolOutcomeAppender`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolOutcomeAppender.java) 写 effects + Tool Result + `TURN_END(continueModel=true)`；
5. `MODEL_ACTIVE`/`TOOL_ACTIVE` 只完成当前 claim；
6. `CONTINUATION_DUE` 优先处理 HISTORY/TURN_PREFIX 或普通 continuation；
7. `IDLE_OR_HISTORICAL` 在有 user demand 时启动 INPUT，否则完成 claim。

需要 resolver 的 turn 使用 speculative plan：第一短事务只构造合法 candidate path 和 command cutoff；事务外 `TurnResolver.resolve`，期间由 `WorkHeartbeat` 续租；第二事务以 source head、cutoff command snapshot 和 claim ownership CAS 提交。Resolver exception/null/heartbeat loss 只 reschedule，CAS/claim loss 完整回滚并返回 `LOST_OWNERSHIP`。

### ModelProcessor 与 ToolProcessor

[`ModelProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessor.java) 在有效 MODEL claim 内 materialize ProviderRequest，`READY -> DISPATCHING` 后启动 heartbeat，再调用 gateway。`Started` 返回的 handle 在 durable `RUNNING` 后 activate；stream delta 只写 attempt-local checkpoint 并尽力发 realtime，terminal result/error 写回 invocation 并 request THREAD Work。过期 `DISPATCHING`/`RUNNING` 变为 `UNKNOWN`，不重放 Provider。

[`ToolProcessor`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessor.java) 在 READY 且无 approval 时读取锁内 YOLO：YOLO true 直接 Allow；否则事务外 preflight。Allow 写 not-required approval 并 dispatch；Ask 写 `WAITING_APPROVAL`；Deny 写 FAILED 并 wake Thread。过期 lease 的 DISPATCHING/RUNNING 收敛 UNKNOWN，不重放非幂等 Tool。Tool partial 只允许 text/json；成功结果与 effects 一次 durable update，ThreadProcessor 才负责 Entry apply。

### Durable compaction

Compaction 复用普通 ModelInvocation、MODEL Work 和三个 processor，不增加表、Work target 或 processor：

```text
TURN_START(reason=COMPACTION, CompactionStart)
  -> ModelInvocation（summary SYSTEM + USER，zero tools）
  -> COMPACTION(summaryText)
  -> TURN_END
```

[`CompactionConfig`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionConfig.java) 默认 `keepRecentTokens=20_000`，可选 fallback model；有效 keep 为 `min(keepRecentTokens, contextWindow/2)`，reserve 为 `min(16384,maxOutputTokens)`，soft threshold 为 `max(effectiveKeep, contextWindow-effectiveReserve)`，manual minimum 为 `min(keepRecentTokens*2, contextWindow/2)`。FULL/HISTORY output budget 使用 reserve 的 80%，TURN_PREFIX 使用 50%，再取 removed-prefix estimate 的较小值。

[`CompactionPlanner`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java) 只基于 EntryPath 和 frozen context window 选择 legal cut：不会切在 ToolResult 或 COMPACTION turn 内；切分使用 HISTORY → TURN_PREFIX，HISTORY complete 后由 durable continuation obligation 驱动 TURN_PREFIX。`CompactionResultEvaluator` 拒绝截断、过滤、空 summary、tool call、非法 summary 和无 gain；complete summary 在后续 provider context 中成为一个 wrapper USER message。失败/停止/incomplete compaction 不自唤醒重试，hard overflow 最多恢复一次，fallback 复用 phase/anchors。

### Stop、approval 与 subagent

`stop` 在 Thread 锁内先按 owner Thread + closeRequestId 查找精确 replay，再做 version CAS。IDLE 只取消 queued Commands；live Model/Tool 写入安全的 aborted/error/cancelled barrier，关闭 turn、净化 Work、删除 Invocation。transaction commit 后才调用 Model/Tool processor 的 local cancel；local cancel 失败不反转 durable 结果。

`decideToolApproval` 只接受当前 `ToolActive` context 内 `WAITING_APPROVAL` invocation。`ALLOWED` → READY + TOOL Work，`DENIED` → FAILED + THREAD Work；相同 `decisionId` 与 payload 精确 replay，不 bump version。权限规则由 [`PermissionEvaluator`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/permission/PermissionEvaluator.java) 产生 Allow/Ask/Deny 候选，`ToolSettings.permission` 的 key 是精确 `*` 全局 wildcard 或 canonical `AgentToolId`，先应用全局规则再应用 tool id 规则，数组顺序保持为求值顺序；真实文件/symlink/执行边界不在 Runtime permission 包。

[`TaskTool`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/subagent/TaskTool.java) 是内部 HOST Tool，不新增状态机或表。它验证父 ModelInvocation frozen `subagentBindings`，用 `HarnessRuntime.acceptCommands(NEW_SESSION, SubagentContext)` 创建普通 durable child Thread；`session_id` resume 要求同 parent/root 且 child quiescent。观察通过 `HarnessThreadChangeSource` 的 version/resync wake 和 [`ChangeGate`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ChangeGate.java)，不固定读取 snapshot；`SubagentRunRegistry` 只 relay descendant status，并在 reservation 中拒绝超出 parent/tree concurrency 的调用。`maxTurns` 是软提醒，idle timeout 会取消 child 但保留 Session。

## 不变量、failure / recovery

- Session/Entry 是 append-only；Thread head 必须指向同 Session Entry；EntryPath 的 grammar 和 CUSTOM ownership 破坏直接 fail closed。
- Runtime 所有多实体事务遵守 `Session -> Thread -> Commands -> Model -> Tool siblings -> Work`；claim final fence 最后执行，失败时低序 mutation 整体回滚。
- `ThreadState.version`、command sequence、Entry createdAt、Invocation attempt/checkpoint 和 Work wakeVersion 都不能回退；terminal result/error/effects 不可变。
- lost/stale claim、过期 lease、重复 callback、重复提交和重复 approval 是正常竞态：返回 no-op / `LOST_OWNERSHIP` / exact replay，不取消合法的新 execution。
- Provider/Tool admission 的 `Busy`/`Overloaded` 证明未开始，可 reschedule；`Rejected` 确定性失败；`Indeterminate` 可能已开始，必须收敛 `UNKNOWN`，不重放潜在副作用。
- retry 只重放 frozen request；`NON_IDEMPOTENT` Tool 不因字符串错误自动重放。
- Realtime 丢失、超限或损坏只要求重新读取 durable snapshot；Entry、Invocation、Thread 和 Work 恢复不读取 realtime。
- Tool Resource materialization 任一步失败会回滚整个 outcome Entry transaction；瞬时 URI 不进入 durable Session message。

## 配置 / 扩展

- `ThreadProcessorConfig` 提供 lease、resolve failure、compaction provider 等 runtime policy；compaction config 每个决策点从 provider 读取。
- `InvocationRetryPolicyProvider` 每个 retry 判定点读取 `maxRetries`、backoff、base/max delay；retry policy 只接受整毫秒正 duration。
- Model/Tool execution 通过 `ModelGateway`、`ToolGateway` 和 `ConcurrencyAdmission` 注入；Runtime 不注册 Provider SDK 或 Tool implementation。
- `ToolResultHistoryMaterializer`、`RealtimeEventSink`、`HarnessThreadChangeSource` 是可为空/可替换的 feature-local port；缺失 materializer 时 Resource history 采用 fail-closed。
- Subagent 的 `maxDepth`、`maxConcurrency`、`maxTotalConcurrency`、`idleTimeout`、`maxTurns` 经 `SubagentConfigProvider` 每次决策现读；registry 不把进程内 reservation 当作 durable execution fact。

## 测试与源码入口

### 源码入口

- [`HarnessRuntime.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntime.java)、[`StopControl.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/StopControl.java)、[`ThreadContextLock.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/ThreadContextLock.java)
- [`EntryPath.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/EntryPath.java)、[`TurnPathValidator.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/history/TurnPathValidator.java)、[`ThreadState.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadState.java)
- [`ModelInvocation.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelInvocation.java)、[`ToolInvocation.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/tool/ToolInvocation.java)、[`Work.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/work/Work.java)
- [`ThreadProcessor.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessor.java)、[`ModelProcessor.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessor.java)、[`ToolProcessor.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessor.java)
- [`ModelRequestMaterializer.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/invocation/model/ModelRequestMaterializer.java)、[`CompactionPlanner.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlanner.java)、[`TaskTool.java`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/subagent/TaskTool.java)

### 关键测试守卫

- [`RuntimeModuleArchitectureTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/RuntimeModuleArchitectureTest.java)：纯 Java 依赖边界、runtime 包边界和模块依赖。
- [`HarnessRuntimeAcceptInitialTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeAcceptInitialTest.java)、[`HarnessRuntimeAcceptThreadTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeAcceptThreadTest.java)：initial materialization、ordered replay、cursor CAS。
- [`HarnessRuntimeStopReplayTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeStopReplayTest.java)、[`HarnessRuntimeStopConcurrencyTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeStopConcurrencyTest.java)、[`HarnessRuntimeApprovalTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/HarnessRuntimeApprovalTest.java)：Stop/approval 的 durable 幂等和并发 fencing。
- [`ThreadContextClassifierTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/thread/ThreadContextClassifierTest.java)、[`ThreadProcessorPlanningTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorPlanningTest.java)、[`ThreadProcessorToolBatchTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorToolBatchTest.java)：分类、speculative plan、Tool sibling apply。
- [`ThreadProcessorCompactionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorCompactionTest.java)、[`ThreadProcessorManualCompactionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ThreadProcessorManualCompactionTest.java)、[`CompactionPlannerTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/compaction/CompactionPlannerTest.java)：压缩切分、fallback、manual CAS 和 no-gain。
- [`ModelProcessorTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ModelProcessorTest.java)、[`ToolProcessorRecoveryTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/ToolProcessorRecoveryTest.java)、[`WorkHeartbeatTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/processor/WorkHeartbeatTest.java)：两阶段 activation、UNKNOWN recovery、lease fencing。
- [`TaskToolTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/subagent/TaskToolTest.java)、[`SubagentRunRegistryTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/subagent/SubagentRunRegistryTest.java)、[`ConcurrencyAdmissionTest.java`](../../harness/runtime/src/test/java/fun/fengwk/kkstudio/harness/runtime/admission/ConcurrencyAdmissionTest.java)：child Thread、事件观察、并发 reservation 和 lease 释放。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Infra](harness-infra.md)、
[Harness Tool](harness-tool.md)、[Harness Plugin API](harness-plugin-api.md)、
[Platform](platform.md)。
