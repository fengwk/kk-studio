# Harness Infra

Runtime 把「该由谁做什么」写成 `harness_work` 里的一行，把「说过什么」写成 append-only Entry 树；`harness-infra` 要回答的是这些事实究竟落在哪里、谁有权推进它们、以及节点挂掉之后系统怎么自己接回来。答案是同一份 PostgreSQL：行锁决定谁能写，`lease_token` / `lease_until` 决定谁还在拥有，LISTEN/NOTIFY 只是让发现更快、绝不承载状态。

模块把 Runtime 的强类型事务原语、Realtime 端口与 `ResourceStore` 端口适配到 PostgreSQL、PostgreSQL 通知通道与本地文件系统；生产依赖为 `harness-common`、`harness-runtime`、`harness-tool`、`harness-environment`、Spring JDBC 与 PostgreSQL driver，测试通过 Flyway 与 Testcontainers 起真实数据库。边界见 [`InfraModuleArchitectureTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/InfraModuleArchitectureTest.java)，表结构统一由 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 管理。

多节点部署中的 App 实例互为对等体，只通过同一 PostgreSQL 里的持久化状态、行锁、租约与 NOTIFY 协调；进程内没有任何跨节点共享的内存协调器。

## 事务边界与锁序

[`PostgresqlHarnessStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java) 在每个回调外层开一个 `READ_COMMITTED`、`PROPAGATION_REQUIRED`、名字为 `harness-store` 的事务；同一 Store 实例用 `ThreadLocal` 显式拒绝嵌套事务，回调加入调用方外层事务时由外层决定最终提交。

[`PostgresqlHarnessTransaction`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java) 实现 [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 的全部原语，并在句柄内记录当前达成的最高锁阶梯，实现层保证 Runtime 声明的锁序真的被遵守：

```text
Session (KEY SHARE / FOR UPDATE) -> Thread（UUID 升序）
  -> Commands（sequence 升序）-> ModelInvocation
  -> ToolInvocation siblings（assistantEntryId + callIndex 升序）
  -> Work（target type + UUID 升序）
```

命令写入、ThreadProcessor Entry 物化、手工压缩与会话创建只取 Session `FOR KEY SHARE`，让同 Session 的兄弟 Thread 并发推进；删除、独占变更与 `renameSession` 才升级为 `FOR UPDATE`。任何要在 Thread 行锁之后插入 `harness_entry` 的事务必须先持有父 Session 的 `KEY SHARE`，否则外键会在插入时隐式补取 Session 锁，与深删除形成 `Thread -> Session` 逆序。检测到逆序立即抛 `IllegalStateException`；句柄严格绑定创建它的线程。

事务期间任何底层数据库异常都被句柄记录为首个故障（poisoning），并在回调返回前由 `rethrowDatabaseFailure()` 强制重抛，防止把被污染的连接继续用于提交。

## 七表与 EntryPath 读取

| 表 | 承载内容 |
| --- | --- |
| `harness_session` | Session 聚合根标识、显示名称与创建时间 |
| `harness_entry` | append-only Entry Tree 节点与 payload |
| `harness_thread` | Session 归属、head 游标、creation request hash、显示名称、YOLO 开关、命令序号与 version |
| `harness_thread_command` | 有序命令邮箱、请求哈希、APPLIED 关联节点或 CANCELLED 取消标记 |
| `harness_model_invocation` | Model 请求规格、状态、attempt、流式 checkpoint、结果与错误 |
| `harness_tool_invocation` | Tool 调用参数、绑定、审批记录、状态、副作用批与错误 |
| `harness_work` | THREAD / MODEL / TOOL 调度邮箱、`wake_version` 与租约 |

数据库只做形状防御，语义由应用层负责：`(session_id, parent_entry_id)` 外键保证父子同 Session，部分唯一索引 `uk_harness_entry_single_root` 保证每个 Session 至多一个 ROOT；`harness_thread_command` 以 `(thread_id, sequence)` 为主键、`(thread_id, idempotency_key)` 唯一；`harness_model_invocation` 以 `(thread_id, turn_start_entry_id)` 唯一；`harness_tool_invocation` 以 `(assistant_entry_id, call_index)` 唯一，且非 `SUCCEEDED` 时 `effects` 必须为空批 `{"version": 1, "customEntries": []}`。所有时间列是 `timestamptz(3)` 毫秒精度，version 与业务 ID 完全由应用生成。

`loadEntryPath` 用单条 `WITH RECURSIVE ... CYCLE id SET is_cycle USING path` 递归 CTE 自 head 回溯到 ROOT，一次往返读出整条不可变路径并按 `depth desc` 输出，同时以环路哨兵检测并拒绝父子成环。句柄内维护事务局部 `Map<UUID, EntryPath>` 正向缓存：冷读一次 CTE 后回填；同事务内连续 `insertEntry` 直接从已缓存父路径派生新节点并更新缓存，后续读取子节点 0 次 CTE；缓存只在本事务可见，`close()` 清空；`deleteEntries` / `deleteSession` 按 session 整体驱逐，避免读到失效数据。`findRootEntry` 优先复用缓存中的 ROOT，未命中时借 `uk_harness_entry_single_root` 点查并把单节点路径写回缓存。[`PostgresqlHarnessRows`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessRows.java) 负责行映射与毫秒精度校验。

`loadContributorCustomEntriesOnPath` 在完整路径缓存精确命中时直接在内存过滤 CUSTOM Entry；未命中时走一条窄递归 CTE，只读 ROOT、head、环路哨兵与匹配该 `contributorId` 的 CUSTOM 节点并验证连通性，结果不回填完整路径缓存。深删除按锁序自底向上执行：`deleteThreads` 要求目标 Thread 全部已加锁、UUID 去重排序，先锁并删除排序后的 Work，再删 Command、Tool、Model，最后删 Thread；`deleteEntries` 叶子优先逐层删除，末尾删除 ROOT 与 Session。

## Work 的 claim / lease / wake

`harness_work` 以 `(target_type, target_id)` 为主键，列含 `available_at`、`wake_version`、`lease_token`、`lease_until` 与 `required_environment_id`。

`requestWork` 必须在持有所属 Thread 锁的事务内调用，upsert 语义是纯函数式推进：

```text
insert: available_at = requested, wake_version = 1, lease 保持 null
conflict: available_at = least(current, requested)
          wake_version = current + 1
          lease_token / lease_until 保持不变
```

环境亲和性一旦绑定即冻结：upsert 的更新条件为 `harness_work.required_environment_id is not distinct from excluded.required_environment_id or excluded.required_environment_id is null`，因此既有行绑定了 `required_environment_id` 时，后续传入不一致的非空环境 ID 会让 UPDATE 命中 0 行，实现层随即抛 `IllegalArgumentException` 拒绝，执行环境不会漂移。

`claimNextWork` 是独立短事务，用一条 `FOR UPDATE SKIP LOCKED` 语句选候选并原子签发新租约，筛选条件是 `available_at <= statement_timestamp()` 且 `lease_until is null or lease_until <= statement_timestamp()`，排序为 `available_at, target_id` 升序。它是系统内唯一允许只取单条 Work 行锁的调度事务：实现层要求事务先到达 `WORK` 阶梯，并且这必须是该事务中的第一次 Work 锁获取，运行时的业务事务则一律先锁 owning Thread。

环境路由围栏就嵌在这条 claim 之上：当 `required_environment_id` 为空时任何活跃 Dispatcher 节点都可认领；非空时用 `exists` 检查 `environment_connection` 中存在 `environment_id` 匹配、`owner_node_id` 等于当前 Dispatcher 的 `nodeInstanceId`、状态为 `READY` 且 `lease_until > statement_timestamp()` 的连接记录。断开、未就绪、归属他人或租约过期的环境一律不返回候选（fail closed），底层数据库故障则让整个 claim 事务回滚。这条路由围栏与 `FOR UPDATE SKIP LOCKED` 的行级并发控制、应用层 `lease_token` / `lease_until` 的所有权围栏分属三个层次，互不替代——路由回答「哪台机器该做」，行锁回答「谁先抢到」，租约回答「谁还在拥有」。

围栏原语沿同一层次展开：`lockClaimedWork` 校验 token 与数据库一致且 `lease_until > now`；`renewWork` 在持锁前提下手动顺延 `lease_until`；[`PostgresqlWorkChannel`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlWorkChannel.java) 在 `requestWork` 之后于同一事务内 `pg_notify`，通知只在提交后才投递。

## Dispatcher 生命周期

[`HarnessWorkDispatcher`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java) 只做 claim、路由、有界 handoff、wake 合并、周期 poll 与 stop：它从不读取 Thread 或 Invocation 业务状态，claims 一律是 claim-only 短事务，typed 结果完全由 Processor 解释。

```text
wake merge -> round-robin claim THREAD/MODEL/TOOL（携带 nodeInstanceId）
  -> bounded worker handoff -> Processor.process(ClaimedWork)
  -> 任务收尾触发一次新的 wake
```

调度器持有实例级 `nodeInstanceId` 并把它传进每次 `claimNextWork`，这正是环境路由围栏判定的输入。`wakeRequested` 与 `drainRunning` 两个原子标志以 CAS 合并唤醒，保证同一时刻只有一个 drain 在跑，并在收尾时再检一次新唤醒；THREAD / MODEL / TOOL 之间用实例级 round-robin 游标轮转，避免类型饥饿；`start()` 注册 fixed-delay poll 并立即触发首次唤醒，可重复调用；外部注入的 drain / worker / poll 执行器由调用方管理，`stop()` 只取消 poll future 并立即返回，已进入数据库的 claim 可能提交但会在 handoff 前归还。

worker 线程池必须是 fail-fast 拒绝策略（`AbortPolicy` 一类），构造时显式检查，拒绝 `CallerRunsPolicy` / `DiscardPolicy` / `DiscardOldestPolicy`。容量打满抛拒绝异常时，Dispatcher 先 `lockClaimedWork` 确认本节点仍持有有效租约，再按 `executorRejectionDelay` 延迟 `rescheduleWork` 归还租约并终止当前 drain，避免在过载下陷入 claim-拒绝的紧密循环。Processor 抛 `RuntimeException` 时只记日志、保留数据库租约，等租约自然过期后由后续调度重新认领并按持久化状态恢复。

Work 终态由业务数据提交之后才推进：[`completeWork`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 在排他锁下校验 `lease_token` 与认领时的 `wake_version`——版本未变说明期间没有新唤醒，直接删除该行；被新唤醒推进过则清空租约保留行交给下一轮；所有权已失效则抛异常回滚整个事务。`rescheduleWork` 清空租约并重设 `available_at` 且保持 `wake_version` 不变。

[`HarnessWorkDispatcherConfig`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java) 是部署级配置，也是唯一暴露给进程启动参数的部分：

```text
threadLeaseDuration / modelLeaseDuration / toolLeaseDuration
periodicPollInterval / executorRejectionDelay / maxDispatchTasks
```

前五个 Duration 必须是正的整毫秒，与 Store 的毫秒精度时间边界一致；`maxDispatchTasks` 只约束本机排队与运行中的 processor handoff 总数（到达上限即停止 claim），绝不约束异步 Model / Tool 的执行并发——那由各 Processor 及其注入的 Gateway / executor 决定。生产组合由 [`HarnessDispatcherProperties`](../../platform/src/main/java/fun/fengwk/kkstudio/platform/harness/configuration/HarnessDispatcherProperties.java)（前缀 `kk-studio.harness.dispatcher`）提供。

## Realtime source / sink

```text
durable snapshot = 恢复事实源
NOTIFY EVENT     = 低延迟尽力投递
NOTIFY RESYNC    = 提示重读快照
```

[`PostgresqlRealtimeEventSink`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSink.java) 向 `harness_realtime` 通道投递实时事件：单条 `append` 用参数化 `pg_notify` 发送编码后的 canonical envelope，UTF-8 载荷超过 `7900` 字节时只把这一个事件降级为紧凑 RESYNC 并把原因记为 `EVENT_TOO_LARGE`。批量 `appendAll` 把已提交批次压缩为「每分块一次往返」，分块同时受 `256` 个事件与 `256KiB` 编码载荷双重上界约束，用 `select pg_notify(?, payload) from unnest(?::text[]) with ordinality ... order by ord` 按输入顺序发出，因此投递顺序与逐条 `append` 完全一致、SQL 往返数等于分块数而不是事件数；每个事件仍各自独立降级。JDBC `Array` 在成功与异常路径都必须释放，数据库异常直接向上传播，由 Runtime 既有的实时事件边界隔离。

[`RealtimeNotificationCodec`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java) 是唯一的编解码入口，对字段集合、重复字段、尾随字符与 JSON 形状做确定性严格校验。[`PostgresqlRealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java) 实现 [`RealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimeEventSource.java)：可靠的长连接与 `LISTEN` 由全应用共享的通知循环维护，收到的载荷经 `onNotification` 投递、重连成功后经 `onResync` 触发同步；合法 EVENT 按所属 Thread 精确分发给本地订阅方，畸变、未知消息与重连一律触发全部订阅方重读快照。并发由全局生命周期锁、Source 级完成围栏与 Subscriber 级独立围栏三层保证，关闭后不再产生任何回调，用户业务回调始终在全局锁之外执行。

通知的丢失、乱序或重复都不会改变最终状态：固定周期 poll、重连后的重读与租约过期重认领共同构成收敛路径。

## LocalFileResourceStore

[`LocalFileResourceStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStore.java) 在固定受信任根目录下以 `<sha256>`（64 位小写十六进制）为扁平文件名实现 [`ResourceStore`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/resource/ResourceStore.java)：

```text
reference() -> 规范 file:///root/<sha256>，纯内存、无存储副作用
put()       -> SHA-256 -> NOFOLLOW/CREATE_NEW 临时文件 -> force 落盘 -> create-only 硬链接发布
read()      -> pinned root + sha 文件名 + NOFOLLOW + 精确 size + 精确摘要
```

构造时要求根目录解析为规范绝对真实目录且能生成合规 ASCII URI。`put` 先算摘要，在同一根目录写入临时文件并 `force(true)`，再用 `Files.createLink` 原子发布；并发写同一内容时恰好一个获胜者创建对象，其余捕获 `FileAlreadyExistsException` 复用已有目标。无论自有发布、冲突还是复用，返回引用前都必须对最终目标做一次精确 size 与摘要校验（同一条 NOFOLLOW 通道，EOF 提前结束或多出字节都算失败），绝不覆盖。`read` 只接受 parent 恰为 pinned root 且文件名等于 sha256 的规范引用，以 `NOFOLLOW_LINKS` 打开、确认是普通文件而非符号链接、按声明字节数精确读取并校验摘要。非法输入（null、超限 content、非 file 引用、越界或嵌套路径）抛 `IllegalArgumentException`；损坏、符号链接、大小或摘要不符、文件系统不支持硬链接等存储异常抛 `IllegalStateException`。

## 包架构

| 包名 | 职责 | 边界 |
| --- | --- | --- |
| `infra.dispatch` | `HarnessWorkDispatcher` 与 `HarnessWorkDispatcherConfig`：Work 调度循环、类型轮转、有界 handoff、wake 合并与 stop | claim-only 短事务；不读业务状态、不解释 Processor 结果 |
| `infra.postgresql` | `PostgresqlHarnessStore`、`PostgresqlHarnessTransaction`、`PostgresqlHarnessRows`、`PostgresqlWorkChannel`、`PostgresqlRealtimeEventSink` / `PostgresqlRealtimeEventSource` / `PostgresqlRealtimeChannel` / `RealtimeNotificationCodec` | 七表 durable 协议、锁序防御、poisoning、事务内 EntryPath 缓存、claim / lease / wake 与环境路由围栏、NOTIFY 编解码 |
| `infra.realtime` | `RealtimeEventSource` 实时事件订阅端口 | 只定义 live overlay 订阅与完成围栏；durable snapshot 是唯一恢复事实源 |
| `infra.resource` | `LocalFileResourceStore` 本地内容寻址对象存储 | 固定根目录、SHA-256 扁平命名、create-only 原子发布与精确校验 |

## 源码与测试

生产源码只有四个包：[`postgresql`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/)（Store、行映射、Work 通道、Realtime sink/source 与通知编解码）、[`dispatch`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/)（Work 调度循环）、[`realtime`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/)（订阅端口）、[`resource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/)（本地内容寻址对象存储）。

测试分四组，入口都在 [`harness/infra/src/test`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/) 下；改 Store 或调度前先从这里定位对应断言：

- 真实 PostgreSQL 契约（需要 Testcontainers 启动数据库）：[`runtime/store/testing` 测试目录](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/)；[`PostgresqlHarnessSchemaTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessSchemaTest.java) 锁定七表形状，[`PostgresqlHarnessStoreTransactionTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreTransactionTest.java)、[`PostgresqlHarnessStoreConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreConcurrencyTest.java) 覆盖事务边界、锁序递增与句柄生命周期；[`PostgresqlEntryPathCacheTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlEntryPathCacheTest.java) 用 CTE 计数器把冷读一次 CTE、连续 append 零 CTE 与会话驱逐变成可断言事实；[`PostgresqlWorkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkTest.java) 与 [`PostgresqlWorkNotificationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkNotificationTest.java) 覆盖 claim、lease、wake、NOTIFY 唤醒与周期轮询语义；深删除顺序、命令邮箱、Entry 树与 Invocation 状态一致性由同目录按域拆分的其余 PostgreSQL 契约测试覆盖。
- 调度循环：[`dispatch` 测试目录](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/)；[`HarnessWorkDispatcherLifecycleTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherLifecycleTest.java)、[`HarnessWorkDispatcherHandoffTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherHandoffTest.java)、[`HarnessWorkDispatcherConfigTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfigTest.java) 覆盖单次 drain、周期调度、有界分发、执行器拒绝归还、平滑停止与配置边界。
- Realtime 与本地资源：[`postgresql` 测试目录](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/) 的 [`RealtimeNotificationCodecTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodecTest.java)、[`PostgresqlRealtimeEventSinkIntegrationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSinkIntegrationTest.java)、[`PostgresqlRealtimeEventSourceConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSourceConcurrencyTest.java) 覆盖规范编解码、超限降级、分块上界与 `Array` 释放、批量保序与回滚不产生通知、Source 围栏控制；[`LocalFileResourceStoreTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStoreTest.java) 覆盖哈希寻址、并发 create-only 发布、NOFOLLOW 打开与精确校验。
- 架构守卫：[`InfraModuleArchitectureTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/InfraModuleArchitectureTest.java) 守卫生产依赖范围与包结构。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、[Harness Provider](harness-provider.md)、[Harness Common](harness-common.md)、[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Schema](schema.md)、[Web](web.md)。
