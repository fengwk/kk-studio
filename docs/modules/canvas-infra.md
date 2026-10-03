# Canvas Infra 模块

`canvas-infra` 实现 [Canvas Core](canvas-core.md) 的持久化与应用服务端口，将命令批、Snapshot 一致性读、资源生命周期和 Function Run 保存为 `canvas_*` 行。Run 行承担 durable queue；claim、lease 与短事务 CAS 支持多节点执行和崩溃恢复，提交结果不确定时转为 UNKNOWN 等待人工核对。Web 装配 Infra，Platform 提供 Storage 与 Function adapter。

生产依赖固定为 Core、`convention4j-spring-boot-starter`、`spring-boot-starter-aspectj`、`mybatis-spring-boot-starter` 与 `jackson-databind`（见 [`canvas/infra/pom.xml`](../../canvas/infra/pom.xml)）；PostgreSQL、Flyway、Testcontainers 只在 test scope。[`CanvasInfraArchitectureTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/CanvasInfraArchitectureTest.java) 同时扫描 import 前缀与 POM，禁止反向依赖 Platform/Harness/Web。自动配置入口是 [`CanvasInfraAutoConfiguration.java`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/CanvasInfraAutoConfiguration.java)，[`AutoConfiguration.imports`](../../canvas/infra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports) 指向它，并显式启用 `CanvasFunctionRuntimeProperties`。

## Graph 投影到行

持久化由 Repository 与 Store 实现，底层注入各个 MyBatis mapper：

| 组件 / mapper | 关键职责与 SQL |
| --- | --- |
| [`PostgresqlCanvasStore`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasStore.java) | 实现 [`CanvasStore`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasStore.java)，聚合 document、node、group 与 dedup 的行级操作 |
| [`CanvasDocumentMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasDocumentMapper.java) | `FOR UPDATE` / `FOR KEY SHARE` 行锁读、`advanceRevision` CAS 推进、document 插入与删除 |
| [`CanvasNodeMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasNodeMapper.java) | node 行 CRUD、`FOR UPDATE` 锁、名称/几何/分组/Function 更新 |
| [`CanvasGroupMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasGroupMapper.java) | group 行 CRUD 与按画布查询；按画布删除由命令服务逐行调用 |
| [`CanvasResourceMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasResourceMapper.java) | 由 [`PostgresqlCanvasResourceRepository`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasResourceRepository.java) 使用：owner attach/detach、按节点与按画布删除、`FOR UPDATE` 读 |
| [`CanvasCommandDedupMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasCommandDedupMapper.java) | `(canvas_id, idempotency_key)` 的 request hash 与 accepted revision 记账 |
| [`CanvasFunctionRunMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionRunMapper.java) | 由 [`PostgresqlCanvasFunctionRunRepository`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasFunctionRunRepository.java) 使用：READY 插入、`replaceTerminalWithReady`、`checkpoint`、`transitionTerminal`、`markUnknown`、`resumeUnknown`、`resolveUnknownTerminal`、`cancelActive` |
| [`CanvasFunctionResourcePinMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionResourcePinMapper.java) | 由 [`PostgresqlCanvasFunctionResourcePinRepository`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasFunctionResourcePinRepository.java) 使用：Run INPUT/OUTPUT 资源 pin 批量写入、按 run/node/canvas 查删、按 resource 计数 |
| [`CanvasFunctionWorkMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkMapper.java) | 由 [`CanvasFunctionWorkStore`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkStore.java) 使用：单条 CTE `claimNext`（`FOR UPDATE SKIP LOCKED`）、`renew`、`reschedule`、`countOwned` |

命令写服务由 [`PostgresqlCanvasCommandService`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasCommandService.java) 实现。写路径锁定 `canvas_document` 短事务：
1. 校验幂等性：同一 `idempotencyKey` 已存在且 `requestHash` 一致时直接返回记录的接受回执（`Accepted(CanvasPatch.receipt(acceptedRevision))`），指纹不一致时抛 `CanvasConflictException(IDEMPOTENCY_CONFLICT)`；
2. 加载权威快照并调用 `CanvasCommandPlanner.plan` 规划；
3. 若存在冲突则整体返回 `Conflicted(conflicts)`，不产生任何写入；
4. 若无冲突且产生变化，按规划步骤依次执行 `CanvasMutation`，推进 `revision` 并写入 `CommandDedup`；
5. 返回带完整实体变化的 `Accepted(patch)`。

删除画布先锁 Document：不存在时直接返回；存在 `READY`、`RUNNING` 或 `UNKNOWN` Run 时整体拒绝，不释放资源。通过后依次释放 pin（[`CanvasResourceLifecycle.releaseCanvasPins`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceLifecycle.java)）、删除终态 Run、删除 Resource 行并释放全局 Blob 引用（[`CanvasResourceLifecycle.deleteCanvasResources`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasResourceLifecycle.java)）、删除 Node、Group、CommandDedup，最后删除 Document。Blob 引用释放失败会使整个删除事务回滚。

Snapshot 读路径是 [`PostgresqlCanvasQueryService.findSnapshot`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasQueryService.java)。它把 document、run 列表、资源按 owner 分组、node、group 以及从 node function args 投影出的 references 装配成 [`CanvasSnapshot`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasSnapshot.java)，并在返回前重读对比 document 与 runs：若读取窗口内发生并发提交则重新读取，保证客户端拿到的 graph 与 Run 投影属于同一代次。

## Function run 的完整生命周期

### 启动：冻结输入，产出 READY

[`CanvasFunctionRunTransactions.start`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunTransactions.java) 是事务边界，锁序固定为 document → node → run：

```text
lock document (FOR UPDATE) -> lock node -> lock current run (FOR UPDATE)
  -> requestId 与现有 Run 相同：直接返回既有 Run（不推进 revision）
  -> 现有 Run 处于 READY/RUNNING/UNKNOWN 且 requestId 不同：conflict
  -> Catalog require(name) + requireAvailable；CanvasFunctionArgsCodec 严格解码 args
  -> 冻结 manifest：每个引用节点必须存在、index 必须在资源范围内且必须是 blob
  -> 校验 frozen blob facts 与 function reference policy
  -> 冻结输出计划：按 function 的 outputs 逐槽位预分配 resource id，构造 submitState=PENDING、stage=QUEUED 的 frozen plan
  -> adapter.preflight(frozen)
  -> 释放上一个 Run 的 pin
  -> 插入 READY Run（已有终态 Run 时用 replaceTerminalWithReady 的 CAS 替换）
  -> 写入 INPUT pin（pin 必须晚于 Run 行存在；OUTPUT pin 在物化事务中与 Resource 同事务写入）
  -> advanceRevision(revision + 1)
```

冻结的含义是：引用在启动瞬间取 source node 的 resource identity、blob id 与权威媒体事实。之后无论用户改了配置、解除引用还是替换了源资源，这个 Run 的输入都不变。`preflight` 在事务内、在写入 READY 之前执行，因此被拒绝的配置不会留下队列事实。

### claim：数据库就是队列

`canvas_function_run` 的主键是 `node_id`，每个 Function 节点只有一行「当前/最后一次」Run。[`CanvasFunctionWorkMapper.claimNext`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkMapper.java) 用单条 CTE 完成认领：

```sql
-- READY 且 available_at <= now，或 RUNNING 且 lease_until <= now（过期租约）
-- order by (READY ? available_at : lease_until), created_at, node_id
-- for update skip locked limit 1
update canvas_function_run
   set status='RUNNING', attempt=attempt+1, available_at=null,
       lease_token=?, lease_until=?, updated_at=?
 where node_id = candidate.node_id
```

`skip locked` 让多节点并发 claim 各自拿到不同行，没有内存队列、没有重试锁竞争。dispatcher 为每次 claim 生成 UUID 形式的 lease token；`renew`、`reschedule`、`isOwned` 与 Run 侧的 `checkpoint`、`transitionTerminal` 都在 SQL 的 `where` 里同时要求 `request_id`、`status='RUNNING'`、`lease_token` 匹配且 `lease_until > now`。token 过期或已被新 owner 接管时，`renew`/`isOwned` 返回 false；`checkpoint` 影响 0 行时在事务里被转成 [`CanvasFunctionInternalCancellation`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionInternalCancellation.java)，`transitionTerminal` 影响 0 行则是行锁后的 CAS 失败，抛 `IllegalStateException`（视为持久化不变量破坏）——旧 worker 的回调因此只能变成 no-op 或静默退出，不可能写坏新 Run。

### 执行、heartbeat 与终态

[`CanvasFunctionDispatcher`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionDispatcher.java) 由周期 poll 驱动（`scheduleWithFixedDelay`），并在启动时立即 wake 一次；它不是 `LISTEN` 的持有者。`start()` 之前 `wake()` 不生效，`stop()` 取消 poll 并让后续 `wake()` 直接返回。`drainOnce` 只在本地容量（`maxDispatchTasks`，默认 2）未满时 claim，把 Run 交给固定并发 worker executor；`SynchronousQueue + AbortPolicy` 保证没有第二层内存等待队列，executor 拒绝时立即用 `reschedule(now + rejectionDelayMillis)` 把 Run 还回 READY 并结束本轮 drain，避免 claim-reject 热循环。

postgres 的 NOTIFY 由组合根接进同一个共享 `LISTEN` 连接：[`ApplicationEventConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java) 把 `CanvasFunctionDispatcher.CHANNEL`（即 `canvas_function_work`）的 handler 指向 `dispatcher.wake()`，断连后由 `dispatcher::wake` 兜底。也就是说 NOTIFY 只提供低延迟，**正确性来自 poll 与 lease**：通知丢失、listener 重连、应用重启都能在下一次 poll 或租约过期后重新 claim。[`notify_canvas_function_work()`](../../schema/src/main/resources/db/migration/V1__schema.sql) 只在提交后行立刻可认领（`READY`、无 lease、`available_at <= now()`）时发空 payload；`canvas_revision` 通道只提示 document revision，客户端据此回读 Snapshot。

[`CanvasFunctionWorker.run`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionWorker.java) 执行两阶段协议：
1. 解析版本 4 state JSON 得到 frozen plan、`requireAvailable`；
2. 若 `submitState == SUBMITTING`，说明提交意图已持久化但进程曾崩溃导致状态不明，绝不重试提交，调用 `markUnknown` 将 Run 转换为 `UNKNOWN` 状态，退出自动调度并保留 pin 与目标资源等待人工核对；
3. 若 `submitState == PENDING`，在独立事务调用 `beginSubmit` 先行持久化 `SUBMITTING` 意图，随后调用 adapter 的 `submit`，外部确认后再经 `confirmSubmitted` 持久化 `SUBMITTED`；
4. 调用 adapter 的 `execute`（仅查询和物化预分配目标 resource id），确认返回值恰好是冻结目标 id，最后 `completeSuccess`。

执行期间 heartbeat 按固定间隔续租，**只**更新 `lease_until`，不触碰 document revision、Run state 或其他业务事实；续租返回 false 或抛 `RuntimeException` 时把 `ownershipLost` 置位，worker 随即停止新的 checkpoint 与 terminal 写入。Run 被 cancel、node/document 被删或租约被新 owner 接管则表现为短事务 CAS 失败，被转成 `CanvasFunctionInternalCancellation` 后静默退出。adapter 抛出普通异常时，worker 在仍持有所有权时用固定文案走 `failIfRunning`，Run 进入业务 `FAILED`；抛出 `CanvasFunctionUnknownException` 时改走 `markUnknown`。`Error` 不在此处吞掉而是上抛，交由进程外失败处理，其 Run 由租约到期后的恢复路径重新 claim。无论走哪条路径，worker 都在 `finally` 中取消 heartbeat 调度任务，不留下续租残留。

checkpoint 与终态都走 [`CanvasFunctionRunTransactions`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunTransactions.java) 的短事务方法：`start`、`beginSubmit`、`confirmSubmitted`、`checkpoint`、`markUnknown`、`resolve`、`cancel`、`completeSuccess`、`failIfRunning`。它们一律先锁 document 与 node，再锁 run，写完后 `advanceRevision(expectedRevision, expectedRevision + 1)`；CAS 在行锁内失败被视为持久化不变量破坏，直接抛错而不是静默重试。外部计算（adapter 的 HTTP、轮询、媒体处理）永远在事务之外。

成功与失败的资源处置不同：

- `completeSuccess` 要求输出计划的**每个槽位都已物化**且与槽位一一对应：媒体槽位必须是同画布、无 owner、有 blob 且 MIME 类型与槽位 kind 匹配的资源，`TEXT` 槽位必须已写入 `text_content` 且不持有 blob；槽位缺失或 ID 顺序不等于 `frozen.outputResourceIds()` 时拒绝 success（绝不发布半成品数组）。校验通过后由 Resource lifecycle 用整组输出替换节点当前 owned 资源（旧资源若仍被其他 Run pin 则只解 owner，否则删行并释放 blob 引用），最后把 Run 置为 `SUCCEEDED` 并释放本次 Run 的全部 pin。
- `failIfRunning` 与 `cancel` 先把 Run 置为 `FAILED` / `CANCELLED`，再释放本次 Run 的 pin（含已物化的 `OUTPUT` pin），最后丢弃无 owner 的计划输出（已挂接资源保留）。pin 必须早于 Resource 回收，顺序由 pin→resource 的删除限制决定。`cancelActive` 的 CAS 只要求 `status in ('READY','RUNNING')`，不需要 lease，因为取消来自客户端而不是 worker。
- `resolve` 处理人工核对：要求非空 `verification` 说明，通过 `RESUME` 将 Run 还回 `READY`（仅重新轮询任务结果，不 resubmit），或指定 `FAILED` / `CANCELLED` 释放 pin 并丢弃未归属的计划输出。`UNKNOWN` 期间 pin 与已物化槽位都保留，因此人工恢复后可以继续补齐缺失槽位。
- 终态 Run 允许被新的 requestId 取代：`replaceTerminalWithReady` 的 `where status in ('SUCCEEDED','FAILED','CANCELLED')` 保证并发下只有一个新 Run 成功（每个 Function 节点只有一行 Run）。重复 requestId 则直接返回既有 Run（含终态），不重复推进版本。

[`CanvasFunctionRuntimeService`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeService.java) 实现 Core 的 `CanvasFunctionService` 端口。`cancel` 或 `resolve(CANCELLED)` 在事务完成后调用 adapter 的 `cancel(frozen)`；该钩子是 best effort，`RuntimeException` 只记日志，不回滚已提交的终态与 pin 清理。`resolve(RESUME)` 和 `resolve(FAILED)` 不调用取消钩子。

pin 由 [`CanvasFunctionResourcePinRepository`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionResourcePinRepository.java) 持久化，`INPUT` 每个冻结引用一条、`OUTPUT` 每个已物化输出槽位一条（成功发布时 `resource_index` 等于槽位 index）；pin 只保护「无 owner 资源不被回收」，绝不参与 `storage_blob.ref_count`。Resource 行的删除与 pin 回收由本模块的 [`PostgresqlCanvasResourceLifecycle`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasResourceLifecycle.java) 在调用方事务内完成，Blob 释放经 [`CanvasBlobReleaser`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasBlobReleaser.java) 由 Platform 的 `StorageBlobManager` 适配；物化时的 retain 则由 Platform 物化器直接完成（见 [Platform](platform.md#canvas-媒体与-function-适配)）。

### 部署参数

[`CanvasFunctionRuntimeProperties`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeProperties.java) 的前缀是 `kk-studio.canvas.function.runtime`：

```text
workerConcurrency       = 2        maxDispatchTasks        = 2
leaseDurationMillis     = 30000    heartbeatIntervalMillis = 10000
pollIntervalMillis      = 1000     rejectionDelayMillis    = 1000
```

`validate()` 在 worker executor、heartbeat scheduler 的 bean 创建和 dispatcher 构造时校验：`workerConcurrency >= 1`、`1 <= maxDispatchTasks <= workerConcurrency`、四个时间参数为正毫秒、heartbeat 间隔必须小于 lease 时长。Executor、dispatcher、poll 与 heartbeat scheduler 的生命周期都由 [`CanvasFunctionRuntimeConfiguration`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeConfiguration.java) 装配；Catalog bean 只在缺失时创建，因此 adapter 集合来自宿主配置。adapter 的 `submit`/`execute` 在固定并发 worker 上同步执行，外部等待和轮询会占用该 worker 槽位，续租由独立 heartbeat scheduler 维持。

## 不变量与恢复

- `canvas_function_run` 是唯一队列事实：state 由 `node_id` 唯一（当前/最后 Run）、`(node_id, request_id)` 唯一、`attempt` 非负、lease token 与 until 成对、READY/RUNNING/终态的 available/lease 组合由 check constraint 固定。
- 所有图写入按 document → node → run 的固定顺序加锁；跨 Run、节点、画布的操作在一个事务里完成，异常整体回滚，revision、pin、Resource 不出现部分提交。
- 迟到回调只能收敛为 no-op 或内部取消；新 owner claim 之后，旧 token 的 `checkpoint`/`transitionTerminal` 影响 0 行。
- 通知是可丢的提示。丢通知、listener 重连、进程重启都由 poll 加 lease 过期恢复；`findSnapshot` 的一致性由「读取窗口前后重读并全值比较 document 与 run 列表」保证。
- 成功必须满足「输出计划的每个槽位都已物化、类型与冻结槽位一致、顺序等于计划顺序」，不满足时拒绝 success 而不是写入半成品输出。
- 通过入口校验的同一槽位重复物化返回既有 Resource 行，不新增行、不重复 pin；当前宿主文本物化器先校验非 null 与最多 1,048,576 个 Java 字符，再查幂等结果。部分物化后崩溃（`RUNNING + SUBMITTED`）的 Run 由 lease 过期恢复后只补齐，不重新提交外部任务。
## 从哪里改

- 改 SQL 或加列：先看 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 的 canvas 段与 [Schema](schema.md) 的重建规则，再改对应 mapper 与集成测试；`canvas_*` 的外键全部 RESTRICT，删除顺序不能靠 cascade。
- 改命令写路径与读模型：[`PostgresqlCanvasCommandService.java`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasCommandService.java)、[`PostgresqlCanvasQueryService.java`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasQueryService.java)、[`PostgresqlCanvasStore.java`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasStore.java) 与 [`PostgresqlCanvasResourceLifecycle.java`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasResourceLifecycle.java)。
- 改 Runtime：[`function` 目录](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/) 下的 dispatcher、worker、transactions、properties、codec 是一个整体；改动 lease 语义时同步检查 `claimNext` 的 `where` 与 `CanvasFunctionRunTransactions` 的 lock order。
- 新增模型能力：在 [Platform](platform.md) 或插件模块实现 `CanvasFunctionAdapter`，由 Catalog 启动冻结。

测试入口（标出「纯 JUnit」的不需要数据库，其余使用 Testcontainers PostgreSQL）：

- [`CanvasInfraArchitectureTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/CanvasInfraArchitectureTest.java)（纯 JUnit）验证模块架构约束、包依赖方向与自动配置清单。
- [`CanvasFunctionRuntimeFoundationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeFoundationTest.java) 在真实库上验证锁序、requestId 幂等、冻结 manifest、成功挂接与事务回滚。
- [`CanvasFunctionWorkStoreIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkStoreIntegrationTest.java) 验证多实例 `SKIP LOCKED` claim、租约恢复、fencing 与 `canvas_function_work` NOTIFY；schema check 的拒绝路径也在这里。
- [`CanvasFunctionWorkerHeartbeatTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionWorkerHeartbeatTest.java) 与 [`CanvasFunctionDispatcherTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionDispatcherTest.java)（纯 JUnit）覆盖续租丢失后阻止旧 worker 写终态、容量耗尽时的归还与 wake 恢复。
- [`CanvasFunctionRuntimeServiceTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeServiceTest.java)（纯 JUnit）覆盖取消/人工决议的 adapter 钩子路由。
- [`PostgresqlCanvasStoreIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasStoreIntegrationTest.java)（含 `FOR UPDATE` 真实阻塞与 CAS 失败）、[`PostgresqlCanvasCommandServiceIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasCommandServiceIntegrationTest.java)、[`PostgresqlCanvasQueryServiceIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasQueryServiceIntegrationTest.java) 与 [`PostgresqlCanvasResourceRepositoryIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasResourceRepositoryIntegrationTest.java) 覆盖四个持久化与应用服务端口契约。
- codec 与属性（纯 JUnit）：[`CanvasFunctionArgsCodecTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionArgsCodecTest.java)、[`CanvasFunctionRunStateCodecTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunStateCodecTest.java)、[`CanvasFunctionRuntimePropertiesTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimePropertiesTest.java)。
- 测试基座 [`PostgresCanvasInfraTestSupport.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresCanvasInfraTestSupport.java) 使用 `postgres:17-alpine` 进程级容器与 Schema 的 Flyway baseline，每个测试前重建 public schema；运行需要 Docker，环境不可用即失败。

---

上级：[系统设计](../system-design.md)。相关文档：[Canvas Core](canvas-core.md)、[Schema](schema.md)、[Web](web.md)、[Platform](platform.md)。
