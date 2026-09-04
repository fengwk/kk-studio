# Harness Infra

## 定位

`harness-infra` 是 Runtime/Tool 的 PostgreSQL、Work dispatch、realtime notification 和本地 ResourceStore 适配层。它实现 `HarnessStore` 和进程 wiring，不拥有 Thread next-step、turn protocol、retry、Tool sibling aggregation 或插件业务规则。

生产依赖是 `harness-common`、`harness-runtime`、`harness-tool`、`harness-environment`、Spring JDBC 和 PostgreSQL driver；测试使用 schema/Flyway/Testcontainers。模块边界见 [`package-info.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/package-info.java) 与 [`pom.xml`](../../harness/infra/pom.xml)。

## Goals / Non-goals

### Goals

- 将 Runtime typed transaction primitives 映射到 PostgreSQL 的七张 `harness_*` 表。
- 以 `harness_work` claim/lease/wake、LISTEN/NOTIFY 和 periodic poll 提供最终可恢复的调度入口。
- 提供有损 realtime source/sink，让 durable snapshot 保持唯一恢复事实。
- 提供固定 root、内容寻址、NOFOLLOW 和精确 size/sha 校验的本地 ResourceStore。
- 管理 dispatcher 的 bounded handoff、round-robin、executor rejection 和 stop 生命周期。

### Non-goals

- 不在 Infra 决定 ThreadContext、TurnPlan、retry policy、Tool permission、Provider 请求或 contributor state。
- NOTIFY 不是 durable event log、Work queue 或 realtime replay buffer。
- LocalFileResourceStore 不从输入 URI、resource name 或路径内容推导可写目标；它只处理 pinned root 下的 content hash object。
- dispatcher 不读取 Thread/Invocation business state，也不解释 Processor 的 typed result。

## 依赖边界

```text
PostgresqlHarnessStore
  -> HarnessStore.Transaction primitives
  -> PostgreSQL / Spring JDBC / V1 schema

HarnessWorkDispatcher
  -> claimNextWork
  -> ThreadProcessor / ModelProcessor / ToolProcessor

Realtime source/sink
  -> harness_realtime NOTIFY
  -> live projection only

LocalFileResourceStore
  -> runtime.resource.ResourceStore
  -> fixed content-addressed filesystem root
```

Schema 的单一权威定义是 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 的 Harness execution protocol section；Infra 不维护 schema mirror。架构守卫 [`InfraModuleArchitectureTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/InfraModuleArchitectureTest.java) 限制生产依赖和 package。

## 包架构

| 包路径 | 职责范围 | 核心类型 | 边界契约与外部依赖 |
| --- | --- | --- | --- |
| `fun.fengwk.kkstudio.harness.infra.dispatch` | Work 调度循环与 Processor 分发 | [`HarnessWorkDispatcher`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java), [`HarnessWorkDispatcherConfig`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java) | claim-only 短事务、round-robin 轮询、wake 合并、bounded handoff、executor rejection 归还 claim、stop 生命周期与 Environment `nodeInstanceId` 传递；依赖 `HarnessStore` 与 Processor |
| `fun.fengwk.kkstudio.harness.infra.postgresql` | PostgreSQL 持久化存储与通知实现 | [`PostgresqlHarnessStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java), [`PostgresqlHarnessTransaction`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java), [`PostgresqlHarnessRows`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessRows.java), [`PostgresqlRealtimeEventSink`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSink.java), [`PostgresqlRealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java), [`RealtimeNotificationCodec`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java) | 七表 durable protocol、严格事务锁序防御、首个数据库故障 poisoning、事务内 EntryPath 局部缓存、Work claim/lease/wake、Environment route 路由围栏与 NOTIFY 编解码；依赖 Spring JDBC、PostgreSQL driver 与 Jackson |
| `fun.fengwk.kkstudio.harness.infra.realtime` | 实时事件传输抽象端口 | [`RealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimeEventSource.java) | 实时事件 live projection overlay 订阅端口与生命周期围栏定义，通知损坏或失联触发 resync，durable snapshot 为唯一恢复事实源；仅依赖 `harness-runtime` |
| `fun.fengwk.kkstudio.harness.infra.resource` | 本地文件内容寻址对象存储 | [`LocalFileResourceStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStore.java) | 固定根目录、SHA-256 十六进制扁平命名、create-only 原子硬链接发布、NOFOLLOW 打开与精确 size/sha 校验；实现 `ResourceStore`，依赖 JDK NIO 与 `harness-common` |

## 核心模型 / API

### PostgreSQL HarnessStore、transaction 与七表

[`PostgresqlHarnessStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java) 用 `READ_COMMITTED` + `PROPAGATION_REQUIRED` 执行 callback，事务名为 `harness-store`；同一 Store 的嵌套 transaction 拒绝。`PostgresqlHarnessTransaction` 只实现 [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) typed primitives：insert/find/lock/update、EntryPath、Invocation、Work claim/renew/complete/reschedule 和删除原语，不实现 use case。

Harness durable protocol 恰好七张表：

| 表 | durable 内容 |
| --- | --- |
| `harness_session` | Session id 和创建时间 |
| `harness_entry` | append-only Entry Tree 节点和 payload |
| `harness_thread` | Session 归属、head、creation request hash（初始创建请求指纹）、YOLO、command cursor、version |
| `harness_thread_command` | ordered command mailbox、request hash、APPLIED/CANCELLED marker |
| `harness_model_invocation` | Model request/status/attempt/checkpoint/result/error |
| `harness_tool_invocation` | Tool call/binding/status/approval/result/effects/error |
| `harness_work` | THREAD/MODEL/TOOL mailbox、wake 和 lease |

其中：

- `harness_entry` 通过 `(session_id,parent_entry_id)` FK 保证同 Session parent，partial unique index 保证每 Session 一个 ROOT；
- `harness_thread_command` 主键为 `(thread_id, sequence)`，另有 `(thread_id, idempotency_key)` 幂等唯一键；
- `harness_model_invocation` 以 `(thread_id, turn_start_entry_id)` 唯一，`result_entry_id` 非空时全局唯一；
- `harness_tool_invocation` 以 `(assistant_entry_id, call_index)` 唯一，`effects` 非 SUCCEEDED 时必须是空 batch，Tool batch apply 后删除；
- 时间列为 `timestamptz(3)`，应用拥有 version/id/time，数据库不自动推进 Runtime version。

`loadEntryPath` 使用单次 `WITH RECURSIVE ... CYCLE` 按 root-to-head 读取不可变路径，保证一次往返完成且避免父子环路。`PostgresqlHarnessTransaction` 维护 transaction-local 的 `Map<UUID, EntryPath>` positive cache：
- 首次持久化读取未命中时执行单次 CTE 并缓存结果，同事务内重复读取直接命中缓存；
- 连续 `insertEntry` 时从已缓存 parent path 派生追加并写入缓存，同事务后续读取子节点 0 次 CTE；
- 具备严格的 transaction 隔离，各事务独立维护自身缓存；
- `deleteEntries` 与 `deleteSession` 均按 session 驱逐缓存，避免脏读。

`findRootEntry(sessionId)` 优先复用当前事务缓存中已存在的同 Session EntryPath ROOT；cache miss 时通过 partial unique index `uk_harness_entry_single_root` 进行点查，并将单节点 ROOT 路径写回缓存。

`loadContributorCustomEntriesOnPath` 在 exact full `entryPathCache` 命中时直接在内存中过滤返回；cold cache 使用独立 `WITH RECURSIVE ... CYCLE`，最终只返回 ROOT/head/cycle sentinel 与匹配 CUSTOM，验证到 ROOT/无 cycle/正确 head/同 Session；部分投影绝不写入 full path cache。

### 事务锁序

所有多实体 Runtime transaction 的锁级别是：

```text
Session (KEY SHARE / FOR UPDATE)
  -> Thread（UUID 升序）
  -> Commands（sequence 升序）
  -> ModelInvocation
  -> ToolInvocation siblings（assistantEntryId + callIndex）
  -> Work（target type + UUID 升序）
```

`FOR KEY SHARE` 用于正常 command/初始创建路径，避免 sibling Thread 被 Session 级写锁串行化；删除/归属独占操作使用 `FOR UPDATE`。[`PostgresqlHarnessTransaction`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java) 在 transaction handle 内记录 LockRank、Thread UUID、Tool callIndex 和 Work 顺序，逆序获取锁直接抛 `IllegalStateException`；transaction handle 只能由创建线程在 callback 内使用。

### harness_work claim / lease / wake

`harness_work` 的主键是 `(target_type,target_id)`，列包含 `available_at`、`wake_version`、`lease_token`、`lease_until` 与 `required_environment_id`。

`requestWork` 在 owning Thread 已锁定时由 Runtime transaction 调用，使用 upsert：

```text
available_at = least(current, requested)
wake_version = current + 1
lease_token / lease_until 保持不变
```

其中：
- `harness_work.required_environment_id` 仅用于 TOOL Work（非 TOOL 传入非空值直接抛 `IllegalArgumentException`）；
- request 对已冻结 affinity 的冲突拒绝：upsert 的 update 语句包含条件 `where (harness_work.required_environment_id is not distinct from excluded.required_environment_id or excluded.required_environment_id is null)`。若已存在的 Work 已经绑定了 `required_environment_id`，而后续 request 传入了不一致的非空 affinity，UPDATE 条件不匹配导致 0 行更新，`writeOne` 抛出 `IllegalArgumentException("conflicting requiredEnvironmentId for work target ...")` 明确拒绝冲突，防止执行中发生环境漂移。

`claimNextWork` 是 Work-only 短事务，按如下规则选取一条 candidate 并原子写入新 `lease_token` 与 `lease_until`：
- candidate 按 `available_at <= statement_timestamp()` 且 `(lease_until is null or lease_until <= statement_timestamp())` 筛选，按 `available_at, target_id` 升序排序，使用 `FOR UPDATE SKIP LOCKED` 悲观跳过被并发事务锁定或已被持有的行；
- Environment route 路由围栏：
  - `required_environment_id` 为空时，无 Environment affinity，任何活跃 Dispatcher 节点均可 claim；
  - `required_environment_id` 非空时，只有 `environment_connection.environment_id` 匹配、`owner_node_id` 等于 Dispatcher 的当前 `nodeInstanceId`、状态 `READY` 且 `lease_until > statement_timestamp()` 的节点可 claim；
  - 路由不确定（连接不存在、处于非 `READY` 状态、连接 lease 过期、归属其他节点）或数据库失败时严格 fail closed（该 candidate 不被选中）；
  - 围栏层次区分：Environment route 路由围栏（面向外部网络拓扑与节点环境绑定的路由准入）与底层 PostgreSQL 行级悲观并发控制 `FOR UPDATE SKIP LOCKED`（行级跳锁，避免节点间加锁排队等待）以及应用层 Work lease 租约（`lease_token` / `lease_until` 所有权围栏）属于完全不同层次的机制，互不替代；避免把 `SKIP LOCKED` 误称为分布式锁或 leader 机制。

所有权围栏与生命周期原语：
- `lockClaimedWork` 校验 token 一致且 `lease_until > now`（所有权围栏）；
- `renewWork` 在已持有 Work 锁前提下延长租约；
- `completeWork` 是 final Work fence：claimed wakeVersion 仍是最新时删除 Work；若执行期间有新 wake 导致 `wake_version` 推进，则保留 Work 行并清除 lease 待后续调度；若已失去所有权则抛错回滚事务；
- `rescheduleWork` 清除 lease 并设置 requested time，保留当前 wakeVersion。

同一 Store transaction 内先由 `requestWork` upsert Work，再执行 `pg_notify('harness_runtime_work', ...)`；PostgreSQL 只在该 transaction commit 后向 listener 投递通知。通知只是 availability hint；通知丢失由 fixed-delay poll、重连 wake 和 lease expiry claim 收敛。

### Dispatcher lifecycle / fencing

[`HarnessWorkDispatcher`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java) 只做：

```text
wake merge -> round-robin claim THREAD/MODEL/TOOL (passing nodeInstanceId)
  -> bounded worker handoff
  -> Processor.process(ClaimedWork)
  -> task finally wake
```

Dispatcher 拥有明确的 `nodeInstanceId`（构造时生成或指定），并在每次 claim-only 短事务中将其传递给 `claimNextWork`，以驱动 Environment route 路由围栏。

`start()` 注册 fixed-delay poll 并立即 wake，重复 start 无副作用；`stop()` 取消 poll，不 shutdown 注入 executor、不 interrupt 已接受 task、不等待 Processor。drain 使用 `wakeRequested` + `drainRunning` CAS，收尾窗口再次检查 wake。THREAD/MODEL/TOOL 使用实例级 round-robin cursor，避免容量为 1 时固定偏向 THREAD。

worker executor 必须 fail-fast reject；禁止 `CallerRunsPolicy`、Discard 和静默替换。handoff 被拒绝时，dispatcher 先通过所有权围栏 `lockClaimedWork` 确认仍 owned，才以 `executorRejectionDelay` 延迟调用 `rescheduleWork` 归还 claim，然后结束当前 drain，避免 claim→reject 热循环。Processor 抛 RuntimeException 时 dispatcher 只记录日志并保留 lease 待过期恢复，不猜测 complete/reschedule/delete。

### Realtime source / sink

`PostgresqlRealtimeEventSink` 写 channel `harness_realtime`。EVENT JSON 超过 `7900` UTF-8 bytes 时自动改发小型 RESYNC，reason 为 `EVENT_TOO_LARGE`。[`RealtimeNotificationCodec`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java) 只接受 canonical `EVENT`/`RESYNC` envelope，严格检查字段集合、duplicate/trailing、UUID 和 round-trip canonical JSON。

[`PostgresqlRealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java) 不创建线程或连接；共享 listener loop 通过 `onNotification` 交付 payload，连接建立/重建通过 `onResync`。合法 EVENT 只分发到目标 Thread subscriber；malformed/unknown、连接恢复和 resync 通知触发全部本地 subscriber 的 snapshot recovery。全局 lifecycle fence、source callback fence 和 per-subscriber callback fence 保证 close 后不再回调；用户 callback 始终在全局锁外执行。

Realtime 是 live-only overlay：

```text
durable snapshot = recovery truth
NOTIFY EVENT    = low-latency best effort
NOTIFY RESYNC   = reread snapshot hint
```

### Local Resource

[`LocalFileResourceStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStore.java) 以固定 canonical root 保存 `<sha256>` flat file：

```text
reference() -> canonical file:///root/<sha256>（无存储副作用）
put()       -> SHA-256 -> NOFOLLOW/CREATE_NEW temp -> force -> create-only hard link
read()      -> pinned root + sha filename + NOFOLLOW + exact size + exact digest
```

并发写同一 content 时只有一个 hard-link publisher，其他调用复用同一目标并执行相同最终校验；不覆盖既有对象。root 必须是已存在真实目录并能生成 ResourceRef-compatible ASCII file URI。读取拒绝 authority/path 不在 pinned root、符号链接、非普通文件、短读、多读、size mismatch 或 digest mismatch。

## 执行 / 状态 trace

```text
Runtime transaction mutation
  -> harness_work upsert + wake_version++
  -> pg_notify(harness_runtime_work)
  -> shared PostgreSQL notification loop -> dispatcher.wake()
  -> periodic poll 兜底
  -> claim token + lease_until
  -> bounded handoff
  -> ThreadProcessor / ModelProcessor / ToolProcessor
  -> complete / reschedule / delete Work
```

PostgreSQL connection 断开不改变 Entry/Invocation/Work；Work lease 过期后由任一 dispatcher 实例重新 claim。realtime connection 断开只要求 source 触发 resync，不重建 durable 数据。

## 不变量、failure / recovery

- Store callback 正常返回才完成当前 transaction；Runtime exception/Error 使当前边界回滚；数据库 failure 在 callback 返回前重新抛出。
- `requestWork` 必须在 owning Thread 已锁定时执行；dispatcher claim 是唯一允许先获取 Work lock 的单 Work primitive。
- `harness_work.required_environment_id` 仅用于 TOOL Work；路由围栏要求 `environment_connection` 匹配、Dispatcher 当前节点持有、状态 `READY` 且 lease 未过期；路由不确定或数据库失败时 fail closed。
- `requestWork` 对已冻结的 `required_environment_id` 严格冲突拒绝，已存在 Work 绑定 affinity 后不允许传入冲突的不同非空 affinity。
- claim token、leaseUntil、wakeVersion 任何一项不匹配都不允许过期 worker 完成新 wake；lost claim 是正常 no-op/recovery 条件。
- NOTIFY 丢失、乱序或重复不影响 correctness；poll 和 lease expiration 提供收敛。
- Processor 的 durable mutation 先于 Work final fence；final fence 失败时事务整体回滚。
- Work handoff rejection 归还 claim 失败时保留 lease，等待过期 recovery，不能伪造 complete。
- realtime sink failure、payload 超限、malformed notification 只降低 live projection，客户端从 snapshot 继续。
- LocalFileResourceStore 所有写入为 create-only；目标损坏、符号链接或 digest 不匹配绝不覆盖并抛错。
- 深删除按 Thread UUID、Command、Model、Tool、Work 锁序，删除 children 后删除 parent；Entry 叶子优先删除，ROOT 最后删除。

## 配置 / 扩展

[`HarnessWorkDispatcherConfig`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java) 的配置为：

```text
threadLeaseDuration
modelLeaseDuration
toolLeaseDuration
periodicPollInterval
executorRejectionDelay
maxDispatchTasks
```

生产组合根通过 `HarnessDispatcherProperties` 将 `kk-studio.harness.dispatcher.*`
映射为上述配置，并单独构建 bounded worker executor；这些值属于部署启动边界，不进入
SystemSettings、DTO 或 frontend。

所有 Duration 必须是正的整毫秒；`maxDispatchTasks` 只限制 dispatcher 本地
queued/running handoff 数量，不限制 Model/Tool 外部 execution 并发。`PostgresqlHarnessStore`
的 UUID 由注入 `Supplier<UUID>` 提供。

外部可替换：

- `HarnessStore` 的 PostgreSQL implementation；
- `RealtimeEventSource` / `RealtimeEventSink` 的 transport adapter；
- `ResourceStore` 的本地或其它内容寻址 implementation；
- dispatcher 的 drain/worker/poll executor，但 executor 必须遵守 fail-fast submission contract。

## 测试与源码入口

### 源码入口

- [`PostgresqlHarnessStore.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java)、[`PostgresqlHarnessTransaction.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java)、[`PostgresqlHarnessRows.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessRows.java)
- [`HarnessWorkDispatcher.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java)、[`HarnessWorkDispatcherConfig.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java)
- [`PostgresqlRealtimeEventSink.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSink.java)、[`PostgresqlRealtimeEventSource.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java)、[`RealtimeNotificationCodec.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java)
- [`LocalFileResourceStore.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStore.java)、[`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)

### 关键测试守卫

- [`InfraModuleArchitectureTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/InfraModuleArchitectureTest.java)：Infra 生产依赖仅允许 Common/Runtime/Tool/Environment/Spring JDBC/PostgreSQL。
- [`PostgresqlHarnessSchemaTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessSchemaTest.java)、[`PostgresqlHarnessStoreTransactionTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreTransactionTest.java)：七表 schema、transaction boundary 和 handle lifecycle。
- [`PostgresqlEntryPathCacheTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlEntryPathCacheTest.java)：事务内 EntryPath 局部缓存、首次持久化读取/连续 append 的 CTE 计数、事务隔离与 deleteEntries 驱逐。
- [`PostgresqlAcceptCommandsRootQueryTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlAcceptCommandsRootQueryTest.java)：守护非 ROOT head 的新 batch/ordered replay 为 0 次完整 path CTE，执行开销与 Entry 树深度无关。
- [`PostgresqlContributorCustomPathTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlContributorCustomPathTest.java)：窄查询守卫，验证 cycle corruption fail-closed、cold cache 0 次 full-path CTE 以及 warm cache reuse 内存复用。
- [`PostgresqlHarnessStoreConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreConcurrencyTest.java)、[`PostgresqlInvocationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlInvocationTest.java)：并发锁序、Invocation transition 和 terminal facts。
- [`PostgresqlWorkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkTest.java)、[`PostgresqlWorkNotificationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkNotificationTest.java)：claim/lease/wake/NOTIFY/poll 语义。
- [`HarnessWorkDispatcherLifecycleTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherLifecycleTest.java)、[`HarnessWorkDispatcherDrainTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherDrainTest.java)、[`HarnessWorkDispatcherHandoffTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherHandoffTest.java)：single drain、round-robin、bounded handoff、rejection 和 stop。
- [`PostgresqlRealtimeEventSourceConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSourceConcurrencyTest.java)、[`PostgresqlRealtimeEventSinkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSinkTest.java)、[`RealtimeNotificationCodecTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodecTest.java)：source fencing、oversize RESYNC 和 canonical codec。
- [`LocalFileResourceStoreTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStoreTest.java)：content address、并发 create-only、NOFOLLOW 和 size/sha 校验。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Runtime](harness-runtime.md)、
[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Schema](schema.md)、[Web](web.md)。
