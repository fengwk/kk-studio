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

`harness_work` 的主键是 `(target_type,target_id)`。`requestWork` 使用 upsert：

```text
available_at = least(current, requested)
wake_version = current + 1
lease_token / lease_until 保持不变
```

`claimNextWork` 是 Work-only 短事务，按 `available_at,target_id` 选取一条 due 且 lease 缺失/过期的目标，使用 `FOR UPDATE SKIP LOCKED` 写入新 token/until。`lockClaimedWork` 校验 token 和 `lease_until > now`；`completeWork` 在 claimed wakeVersion 仍是最新时删除 Work，有新 wake 时清除 lease 保留行；`rescheduleWork` 清除 lease 并设置 requested time。

同一 Store transaction 内先由 `requestWork` upsert Work，再执行
`pg_notify('harness_runtime_work', ...)`；PostgreSQL 只在该 transaction commit
后向 listener 投递通知。通知只是 availability hint；通知丢失由 fixed-delay
poll、重连 wake 和 lease expiry claim 收敛。

### Dispatcher lifecycle / fencing

[`HarnessWorkDispatcher`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java) 只做：

```text
wake merge -> round-robin claim THREAD/MODEL/TOOL
  -> bounded worker handoff
  -> Processor.process(ClaimedWork)
  -> task finally wake
```

`start()` 注册 fixed-delay poll 并立即 wake，重复 start 无副作用；`stop()` 取消 poll，不 shutdown 注入 executor、不 interrupt 已接受 task、不等待 Processor。drain 使用 `wakeRequested` + `drainRunning` CAS，收尾窗口再次检查 wake。THREAD/MODEL/TOOL 使用实例级 round-robin cursor，避免容量为 1 时固定偏向 THREAD。

worker executor 必须 fail-fast reject；禁止 `CallerRunsPolicy`、Discard 和静默替换。handoff 被拒绝时，dispatcher 先 ownership-fenced `lockClaimedWork`，仍 owned 才以 `executorRejectionDelay` reschedule，然后结束当前 drain，避免 claim→reject 热循环。Processor 抛 RuntimeException 时 dispatcher 只记录并保留 lease 待过期恢复，不猜测 complete/reschedule/delete。

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
- [`PostgresqlHarnessStoreConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreConcurrencyTest.java)、[`PostgresqlInvocationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlInvocationTest.java)：并发锁序、Invocation transition 和 terminal facts。
- [`PostgresqlWorkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkTest.java)、[`PostgresqlWorkNotificationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkNotificationTest.java)：claim/lease/wake/NOTIFY/poll 语义。
- [`HarnessWorkDispatcherLifecycleTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherLifecycleTest.java)、[`HarnessWorkDispatcherDrainTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherDrainTest.java)、[`HarnessWorkDispatcherHandoffTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherHandoffTest.java)：single drain、round-robin、bounded handoff、rejection 和 stop。
- [`PostgresqlRealtimeEventSourceConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSourceConcurrencyTest.java)、[`PostgresqlRealtimeEventSinkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSinkTest.java)、[`RealtimeNotificationCodecTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodecTest.java)：source fencing、oversize RESYNC 和 canonical codec。
- [`LocalFileResourceStoreTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStoreTest.java)：content address、并发 create-only、NOFOLLOW 和 size/sha 校验。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Runtime](harness-runtime.md)、
[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Schema](schema.md)、[Web](web.md)。
