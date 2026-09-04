# Harness Infra

## 定位

`harness-infra` 是 Harness Runtime 与 Tool 模块的基础设施适配层，负责将上层的强类型领域契约对接到具体的外部系统与本地运行时。该模块提供基于 PostgreSQL 的持久化事务与状态存储、Work 任务的调度分发循环、基于 PostgreSQL LISTEN/NOTIFY 的实时事件广播通道，以及基于本地文件系统的内容寻址资源存储。上层的会话推进、Turn 协议编排、重试策略与工具聚合等逻辑，由 Runtime 模块直接承载。

模块的生产依赖包括 `harness-common`、`harness-runtime`、`harness-tool`、`harness-environment`、Spring JDBC 与 PostgreSQL driver；测试环境通过 Flyway 和 Testcontainers 启动真实数据库进行验证。模块边界见 [`package-info.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/package-info.java) 与 [`pom.xml`](../../harness/infra/pom.xml)。

## 职责

### 核心职责

- 持久化映射：将 Runtime 强类型事务原语映射至 PostgreSQL 的七张 `harness_*` 表，保证严格的行级锁序与原子提交。
- 任务调度：通过 `harness_work` 的条件认领、租约时效、PostgreSQL 唤醒通知与后台周期轮询，构建可容灾恢复的 Work 调度分发循环。
- 实时广播：通过 `harness_realtime` 通道分发低延迟实时事件，支持客户端以数据库持久化快照为基准恢复状态。
- 本地对象存储：在固定受信任根目录下提供基于 SHA-256 内容寻址的只读引用、原子发布，以及写入和读取阶段的 digest/size 校验。
- 调度器生命周期：管理 Dispatcher 的容量受限任务提交、类型轮转调度、执行器过载保护与受控停机。

### 协作边界

- 领域编排归属：Thread 上下文聚合、TurnPlan 规划、重试策略、Tool 权限求值与模型交互由 Runtime 直接处理。
- 状态恢复基准：系统状态的单一事实源由 PostgreSQL 持久化表维护；NOTIFY 通道承担低延迟可用性提示，通过快照重拉对齐状态。
- 资源寻址范围：LocalFileResourceStore 存储路径完全由内容 SHA-256 摘要决定，仅在固定根目录下读写哈希对象。
- 调度关注点：Dispatcher 专注于任务认领、租约维护与向对应 Processor 投递任务；业务执行逻辑与状态跃迁由各 Processor 自行持久化。

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

表结构与迁移脚本由 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 的 Harness execution protocol 部分统一管理。架构约束见 [`InfraModuleArchitectureTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/InfraModuleArchitectureTest.java)，用于守卫生产依赖范围与包结构。

多节点部署中的 App 实例只通过同一 PostgreSQL 中的持久化状态、行锁、租约与 LISTEN/NOTIFY 协调。Dispatcher 实例采用对等模型，PostgreSQL 是 App-to-App 协调的唯一载体。

## 包架构

| 包路径 | 职责范围 | 核心类型 | 边界契约与外部依赖 |
| --- | --- | --- | --- |
| `fun.fengwk.kkstudio.harness.infra.dispatch` | Work 调度循环与 Processor 分发 | [`HarnessWorkDispatcher`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java), [`HarnessWorkDispatcherConfig`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java) | claim-only 短事务、round-robin 轮询、wake 合并、bounded handoff、executor rejection 归还 claim、stop 生命周期与 Environment `nodeInstanceId` 传递；依赖 `HarnessStore` 与 Processor |
| `fun.fengwk.kkstudio.harness.infra.postgresql` | PostgreSQL 持久化存储与通知实现 | [`PostgresqlHarnessStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java), [`PostgresqlHarnessTransaction`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java), [`PostgresqlHarnessRows`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessRows.java), [`PostgresqlRealtimeEventSink`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSink.java), [`PostgresqlRealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java), [`RealtimeNotificationCodec`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java) | 七表 durable protocol、严格事务锁序防御、首个数据库故障 poisoning、事务内 EntryPath 局部缓存、Work claim/lease/wake、Environment route 路由围栏与 NOTIFY 编解码；依赖 Spring JDBC、PostgreSQL driver 与 Jackson |
| `fun.fengwk.kkstudio.harness.infra.realtime` | 实时事件传输抽象端口 | [`RealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimeEventSource.java) | 实时事件 live projection overlay 订阅端口与生命周期围栏定义，通知损坏或失联触发 resync，durable snapshot 为唯一恢复事实源；仅依赖 `harness-runtime` |
| `fun.fengwk.kkstudio.harness.infra.resource` | 本地文件内容寻址对象存储 | [`LocalFileResourceStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStore.java) | 固定根目录、SHA-256 十六进制扁平命名、create-only 原子硬链接发布、NOFOLLOW 打开与精确 size/sha 校验；实现 `ResourceStore`，依赖 JDK NIO 与 `harness-common` |

## 核心模型 / API

### PostgreSQL HarnessStore、transaction 与七表

[`PostgresqlHarnessStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java) 在 `READ_COMMITTED` 隔离级别与 `PROPAGATION_REQUIRED` 传播行为下执行业务回调，事务名称统一标记为 `harness-store`。同一 Store 实例通过 ThreadLocal 检查并拒绝嵌套事务调用；若回调加入调用方已有的外层事务，最终提交由外层事务决定。`PostgresqlHarnessTransaction` 实现 [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 定义的强类型持久化原语，包括实体的插入、单点查询、行锁获取、属性更新、EntryPath 路径加载、Work 任务认领与流转，以及清理删除操作，专注于数据访问与并发控制。

Harness 持久化协议由七张核心表组成：

| 表 | 承载内容 |
| --- | --- |
| `harness_session` | Session 聚合根标识与创建时间 |
| `harness_entry` | append-only Entry Tree 节点数据与 payload |
| `harness_thread` | Session 归属、当前 head 游标、创建请求指纹（creation request hash）、YOLO 开关、命令序列号与版本号 |
| `harness_thread_command` | 有序 Command 邮箱、请求哈希、APPLIED 关联节点或 CANCELLED 取消标记 |
| `harness_model_invocation` | Model 请求规格、生命周期状态、尝试次数、流式检查点、执行结果与错误信息 |
| `harness_tool_invocation` | Tool 调用参数、工具绑定、审批记录、执行状态、副作用批次与错误信息 |
| `harness_work` | THREAD、MODEL、TOOL 调度邮箱、唤醒版本与租约信息 |

数据表设计遵循以下约束：
- `harness_entry` 通过 `(session_id, parent_entry_id)` 外键约束保证父子节点属于同一 Session；部分唯一索引 `uk_harness_entry_single_root` 保证每个 Session 仅能创建一个 ROOT 节点；
- `harness_thread_command` 以 `(thread_id, sequence)` 复合主键保证有序性，同时通过 `(thread_id, idempotency_key)` 唯一索引提供幂等保障；
- `harness_model_invocation` 以 `(thread_id, turn_start_entry_id)` 保持唯一，`result_entry_id` 在非空时全局唯一；
- `harness_tool_invocation` 以 `(assistant_entry_id, call_index)` 保持唯一，当状态非 `SUCCEEDED` 时 `effects` 字段必须为空副作用批次 `{"version": 1, "customEntries": []}`；工具批次执行完成后对应的 Invocation 记录会被物理删除；
- 表中所有时间字段均采用 `timestamptz(3)` 毫秒精度；实体版本号、业务 ID 与时间戳由应用层全权管理，数据库不自动递增版本号。

`loadEntryPath` 使用单次 `WITH RECURSIVE ... CYCLE` 递归查询，按 root-to-head 顺序读取不可变路径；查询在一次数据库往返中获取完整祖先链，并检测、拒绝父子环路。`PostgresqlHarnessTransaction` 内部维护事务局部的 `Map<UUID, EntryPath>` 正向缓存（entryPathCache）：
- 首次读取未命中缓存时执行单次递归 CTE，并将结果存入缓存；同一事务内重复读取相同 head 时直接命中内存缓存；
- 在同一事务中连续调用 `insertEntry` 时，系统从已缓存的父路径派生并追加新节点，直接更新缓存，使后续读取子节点实现 0 次 CTE 查询；
- 缓存遵循严格的事务隔离边界，各事务独立维护自身的局部缓存，并在事务 `close()` 时清空；
- 执行 `deleteEntries` 与 `deleteSession` 时，按 session 整体驱逐相关缓存，避免读取到失效数据。

`findRootEntry(sessionId)` 优先复用当前事务缓存中已存在的同 Session EntryPath ROOT 节点；在缓存未命中时通过部分唯一索引 `uk_harness_entry_single_root` 执行精确点查，并将单节点 ROOT 路径写回局部缓存。

`loadContributorCustomEntriesOnPath` 在完整路径缓存（entryPathCache）精确命中时，直接在内存中过滤并返回匹配的 CUSTOM Entry；当缓存未命中时，执行独立的窄递归 CTE，仅读取 ROOT、head、环路哨兵以及与指定 `contributorId` 匹配的 CUSTOM 节点，并验证路径的连通性与合法性。窄查询的结果保持独立，不会回填至完整路径缓存中。

### 事务锁序

涉及多实体的 Runtime 事务按以下统一层级获取行锁：

```text
Session (KEY SHARE / FOR UPDATE)
  -> Thread（UUID 升序）
  -> Commands（sequence 升序）
  -> ModelInvocation
  -> ToolInvocation siblings（assistantEntryId + callIndex 升序）
  -> Work（target type + UUID 升序）
```

在普通的 Command 写入与会话创建路径中，对 Session 获取 `FOR KEY SHARE` 共享锁，允许多个同级 Thread 并发执行写入；在涉及删除或独占变更时获取 `FOR UPDATE` 排他锁。

[`PostgresqlHarnessTransaction`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java) 在事务句柄内记录当前达到的最高锁阶梯等级（LockRank）、Thread UUID、Tool 调用序号以及 WorkTarget 排序。当检测到逆序获取锁的操作时，立即抛出 `IllegalStateException`。事务句柄严格绑定到创建它的单一线程，仅在当前回调作用域内生效。当事务执行期间发生任何底层数据库异常时，句柄会捕获并记录该首个故障（poisoning 防护），并在回调结束前通过 `rethrowDatabaseFailure()` 强制重新抛出，阻断破坏状态的提交。

### harness_work claim / lease / wake

`harness_work` 表以 `(target_type, target_id)` 复合主键标识调度任务，包含 `available_at`、`wake_version`、`lease_token`、`lease_until` 与 `required_environment_id` 列。

`requestWork` 在持有所属 Thread 锁的事务中调用，通过数据库 upsert 写入任务：

```text
available_at = least(current, requested)
wake_version = current + 1
lease_token / lease_until 保持不变
```

环境亲和性与冲突约束规则如下：
- `required_environment_id` 仅用于 TOOL 类型的 Work 任务，非 TOOL 任务传入非空值将抛出 `IllegalArgumentException`；
- 环境亲和性一旦绑定即行冻结，upsert 语句包含更新条件 `where (harness_work.required_environment_id is not distinct from excluded.required_environment_id or excluded.required_environment_id is null)`；
- 当既有 Work 记录已关联 `required_environment_id`，而后续请求传入了不一致的非空环境 ID 时，UPDATE 条件匹配 0 行，系统抛出 `IllegalArgumentException("conflicting requiredEnvironmentId for work target ...")` 拒绝冲突修改，确保执行环境不可漂移。

`claimNextWork` 是独立执行的短事务，负责从队列中选取一条就绪候选任务，并原子写入新的 `lease_token` 与 `lease_until`。候选筛选满足以下条件：
- 任务已到达可调度时间（`available_at <= statement_timestamp()`），且不存在未到期的活跃租约（`lease_until is null or lease_until <= statement_timestamp()`）；
- 排序按 `available_at, target_id` 升序排列，并使用 `FOR UPDATE SKIP LOCKED` 跳过其他并发事务正在认领的行；
- 租约过期状态由查询条件直接过滤判定。

在任务调度中，Environment route 路由围栏负责环境亲和性匹配。当 `harness_work.required_environment_id` 为空时，任一执行 claim 的 Dispatcher 节点都可认领；当 `required_environment_id` 非空时，SQL 条件通过 EXISTS 检查 `environment_connection`，要求该环境的连接记录满足 `owner_node_id` 等于当前 Dispatcher 节点的 `nodeInstanceId`、连接状态为 `READY` 且 `lease_until > statement_timestamp()`。只有持有就绪连接的节点才能 claim 任务；连接缺失、状态异常、租约过期或归属其他节点时，候选筛选 fail closed。

系统在调度与并发控制中划分了清晰的层次职责：
- 环境路由准入：Environment route 路由围栏针对外部网络拓扑与执行节点的环境连接状态实施准入筛选；
- 行级并发协调：PostgreSQL 的 `FOR UPDATE SKIP LOCKED` 让并发认领事务跳过已被其他 claim 事务锁定的候选行，减少认领等待；
- 任务所有权围栏：应用层通过 `lease_token` 与 `lease_until` 维护任务租约，只有持有当前有效 token 的执行体可以提交状态。

三层机制分别处理路由、行级竞争与执行所有权。Dispatcher 实例以对等方式参与 claim，由 PostgreSQL 协调并发认领。

所有权围栏与任务生命周期原语：
- `lockClaimedWork`：对已认领的任务加锁，并校验当前传入的 token 与数据库记录一致且 `lease_until > now`；
- `renewWork`：在持有 Work 行锁的前提下顺延 `lease_until` 租约时间；
- `completeWork`：作为终态检查（final Work fence），校验持有租约期间的 `wake_version`。若版本保持一致，说明执行期间无新唤醒到达，直接删除该 Work 行；若执行期间有新的唤醒推进了 `wake_version`，则清除当前租约并保留任务行，供后续调度循环再次认领；若所有权已失效，则抛出异常回滚当前事务；
- `rescheduleWork`：清除当前租约并更新任务的 `available_at`，保持当前的 `wake_version` 不变。

在同一个 Store 事务内，系统先通过 `requestWork` 完成 Work 记录的 upsert，随后调用 `pg_notify('harness_runtime_work', ...)` 发送唤醒信号。PostgreSQL 在事务提交后向监听方投递通知。唤醒通知提供低延迟提示，固定周期轮询、重连唤醒与租约超时回收共同提供持续发现和重新认领路径。

### Dispatcher lifecycle / fencing

[`HarnessWorkDispatcher`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java) 负责驱动 Work 调度分发循环：

```text
wake merge -> round-robin claim THREAD/MODEL/TOOL (passing nodeInstanceId)
  -> bounded worker handoff
  -> Processor.process(ClaimedWork)
  -> task finally wake
```

Dispatcher 在初始化时分配唯一的 `nodeInstanceId`，并在每次执行 `claimNextWork` 短事务时将其传递给存储层，以驱动环境路由围栏的判定。

调度器的生命周期与分发机制具有以下特征：
- 启动与停止：`start()` 方法向调度执行器注册固定周期的轮询任务（fixed-delay poll），并立即触发一次唤醒。多次调用 `start()` 保持幂等；调用 `stop()` 时取消轮询任务的 ScheduledFuture、将运行状态置为已停止并立即返回。外部注入的执行器生命周期保持独立，已提交给 Processor 的任务继续执行直至完成；
- 唤醒与轮询：任务排水（drain）逻辑通过 `wakeRequested` 与 `drainRunning` 两个原子标志以 CAS 方式协调，确保同一时间只有一个排空循环在运行，并在收尾阶段再次检查是否有新唤醒到达；
- 公平轮询：调度器在 THREAD、MODEL、TOOL 三种任务类型之间维护实例级的轮询游标（round-robin cursor），每次认领后递增游标，避免任务类型饥饿；
- 过载保护与租约归还：Worker 线程池采用快速失败拒绝策略（fail-fast rejection），例如 `AbortPolicy`。当线程池容量达到上限抛出拒绝异常时，Dispatcher 先通过 `lockClaimedWork` 校验当前节点仍持有该任务的有效租约，随后按配置的 `executorRejectionDelay` 延迟调用 `rescheduleWork` 归还租约，并立即终止当前排水循环，避免在过载状态下陷入认领与拒绝的紧密循环；
- 异常隔离：当 Processor 处理任务抛出 `RuntimeException` 时，Dispatcher 记录错误日志，并保留数据库中的现有租约。该任务在租约超时后由后续调度自动认领并尝试恢复。

### Realtime source / sink

`PostgresqlRealtimeEventSink` 负责向 PostgreSQL 的 `harness_realtime` channel 发送实时事件。当编码后的 Canonical EVENT JSON 荷载超过 `7900` 字节（UTF-8 编码）时，自动降级发送紧凑的 RESYNC 通知，并将原因标记为 `EVENT_TOO_LARGE`。[`RealtimeNotificationCodec`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java) 负责规范化编解码，对字段集合、重复字段、尾随字符与 JSON 格式执行确定性严格校验。

[`PostgresqlRealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java) 管理本地事件订阅与分发。长连接与底层监听由共享的 PostgreSQL 监听循环统一维护，通过调用 `onNotification` 投递收到的消息荷载；在连接建立或重连成功后，通过 `onResync` 触发同步。合法 EVENT 按照所属 Thread 精确分发给对应的本地订阅方；当收到畸变或未知消息，以及连接断开重连时，触发全部本地订阅方的快照恢复回调。内部通过全局生命周期锁、Source 级回调完成围栏与 Subscriber 级独立围栏保证并发安全，关闭后不再产生任何回调，用户业务回调始终在全局锁外部执行。

实时事件在系统整体架构中承担增量覆盖层职责：

```text
durable snapshot = recovery truth
NOTIFY EVENT    = low-latency best effort
NOTIFY RESYNC   = reread snapshot hint
```

### Local Resource

[`LocalFileResourceStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStore.java) 在固定的受信任根目录下以 `<sha256>`（64 位小写十六进制）作为扁平文件名管理对象：

```text
reference() -> canonical file:///root/<sha256>（无存储副作用）
put()       -> SHA-256 -> NOFOLLOW/CREATE_NEW temp -> force -> create-only hard link
read()      -> pinned root + sha filename + NOFOLLOW + exact size + exact digest
```

资源存取操作遵循以下规则：
- 引用生成（`reference`）：直接基于元数据生成规范的 `file:///root/<sha256>` 引用对象，纯内存计算，不产生存储副作用；根目录在构造时要求解析为规范绝对真实目录，且能生成符合 ResourceRef 规范的 ASCII URI；
- 写入发布（`put`）：先计算内容 SHA-256 摘要，在同一根目录下以 `NOFOLLOW_LINKS` 与 `CREATE_NEW` 选项写入临时文件并调用 `force(true)` 刷盘；随后通过 `Files.createLink` 原子创建硬链接发布对象（create-only）。当多个并发写操作写入相同内容时，首个成功建立硬链接的调用完成发布；捕获到 `FileAlreadyExistsException` 的并发调用则复用已有目标文件。无论何种分支，返回引用前均对最终文件执行精确的 size 与摘要校验；
- 内容读取（`read`）：校验传入引用的路径严格位于固定根目录下且文件名符合 SHA-256 格式，使用 `NOFOLLOW_LINKS` 打开文件通道，确认目标为普通文件而非符号链接，并精确读取声明的字节数，校验读取长度与内容摘要完全匹配；
- 异常传播：参数非法（如内容超限、路径越界）抛出 `IllegalArgumentException`；检测到数据损坏、符号链接、文件大小或摘要不符，以及文件系统不支持硬链接等存储异常时，抛出 `IllegalStateException`。

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

任务从 Runtime 提交到执行完成的完整生命周期流转如下：
1. Runtime 事务在提交状态变更的同时更新 `harness_work` 表（递增 `wake_version`），并向 `harness_runtime_work` channel 发送通知；
2. 事务成功提交后，通知由 PostgreSQL 投递给监听循环，进而调用 `dispatcher.wake()` 唤醒调度器；
3. Dispatcher 结合事件唤醒与周期性轮询，通过短事务原子认领到期任务（写入新 `lease_token` 与 `lease_until`）；
4. 任务提交至工作线程池并分发给对应的 ThreadProcessor、ModelProcessor 或 ToolProcessor；
5. 各 Processor 在其独立事务中持久化处理结果，并在通过所有权与版本校验后推进或删除 Work。

PostgreSQL 临时断连不会改写已提交的数据；Work 租约到期后，活跃的 Dispatcher 节点会重新认领任务并由 Processor 按持久化状态恢复处理。实时通知通道重连后，在线客户端重新同步数据库快照。

## 不变量、failure / recovery

- 事务完整性与故障阻断：Store 回调正常返回是当前事务提交的前提。任何未捕获的 RuntimeException 或 Error 都会触发事务整体回滚；事务句柄在完成前重新抛出首个底层数据库异常（poisoning），阻断损坏状态的提交。
- 锁序单调递增：多实体事务严格按照 Session -> Thread（UUID 升序）-> Commands（sequence 升序）-> ModelInvocation -> ToolInvocation（assistantEntryId + callIndex 升序）-> Work（target type + UUID 升序）的层级加锁；逆序加锁直接抛出 `IllegalStateException`。
- 任务写入与认领前提：`requestWork` 必须在持有对应 Thread 排他锁的事务中执行；Dispatcher 的 `claimNextWork` 是系统内唯一允许独立获取单个 Work 行级锁的短事务。
- 环境亲和性锁定：`harness_work.required_environment_id` 仅在 TOOL 任务中允许指定。`requestWork` 会校验既有环境绑定，当传入冲突的不同非空环境 ID 时，UPDATE 条件不匹配导致 0 行更新并抛出 `IllegalArgumentException` 拒绝修改。
- 调度路由准入：带有 `required_environment_id` 的任务，必须匹配状态为 `READY`、租约有效且所有者等于当前节点 `nodeInstanceId` 的环境连接记录。任何条件不满足时候选直接跳过（fail closed），底层查询失败使认领事务回滚。
- 终态防御（Final Work Fence）：Processor 的业务数据持久化先于 Work 终态变更。`completeWork` 在排他锁下校验 `lease_token` 与认领时的 `wake_version`；若执行期间有新唤醒推进了版本号，系统清除租约并保留任务行供后续调度；若租约已失效则抛出异常回滚事务。
- 执行器过载恢复：Worker 线程池拒绝任务时，Dispatcher 校验租约有效性后通过延迟 `rescheduleWork` 释放租约；若归还失败则等待租约自然超时后由后续调度恢复。
- 通知通道容错：NOTIFY 唤醒信号出现丢失、乱序或重复时，系统通过后台固定周期的定期轮询与租约到期重认领机制实现最终一致与收敛。
- 实时广播降级：EVENT 载荷超过 7900 字节时，Sink 改发 RESYNC；通知畸变、类型未知或监听连接重建时，Source 通知订阅方重新拉取持久化快照。数据库发送失败向上层传播，并由 Runtime 的实时事件边界隔离。
- 本地资源不可变性：LocalFileResourceStore 采用 create-only 写入与原子硬链接发布；任何阶段检测到文件损坏、符号链接或摘要大小不符，均抛出 `IllegalStateException` 阻断读取与发布。
- 级联删除顺序：级联清理按锁序自底向上执行，先清理下游的 Command、Model、Tool 与 Work 实体，再清理 Thread；Entry 树先删除叶子节点，最终删除 ROOT 节点与 Session。

## 配置 / 扩展

[`HarnessWorkDispatcherConfig`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java) 包含以下部署级配置：

```text
threadLeaseDuration
modelLeaseDuration
toolLeaseDuration
periodicPollInterval
executorRejectionDelay
maxDispatchTasks
```

配置项定义如下：
- `threadLeaseDuration` / `modelLeaseDuration` / `toolLeaseDuration`：针对 THREAD、MODEL、TOOL 任务分别配置的认领租约有效期；
- `periodicPollInterval`：调度器的后台固定延迟轮询周期，作为通知丢失时的兜底保障；
- `executorRejectionDelay`：Worker 线程池过载拒绝后，归还租约并推迟下次重试的等待时间；
- `maxDispatchTasks`：Dispatcher 本地允许排队与运行的最大分发任务数，用于控制单机分发负载。

生产组合根通过 `HarnessDispatcherProperties` 读取 `kk-studio.harness.dispatcher.*` 配置，并构建有界容量的 Worker 线程池。这些参数停留在进程启动配置边界。所有 Duration 参数必须为正的整毫秒；`maxDispatchTasks` 约束 Dispatcher 本地排队与运行中的 handoff 数量，外部 Model/Tool 执行并发由独立准入机制控制。`PostgresqlHarnessStore` 所需的 UUID 由外部注入的 `Supplier<UUID>` 提供。

架构支持的扩展与替换点：
- 存储实现：通过实现 `HarnessStore` 接口对接不同的关系型数据库；
- 实时传输：通过替换 `RealtimeEventSource` 与 `RealtimeEventSink` 对接不同的消息总线；
- 资源存储：通过实现 `ResourceStore` 对接分布式对象存储；
- 调度线程池：可注入自定义的执行器，但 Worker 线程池必须遵循快速失败拒绝契约。

## 测试与源码入口

### 源码入口

- [`PostgresqlHarnessStore.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java)、[`PostgresqlHarnessTransaction.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java)、[`PostgresqlHarnessRows.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessRows.java)
- [`HarnessWorkDispatcher.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java)、[`HarnessWorkDispatcherConfig.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java)
- [`PostgresqlRealtimeEventSink.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSink.java)、[`PostgresqlRealtimeEventSource.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java)、[`RealtimeNotificationCodec.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java)
- [`LocalFileResourceStore.java`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStore.java)、[`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql)

### 关键测试守卫

- [`InfraModuleArchitectureTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/InfraModuleArchitectureTest.java)：验证 Infra 模块的生产依赖仅限于 Common、Runtime、Tool、Environment、Spring JDBC 与 PostgreSQL。
- [`PostgresqlHarnessSchemaTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessSchemaTest.java)、[`PostgresqlHarnessStoreTransactionTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreTransactionTest.java)：验证七表 schema 结构、事务边界控制与句柄生命周期。
- [`PostgresqlEntryPathCacheTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlEntryPathCacheTest.java)：验证事务内 EntryPath 局部缓存、首次持久化读取与连续 append 的 CTE 执行计数、事务隔离与基于 session 的缓存驱逐。
- [`PostgresqlAcceptCommandsRootQueryTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlAcceptCommandsRootQueryTest.java)：守护非 ROOT head 的新命令批次处理，保证完整路径 CTE 执行次数为 0，开销与 Entry 树深度无关。
- [`PostgresqlContributorCustomPathTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlContributorCustomPathTest.java)：验证窄查询在环路损坏时的 fail-closed 保护、冷缓存下的 0 次全路径 CTE 以及热缓存时的内存复用。
- [`PostgresqlHarnessStoreConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreConcurrencyTest.java)、[`PostgresqlInvocationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlInvocationTest.java)：验证并发锁序递增控制、Invocation 状态流转与终态事实的一致性。
- [`PostgresqlWorkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkTest.java)、[`PostgresqlWorkNotificationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkNotificationTest.java)：验证任务 claim、lease、wake、NOTIFY 唤醒与周期轮询语义。
- [`HarnessWorkDispatcherLifecycleTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherLifecycleTest.java)、[`HarnessWorkDispatcherDrainTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherDrainTest.java)、[`HarnessWorkDispatcherHandoffTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherHandoffTest.java)：验证单次排空循环、轮询调度、有界分发、执行器拒绝处理与平滑停止。
- [`PostgresqlRealtimeEventSourceConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSourceConcurrencyTest.java)、[`PostgresqlRealtimeEventSinkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSinkTest.java)、[`RealtimeNotificationCodecTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodecTest.java)：验证 Source 围栏控制、超限降级发送 RESYNC 与规范编解码。
- [`LocalFileResourceStoreTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/resource/LocalFileResourceStoreTest.java)：验证基于内容的哈希寻址、并发 create-only 原子发布、NOFOLLOW 打开与精确 size/sha 校验。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Common](harness-common.md)、[Harness Runtime](harness-runtime.md)、
[Harness Tool](harness-tool.md)、[Harness Environment](harness-environment.md)、[Schema](schema.md)、[Web](web.md)。
