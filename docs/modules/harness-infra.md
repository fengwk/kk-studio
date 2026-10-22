# Harness Infra

`harness-infra` 将 Runtime 的 Entry、命令、Invocation 与 Work 端口适配到 PostgreSQL。行锁协调写入，lease token/deadline 限定执行所有权，Work 行支持重启后的认领恢复；LISTEN/NOTIFY 降低发现延迟。

模块把 Runtime 的强类型事务原语与 Realtime 端口适配到 PostgreSQL 与 PostgreSQL 通知通道；生产依赖为 `common`、`work`、`harness-runtime`、`harness-environment`、Jackson 3（`tools.jackson`，经 `harness-runtime` 传递）、Spring JDBC 与 PostgreSQL driver，测试通过 Flyway 与 Testcontainers 起真实数据库。模块依赖方向由仓库检查 [`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 守护，表结构统一由 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 管理。

多节点部署中的 App 实例互为对等体，只通过同一 PostgreSQL 里的持久化状态、行锁、租约与 NOTIFY 协调；进程内没有任何跨节点共享的内存协调器。

## 事务边界与锁序

[`PostgresqlHarnessStore`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessStore.java) 在每个回调外层开一个 `READ_COMMITTED`、`PROPAGATION_REQUIRED`、名字为 `harness-store` 的事务；同一 Store 实例用 `ThreadLocal` 显式拒绝嵌套事务，回调加入调用方外层事务时由外层决定最终提交。

[`PostgresqlHarnessTransaction`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessTransaction.java) 实现 [`HarnessStore.Transaction`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 的全部原语，并在句柄内记录当前达成的最高锁阶梯，实现层保证 Runtime 声明的锁序真的被遵守：

```text
前置 advisory 围栏：task 准入（仅接受路径）-> tree（根 UUID 升序）
行锁阶梯：Session -> Thread -> Command -> Model -> Tool -> Work
```

advisory 围栏与六级行锁分别记录。Thread 按 UUID、Command 按 sequence、
Tool siblings 按 assistantEntryId/callIndex、Work 按 type/UUID 排序。
持有执行树锁时，生命周期交付允许回写同树 Command；其他逆序请求立即失败。

执行树由 Thread 行上不可变的 `parent_thread_id` 与 `root_thread_id` 表达：根直接取自 `root_thread_id`，涉及整棵树的读写先在根上取 `pg_advisory_xact_lock`；一次事务涉及多棵树时按根 UUID 升序依次取锁。ThreadProcessor 的 Join 交付与 Stop 只锁自身/目标子树与直接父，不回溯完整祖先链；`findAncestorChain` 仅供 spawn 接受、深度判定等确实需要逐级祖先的路径用 `WITH RECURSIVE` 回溯。`lockTree` 必须在任何业务行锁之前取得（否则抛 `IllegalStateException`）。`getThreadTree` 在这把树锁内用 `listThreadsByRoot` 取同根全部 Thread，再按 UUID 升序锁定这些 Thread、读取 Command 并投影各节点 Invocation。Model/Tool 的状态推进与查询在各自 owning Thread 行上线性化；Model checkpoint 仅锁 Invocation/Work，可独立更新，不受树锁或 Thread 行锁阻塞，因此整树投影不是所有 checkpoint 的同一时刻快照。投影读取不新增 Model/Tool 行锁，避免 Tool 到 Model 的逆序加锁。

`lockJoinAdmission` 使用与树锁隔离的双 int advisory key，在任何树锁和行锁之前串行化 task 接受。`countIncompleteChildJoins` 统计指定父 Thread 的未完成 Join，`countIncompleteSubagentJoins` 跨所有根统计 `parent_thread_id` 非空且 `terminal_entry_id` 为空的 Join，原子执行父级与全局并发准入。每次接受新的子任务 Join 占用一个额度，Join 结算后释放；子 Thread 后续普通交互不重新占用额度。普通执行推进和无父 one-shot 不取全局准入锁。

命令写入、ThreadProcessor Entry 物化、手工压缩与会话创建只取 Session `FOR KEY SHARE`，让同 Session 的兄弟 Thread 并发推进；会话删除与 `renameSession` 才升级为 `FOR UPDATE`。Runtime 的约定是：任何要在 Thread 行锁之后插入 `harness_entry` 的事务先持有父 Session 的 `KEY SHARE`，否则外键会在插入时隐式补取 Session 锁，与深删除形成 `Thread -> Session` 逆序。实现层在显式请求新锁时按已达成阶梯检测逆序并立即抛 `IllegalStateException`（`insertEntry` 自身不做该判定）；句柄严格绑定创建它的线程。

事务期间任何底层数据库异常都被句柄记录为首个故障（poisoning），并在回调返回前由 `rethrowDatabaseFailure()` 强制重抛，防止把被污染的连接继续用于提交。

## 九表与 EntryPath 读取

| 表 | 承载内容 |
| --- | --- |
| `harness_session` | Session 聚合根标识、显示名称与创建时间 |
| `harness_entry` | append-only Entry Tree 节点与 payload |
| `harness_thread` | Session 归属、不可变执行父关系、head 游标、持久 `execution_control`（`RUNNABLE` / `STOPPED`）、`input_through_sequence` 接纳水位、creation request hash、显示名称、`root_thread_id` 不可变执行根与 `yolo_enabled` 根开关、命令序号与 version |
| `harness_thread_command` | 有序命令邮箱、请求哈希与 `applied_entry_id`（APPLIED 时指向应用的 Entry，未应用为 null）；CANCELLED 用 `stop_request_id` + `cancelled_at` 成对标记 |
| `harness_model_invocation` | Model 请求规格、状态、attempt、流式 checkpoint、结果与错误 |
| `harness_tool_invocation` | Tool 调用参数、绑定、审批记录、状态、副作用批与错误 |
| `durable_work` | 全部后台调度共用的邮箱；Harness 只读写 THREAD / MODEL / TOOL 行 |
| `harness_thread_join` | 一次委派的 join 凭据：源命令 `(child_thread_id, source_command_sequence)`、`terminal_entry_id` / `final_answer_entry_id` 冻结回执与 `delivery_command_sequence` |
| `harness_thread_stop_receipt` | 一次 Stop 在每个受影响 Thread 上的持久回执：`stop_request_id`、`stopped_turn_end_entry_id`、`cancelled_command_count` 与 `cancelled_inputs`；集合身份为 `(root_thread_id, root_stop_request_id)` |

数据库只做形状防御，语义由应用层负责：`(session_id, parent_entry_id)` 外键保证父子同 Session，部分唯一索引 `uk_harness_entry_single_root` 保证每个 Session 至多一个 ROOT；`harness_thread_command` 以 `(thread_id, sequence)` 为主键、`(thread_id, idempotency_key)` 唯一；`harness_model_invocation` 以 `(thread_id, turn_start_entry_id)` 唯一；`harness_tool_invocation` 以 `(assistant_entry_id, call_index)` 唯一，且非 `SUCCEEDED` 时 `effects` 必须为空批 `{"version": 1, "customEntries": []}`；`durable_work` 要求租约 token 与租约到期时刻同空同非空、且 `required_environment_id` 只允许出现在 `TOOL` 行。`harness_thread` 要求 `execution_control` 取 `RUNNABLE` / `STOPPED` 且 `input_through_sequence < next_command_sequence`；`harness_thread_command` 的 `applied_entry_id` 与 `cancelled_at` 互斥、CANCELLED 必须 `stop_request_id` 与 `cancelled_at` 成对；`harness_thread_join` 的 `final_answer_entry_id` 只在 `terminal_entry_id` 非空时可非空、`delivery_command_sequence` 非空必须已 matched 且父 Thread 非空；`harness_thread_stop_receipt` 以 `(thread_id, stop_request_id)` 为主键、集合身份为 `(root_thread_id, root_stop_request_id)`，`cancelled_inputs` 必须是 JSON array。所有时间列是 `timestamptz(3)` 毫秒精度，version 与业务 ID 完全由应用生成。

执行根约束要求「无父」与「`root_thread_id` 指向自身」等价，`root_thread_id` 有 Thread 外键；
`yolo_enabled` 只允许出现在执行根上。根开关更新不递归写子行，执行根与父关系均在创建时固定。

`loadEntryPath` 用单条 `WITH RECURSIVE ... CYCLE id SET is_cycle USING path` 递归 CTE 自 head 回溯到 ROOT，一次往返读出整条不可变路径并按 `depth desc` 输出，同时以环路哨兵检测并拒绝父子成环。句柄内维护事务局部 `Map<UUID, EntryPath>` 正向缓存：冷读一次 CTE 后回填；同事务内连续 `insertEntry` 直接从已缓存父路径派生新节点并更新缓存，后续读取子节点 0 次 CTE；缓存只在本事务可见，`close()` 清空；`deleteEntries` / `deleteSession` 按 session 整体驱逐，避免读到失效数据。`findRootEntry` 优先复用缓存中的 ROOT，未命中时借 `uk_harness_entry_single_root` 点查并把单节点路径写回缓存。[`PostgresqlHarnessRows`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessRows.java) 负责行映射与毫秒精度校验。

`loadContributorCustomEntriesOnPath` 在完整路径缓存精确命中时直接在内存过滤 CUSTOM Entry；未命中时走一条窄递归 CTE，只读 ROOT、head、环路哨兵与匹配该 `contributorId` 的 CUSTOM 节点并验证连通性，结果不回填完整路径缓存。

深删除按阶梯取锁：`deleteThreads` 将 UUID 排序，但重复 ID 直接抛 `IllegalArgumentException`，不静默去重。目标 Thread 必须全部已加锁且属于已锁执行树；仍被 join 引用、或删除父 Thread 却遗漏其存活子 Thread 时同样拒绝。随后依次锁 Command、Model、Tool、Work，删除顺序为 Work、Command、Tool、Model，最后整批删除 Thread。`deleteEntries` 叶子优先逐层删除直到只剩 ROOT，Session 由独立的 `deleteSession` 删除。

## Work 的 claim / lease / wake

`durable_work` 以 `(target_type, target_id)` 为主键，列含 `available_at`、`wake_version`、`lease_token`、`lease_until` 与 `required_environment_id`。同一张表还承载 Project 的 `ISSUE` 与 Canvas 的 `CANVAS_RUN` 行，它们由 [`DurableWorkQueue`](../../work/src/main/java/fun/fengwk/kkstudio/work/DurableWorkQueue.java) 读写；Harness 的每条语句都按 `target_type` 过滤。行变为可领取时在同一事务内向共享通道 `durable_work` 发送 payload 为 target type 的提示，组合根按类型只唤醒对应的 dispatcher。

`requestWork` 必须在持有所属 Thread 锁的事务内调用，upsert 语义是纯函数式推进；一次 request 只表达「立即唤醒」，due 由数据库权威时间写入，调用方传入的 `now` 只做毫秒精度校验：

```text
insert: available_at = date_trunc('milliseconds', statement_timestamp()), wake_version = 1, lease 保持 null
conflict: available_at = least(current, statement_timestamp())
          wake_version = current + 1
          lease_token / lease_until 保持不变
```

`rescheduleWork` 接收非负整毫秒 Duration。实现读取数据库当前时刻，在 Java 中计算 databaseNow.plus(delay) 并调用 Work.reschedule，再更新行；零表示立即。它清空租约、保留 wake_version：认领后未产生新 wake 时采用目标时刻，已有新 wake 时保留较早 available_at。数据库时间域决定相对延迟，调用方 now 只校验精度。

环境亲和性一旦绑定即冻结：upsert 的更新条件为 `durable_work.required_environment_id is not distinct from excluded.required_environment_id or excluded.required_environment_id is null`，因此既有行绑定了 `required_environment_id` 时，后续传入不一致的非空环境 ID 会让 UPDATE 命中 0 行，实现层随即抛 `IllegalArgumentException` 拒绝，执行环境不会漂移。

`claimNextWork` 是独立短事务，用一条 `FOR UPDATE SKIP LOCKED` 语句选候选并原子签发新租约，筛选条件是 `target_type` 匹配、`available_at <= statement_timestamp()` 且 `lease_until is null or lease_until <= statement_timestamp()`，排序为 `available_at, target_id` 升序。claim 与 heartbeat/lease 续租是系统内仅有的两类只取单条 Work 行锁的调度事务；`claimNextWork` 额外要求它必须是该事务中的第一次 Work 锁获取，运行时的业务事务则一律先锁 owning Thread。

环境路由围栏就嵌在这条 claim 之上：当 `required_environment_id` 为空时任何活跃 Dispatcher 节点都可认领；非空时用 `exists` 检查 `environment_connection` 中存在 `environment_id` 匹配、`owner_node_id` 等于当前 Dispatcher 的 `nodeInstanceId`、状态为 `READY` 且 `lease_until > statement_timestamp()` 的连接记录。断开、未就绪、归属他人或租约过期的环境一律不返回候选（fail closed），底层数据库故障则让整个 claim 事务回滚。这条路由围栏与 `FOR UPDATE SKIP LOCKED` 的行级并发控制、应用层 `lease_token` / `lease_until` 的所有权围栏分属三个层次，互不替代——路由回答「哪台机器该做」，行锁回答「谁先抢到」，租约回答「谁还在拥有」。

围栏原语沿同一层次展开，且全部以数据库时钟为唯一权威时间域：`lockClaimedWork` 校验 token 与数据库一致且 `lease_until > statement_timestamp()`；`renewWork` 在持锁前提下把 `lease_until` 延展到「`statement_timestamp()` + 传入 `Duration`」，现有租约已覆盖目标时点则保持不变并返回 `false`，绝不缩短。事务实现的 `notifyWorkAvailable` 向共享的 `durable_work` 通道执行 payload 为 target type 的 `pg_notify`：`requestWork`、保留新 wake 的 `completeWork` 与 `rescheduleWork` 都在各自事务内发通知，仅提交后投递。

## Dispatcher 生命周期

[`HarnessWorkDispatcher`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcher.java) 只做 claim、路由、有界 handoff、wake 合并、周期 poll 与 stop：它从不读取 Thread 或 Invocation 业务状态，claims 一律是 claim-only 短事务，typed 结果完全由 Processor 解释。

```text
wake merge -> round-robin claim THREAD/MODEL/TOOL（携带 nodeInstanceId）
  -> bounded worker handoff -> Processor.process(ClaimedWork)
  -> 任务收尾触发一次新的 wake
```

调度器持有实例级 `nodeInstanceId` 并把它传进每次 `claimNextWork`，这正是环境路由围栏判定的输入。`wakeRequested` 与 `drainRunning` 两个原子标志以 CAS 合并唤醒，保证同一时刻只有一个 drain 在跑，并在收尾时再检一次新唤醒；THREAD / MODEL / TOOL 之间用实例级 round-robin 游标轮转，避免类型饥饿；`start()` 注册 fixed-delay poll 并立即触发首次唤醒，运行期间重复调用是 no-op，`stop()` 后再调用则抛 `IllegalStateException`。外部注入的 drain / worker / poll 执行器由调用方管理，`stop()` 只取消 poll future 并立即返回，已进入数据库的 claim 可能提交但会在 handoff 前归还。

合并 wake 的单 drain、bounded handoff、容量计数、executor 拒绝归还、接收前拒绝与接收后失败的区分、stop 收尾与 fail-fast executor 校验收敛在 [`harness/common`](../../common/) 的 [`BoundedWorkDispatcher`](../../common/src/main/java/fun/fengwk/kkstudio/common/concurrent/BoundedWorkDispatcher.java)；Harness 只保留 round-robin 类型 claim、按类型路由的 Processor handoff 与 ownership-fenced 归还，因此同一份调度骨架被 Harness / Issue / Canvas 三个域复用，而各自的 claim、fencing 与失败策略仍在域内。

worker 线程池必须是 fail-fast 拒绝策略（`AbortPolicy` 一类），构造时显式检查，拒绝 `CallerRunsPolicy` / `DiscardPolicy` / `DiscardOldestPolicy`。容量打满抛拒绝异常时，Dispatcher 先 `lockClaimedWork` 确认本节点仍持有有效租约，再按 `executorRejectionDelay` 延迟 `rescheduleWork` 归还租约并终止当前 drain，避免在过载下陷入 claim-拒绝的紧密循环。Processor 抛 `RuntimeException` 时只记日志、保留数据库租约，等租约自然过期后由后续调度重新认领并按持久化状态恢复。

Work 完成与业务写入在同一事务提交：[`completeWork`](../../harness/runtime/src/main/java/fun/fengwk/kkstudio/harness/runtime/store/HarnessStore.java) 在排他锁下校验 `lease_token` 与认领时的 `wake_version`——版本未变说明期间没有新唤醒，直接删除该行；被新唤醒推进过则清空租约保留行交给下一轮；所有权已失效则抛异常回滚整个事务。

[`HarnessWorkDispatcherConfig`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfig.java) 是调度器的部署级配置契约，也是本模块唯一暴露给进程启动参数的部分：

```text
threadLeaseDuration / modelLeaseDuration / toolLeaseDuration
periodicPollInterval / executorRejectionDelay / maxDispatchTasks
```

前五个 Duration 必须是正的整毫秒，与 Store 的毫秒精度时间边界一致；`maxDispatchTasks` 只约束本机排队与运行中的 processor handoff 总数（到达上限即停止 claim），绝不约束异步 Model / Tool 的执行并发——那由各 Processor 及其注入的 Gateway / executor 决定。生产组合由 [`HarnessDispatcherProperties`](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/harness/configuration/HarnessDispatcherProperties.java)（前缀 `kk-studio.harness.dispatcher`）提供，它另外承载 worker 池容量 `worker.concurrency` / `worker.queue-capacity`，生产装配当前用同一个 `lease-duration` 填充 THREAD / MODEL / TOOL 三个 lease 字段。

## Realtime source / sink

```text
durable snapshot = 恢复事实源
NOTIFY EVENT     = 低延迟尽力投递
NOTIFY REFERENCE = oversize 事件从 origin 有界回拉
NOTIFY RESYNC    = 提示重读快照
```

[`RealtimeBus`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimeBus.java) 是 Runtime 与 Web 共同使用的本地优先总线，同时实现 sink 与 source。`append` 先同步投递本机订阅，再按集群节点存在快照决定是否跨 PostgreSQL 发布；`appendAll` 同理先完成整批本地投递，再最多一次远端批量。本地回调若关闭 bus，返回后立即停止，不再查询 presence 或发布远端；单节点部署不产生任何实时 NOTIFY。每条远端 envelope 都携带 `originNodeId`，接收方丢弃自身 origin，因此发布节点不会因 LISTEN 回送而重复投递；远端事件只分发给本地订阅，绝不再次发布。收到 `REFERENCE` 时，bus 只从可信 `cluster_node` 租约解析 origin 并回拉 canonical event，校验 Thread 后本地投递一次；回拉期间 bus 关闭则不再投递。回拉是阻塞 HTTP，在 bus 自有的虚拟线程 executor 上执行，不占用唯一的 realtime LISTEN 线程：该 Thread 开一条有序通道，回拉完成前到达的同 Thread 通知按到达顺序排在其后，其它 Thread 照常即时投递；通道积压达到 `256` 条时丢弃积压、以一条 `LANE_OVERFLOW` RESYNC 代替，关闭 bus 会中断进行中的回拉并释放 executor。失败 warning 只记录 origin/Thread/payload id 与异常类别，不输出 HTTP body 或原 JSON，并对目标 Thread 触发 RESYNC。本地投递完成后，节点存在查询或远端发布失败直接向上抛出，不使用静默单机兜底。

[`PostgresqlRealtimeEventSink`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSink.java) 向 `harness_realtime` 通道投递实时事件：单条 `append` 用参数化 `pg_notify` 发送带本节点 origin 的 canonical envelope；UTF-8 载荷超过 `7900` 字节时，先把事件自身的完整 canonical JSON 写入 owner 的 [`RealtimePayloadBuffer`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimePayloadBuffer.java)，再发送只含 `originNodeId` / `threadId` / `payloadId` 的紧凑 `REFERENCE`。buffer 只驻留内存，单事件上限 `2MiB`、总字节 `64MiB`、最多 `256` 条、TTL `30s`，容量压力按写入顺序驱逐最旧条目；单事件超限或 buffer 已关闭时明确 RESYNC，原因记为 `PAYLOAD_UNAVAILABLE`，绝不截断内容或拆成大量 NOTIFY 帧。批量 `appendAll` 把已提交批次压缩为「每分块一次往返」，分块同时受 `256` 个事件与 `256KiB` 编码载荷双重上界约束，用 `select pg_notify(?, payload) from unnest(?::text[]) with ordinality ... order by ord` 按输入顺序发出，因此投递顺序与逐条 `append` 完全一致、SQL 往返数等于分块数而不是事件数；每个事件仍各自独立处理。JDBC `Array` 在成功与异常路径都必须释放，数据库异常直接向上传播，由 Runtime 既有的实时事件边界隔离。

[`RealtimeNotificationCodec`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodec.java) 是实时通道的编解码入口，`EVENT`、`REFERENCE` 与 `RESYNC` 都必须携带 canonical `originNodeId`，对字段集合、重复字段、尾随字符与 JSON 形状做确定性严格校验，不存在旧 envelope 兼容路径；[`PostgresqlHarnessRows`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlHarnessRows.java) 的 `cancelled_inputs` 等 JSON 列各自持有独立 mapper。两者都使用不可变 `JsonMapper`，开启严格读取（`STRICT_DUPLICATE_DETECTION`、`FAIL_ON_TRAILING_TOKENS`）与 `USE_BIG_DECIMAL_FOR_FLOATS`，因此自由 JSON 数值按 `BigDecimal` 搬运，不会在 double 中退化为 `Infinity` / `0`；Jackson 3 把空白输入解析为 `MissingNode`，空白与畸形载荷一样被拒绝。

PostgreSQL `jsonb` 会按自身规则规范化极端量级的数字文本（例如 `1E+309` 展开为等值全数字），因此数值精确但数字文本可能与写入时不同；应用层契约是「数值不失真」，不是「文本逐字相同」。

[`PostgresqlRealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSource.java) 实现 [`RealtimeEventSource`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimeEventSource.java)：它只负责本地订阅分发与关闭围栏，合法 EVENT 按所属 Thread 精确分发，畸变、未知消息与重连触发全部订阅方重读快照。并发由全局生命周期锁、Source 级完成围栏与 Subscriber 级独立围栏三层保证，关闭后不再产生任何回调，用户业务回调始终在全局锁之外执行。

`harness_realtime` 的 `LISTEN` 由独立于 durable-work / 失效提示的专用连接承载：实时高频小帧不会阻塞控制通知，实时连接失联重连时也只对实时订阅触发 `onResync`。节点存在判定来自 `cluster_node` 的未过期租约快照，并以一秒级缓存复用，避免每个增量事件都执行 SQL；查询失败向上传播，不退化为“单节点”。

通知的丢失、乱序或重复都不会改变最终状态：固定周期 poll、重连后的重读与租约过期重认领共同构成收敛路径。

## 包架构

| 包名 | 职责 | 边界 |
| --- | --- | --- |
| `infra.dispatch` | `HarnessWorkDispatcher` 与 `HarnessWorkDispatcherConfig`：Work 调度循环、类型轮转、有界 handoff、wake 合并与 stop | claim-only 短事务；不读业务状态、不解释 Processor 结果 |
| `infra.postgresql` | `PostgresqlHarnessStore`、`PostgresqlHarnessTransaction`、`PostgresqlHarnessRows`、`PostgresqlRealtimeEventSink` / `PostgresqlRealtimeEventSource` / `PostgresqlRealtimeChannel` / `RealtimeNotificationCodec` | 九表 durable 协议、树锁与锁序防御、poisoning、事务内 EntryPath 缓存、claim / lease / wake 与环境路由围栏、NOTIFY 编解码 |
| `infra.realtime` | `RealtimeEventSource` 订阅端口与 `RealtimeBus` 本地优先总线 | 先本地投递，再按节点快照跨 PG 发布；origin 去回环；durable snapshot 是唯一恢复事实源 |

## 源码与测试

生产源码只有三个包：[`postgresql`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/postgresql/)（Store、行映射、Work 通道、Realtime sink/source 与通知编解码）、[`dispatch`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/dispatch/)（Work 调度循环）与 [`realtime`](../../harness/infra/src/main/java/fun/fengwk/kkstudio/harness/infra/realtime/)（订阅端口）。

测试分四组，入口都在 [`harness/infra/src/test`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/) 下；改 Store 或调度前先从这里定位对应断言：

- 真实 PostgreSQL 契约（需要 Testcontainers 启动数据库）：[`runtime/store/testing` 测试目录](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/)；[`PostgresqlHarnessSchemaTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessSchemaTest.java) 锁定九表形状（含 `harness_thread_join` 的 terminal/final-answer 与交付约束、`harness_thread_stop_receipt` 回执），[`PostgresqlHarnessStoreTransactionTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreTransactionTest.java)、[`PostgresqlHarnessStoreConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlHarnessStoreConcurrencyTest.java) 覆盖事务边界、锁序递增与句柄生命周期；[`PostgresqlEntryPathCacheTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlEntryPathCacheTest.java) 用 CTE 计数器把冷读一次 CTE、连续 append 零 CTE 与会话驱逐变成可断言事实；[`PostgresqlWorkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkTest.java) 与 [`PostgresqlWorkNotificationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/runtime/store/testing/PostgresqlWorkNotificationTest.java) 覆盖 claim、lease、wake 与 NOTIFY 唤醒；周期轮询由下列 Dispatcher 生命周期测试覆盖。深删除顺序、命令邮箱、Entry 树与 Invocation 状态一致性由同目录按域拆分的其余 PostgreSQL 契约测试覆盖。
- 调度循环：[`dispatch` 测试目录](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/)；[`HarnessWorkDispatcherLifecycleTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherLifecycleTest.java)、[`HarnessWorkDispatcherDrainTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherDrainTest.java)、[`HarnessWorkDispatcherHandoffTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherHandoffTest.java)、[`HarnessWorkDispatcherConfigTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/dispatch/HarnessWorkDispatcherConfigTest.java) 覆盖单次 drain、周期调度、有界分发、执行器拒绝归还、恢复与等待态的 handoff、平滑停止与配置边界。
- Realtime：[`postgresql` 测试目录](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/) 的 [`RealtimeNotificationCodecTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/RealtimeNotificationCodecTest.java)、[`PostgresqlRealtimeEventSinkTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSinkTest.java)、[`PostgresqlRealtimeEventSinkIntegrationTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSinkIntegrationTest.java)、[`PostgresqlRealtimeEventSourceTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSourceTest.java)、[`PostgresqlRealtimeEventSourceConcurrencyTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/postgresql/PostgresqlRealtimeEventSourceConcurrencyTest.java)、[`RealtimeBusTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimeBusTest.java) 与 [`RealtimePayloadBufferTest.java`](../../harness/infra/src/test/java/fun/fengwk/kkstudio/harness/infra/realtime/RealtimePayloadBufferTest.java) 覆盖规范编解码、REFERENCE/完整内容回拉、UTF-8/大小/TTL/驱逐边界、分块上界与 `Array` 释放、批量保序与回滚不产生通知、Source 围栏控制、本地优先投递、跨节点一次发布、origin 去回环、REFERENCE 通道保序/跨 Thread 不阻塞/溢出 RESYNC、回调关闭与关闭边界。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Runtime](harness-runtime.md)、[Harness Provider](harness-provider.md)、[Common](common.md)、[Harness Environment](harness-environment.md)、[Schema](schema.md)、[Web](web.md)。
