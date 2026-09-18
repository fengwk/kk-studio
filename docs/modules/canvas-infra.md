# Canvas Infra 模块

[canvas-core](canvas-core.md) 定义了 graph 聚合、typed command 与 Function 执行边界，但 domain 不碰 PostgreSQL。`canvas-infra` 是这些端口的实现侧，也是整套系统里**唯一**的 Function durable queue：它把命令、Snapshot、Resource 与 Run 落到 `canvas_*` 行上，并用 claim / lease / fencing / 短事务 CAS 让「用户点一次生成」在进程崩溃、节点重启、通知丢失之后仍然收敛。Web 负责把 Infra 装进组合根，Platform 通过 Core 端口驱动业务事务。

生产依赖固定为 Core、`convention4j-spring-boot-starter`、`spring-boot-starter-aspectj`、`mybatis-spring-boot-starter` 与 `jackson-databind`（见 [`canvas/infra/pom.xml`](../../canvas/infra/pom.xml)）；PostgreSQL、Flyway、Testcontainers 只在 test scope。[`CanvasInfraArchitectureTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/CanvasInfraArchitectureTest.java) 同时扫描 import 前缀与 POM，禁止反向依赖 Platform/Harness/Web。自动配置入口是 [`CanvasInfraAutoConfiguration.java`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/CanvasInfraAutoConfiguration.java)，[`AutoConfiguration.imports`](../../canvas/infra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports) 只指向它，并显式启用 `CanvasFunctionRuntimeProperties`。

## Graph 投影到行

写路径从 [`PostgresqlCanvasStore`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasStore.java) 开始，它注入 document、node、group、link、command dedup 五个 mapper（Resource 行由 [`PostgresqlCanvasResourceRepository`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasResourceRepository.java) 单独负责）。Store 本身只提供 Core 端口定义的原语，不编排业务顺序：

| mapper | 关键 SQL |
| --- | --- |
| [`CanvasDocumentMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasDocumentMapper.java) | `FOR UPDATE` / `FOR KEY SHARE` 读、`compareAndSetVersion`、行锁内 `incrementVersion` |
| [`CanvasNodeMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasNodeMapper.java) | node CRUD、`FOR UPDATE`、transform/name/function 条件更新、组内挂接与移动 |
| [`CanvasGroupMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasGroupMapper.java)、[`CanvasLinkMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasLinkMapper.java) | group/link upsert 与按节点、按画布删除 |
| [`CanvasResourceMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasResourceMapper.java) | owner attach/detach、文本内容更新、三类删除 |
| [`CanvasCommandDedupMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasCommandDedupMapper.java) | `(canvas_id, idempotency_key)` 的 request hash |
| [`CanvasSessionMapper`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasSessionMapper.java) | `session_owner` 中 Canvas 归属边的读写 |

所有 boolean 返回值都来自 PostgreSQL 实际影响行数，而不是进程内推断——`advanceDocumentVersion` 就是 `compareAndSetVersion(...) == 1`。SQL 直接写在 mapper 注解里；`canvas_*` 的 ownership 外键全部是 `ON DELETE RESTRICT`，因此删除顺序必须由应用显式编排：Platform 的 `deleteNode` 依次删 link → pin → run → owned resource → node，`deleteCanvas` 依次释放 pin、删 resource、run、link、node、group、dedup、Session 归属，最后删 document（见 [Platform](platform.md)）。

Snapshot 读路径是 [`PostgresqlCanvasQueryService.findSnapshot`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasQueryService.java)。它把 document、run 列表、资源按 owner 分组、node、group、link 装配成 [`CanvasSnapshot`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasSnapshot.java)，然后在返回前重读 document 与全部 run 并逐项 `equals` 对比：任何 Function checkpoint 在同一窗口提交都会导致 generation 变化，于是整轮重读。这样客户端拿到的 graph 与 Run 投影一定属于同一代次，代价是并发 Function 写入时读操作可能多跑几轮。

## Function run 的完整生命周期

### 启动：冻结输入，产出 READY

[`CanvasFunctionRunTransactions.start`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunTransactions.java) 是事务边界，锁序固定为 document → node → run：

```text
lock document (FOR UPDATE) -> lock node -> lock current run (FOR UPDATE)
  -> requestId 与现有 Run 相同：直接返回既有 Run（不推进版本）
  -> 现有 Run 是 READY/RUNNING 且 requestId 不同：conflict
  -> Catalog require(modelKey) + requireAvailable；codec 严格解码 config
  -> 冻结 manifest：每个引用节点必须存在、必须有指向本节点的 Link、index 必须在资源范围内且必须是 blob
  -> 校验 frozen blob facts 与 model reference policy
  -> 预分配 targetResourceId，构造 stage=QUEUED 的 frozen plan
  -> adapter.preflight(frozen)
  -> 释放上一个 Run 的 pin，写入 INPUT/OUTPUT pin
  -> 插入 READY Run（已有终态 Run 时用 replaceTerminalWithReady 的 CAS 替换）
  -> advanceDocumentVersion(+1)
```

冻结的含义是：引用在启动瞬间取 source node 的 resource identity、blob id 与权威媒体事实。之后无论用户改了配置、删了 link 还是替换了源资源，这个 Run 的输入都不变。`preflight` 在事务内、在写入 READY 之前执行，因此拒绝的配置不会留下队列事实。

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

`skip locked` 让多节点并发 claim 各自拿到不同行，没有内存队列、没有重试锁竞争。dispatcher 为每次 claim 生成 UUID 形式的 lease token；`renew`、`reschedule`、`countOwned` 与 Run 侧的 `checkpoint`、`transitionTerminal` 都在 SQL 的 `where` 里同时要求 `request_id`、`status='RUNNING'`、`lease_token` 匹配且 `lease_until > now`。token 过期或已被新 owner 接管时，`renew`/`countOwned` 返回 false，`checkpoint`/`transitionTerminal` 影响 0 行，后者在事务里被转成 [`CanvasFunctionInternalCancellation`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionInternalCancellation.java)——旧 worker 的回调因此只能变成 no-op 或静默退出，不可能写坏新 Run。

### 执行、heartbeat 与终态

[`CanvasFunctionDispatcher`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionDispatcher.java) 由周期 poll 驱动（`scheduleWithFixedDelay`），并在启动时立即 wake 一次；它不是 `LISTEN` 的持有者。`start()` 之前 `wake()` 不生效，`stop()` 取消 poll 并让后续 `wake()` 直接返回。`drainOnce` 只在本地容量（`maxDispatchTasks`，默认 2）未满时 claim，把 Run 交给固定并发 worker executor；`SynchronousQueue + AbortPolicy` 保证没有第二层内存等待队列，executor 拒绝时立即用 `reschedule(now + rejectionDelayMillis)` 把 Run 还回 READY 并结束本轮 drain，避免 claim-reject 热循环。

postgres 的 NOTIFY 由组合根接进同一个共享 `LISTEN` 连接：[`ApplicationEventConfiguration`](../../web/src/main/java/fun/fengwk/kkstudio/web/events/ApplicationEventConfiguration.java) 把 `CanvasFunctionDispatcher.CHANNEL`（即 `canvas_function_work`）的 handler 指向 `dispatcher.wake()`，断连后由 `dispatcher::wake` 兜底。也就是说 NOTIFY 只提供低延迟，**正确性来自 poll 与 lease**：通知丢失、listener 重连、应用重启都能在下一次 poll 或租约过期后重新 claim。[`canvas_function_work_notify()`](../../schema/src/main/resources/db/migration/V1__schema.sql) 只在提交后行立刻可认领（`READY`、无 lease、`available_at <= now()`）时发空 payload；`canvas_version` 通道只提示 document version，客户端据此回读 Snapshot。

[`CanvasFunctionWorker.run`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionWorker.java) 的动作是：解析 state 得到 frozen plan、`requireAvailable`、构造 [`CanvasFunctionExecutionContextImpl`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionExecutionContextImpl.java)、调用 adapter 的 `execute`、确认返回值恰好是冻结目标 id、最后 `completeSuccess`。执行期间 heartbeat 按固定间隔续租，**只**更新 `lease_until`，不触碰 document version、Run state 或其他业务事实；续租失败、抛异常、Run 被 cancel、node/document 被删、租约过期都会把 `ownershipLost` 置位，worker 随即停止新的 checkpoint 与 terminal 写入。adapter 抛出其它异常时，worker 用固定文案走 `failIfRunning`。

checkpoint 与终态都走 [`CanvasFunctionRunTransactions`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunTransactions.java) 的短事务方法：`start`、`cancel`、`checkpoint`、`completeSuccess`、`failIfRunning`。它们一律先锁 document 与 node，再锁 run，写完后 `advanceDocumentVersion(expectedVersion, expectedVersion + 1)`；CAS 在行锁内失败被视为持久化不变量破坏，直接抛错而不是静默重试。外部计算（adapter 的 HTTP、轮询、媒体处理）永远在事务之外。

成功与失败的资源处置不同，这是最容易改错的地方：

- `completeSuccess` 先确认目标资源仍是同画布、无 owner、有 blob 的资源，且媒体类型与冻结 model 的 `outputKind` 匹配，然后由 Resource lifecycle 用 target 替换节点当前 owned 资源（旧资源若仍被其他 Run pin 则只解 owner，否则删行并释放 blob 引用），最后把 Run 置为 `SUCCEEDED` 并释放本次 Run 的全部 pin。
- `failIfRunning` 与 `cancel` 只丢弃「无 owner 的目标资源」，释放本次 Run 的 pin，Run 进入 `FAILED` / `CANCELLED`。`cancelActive` 的 CAS 只要求 `status in ('READY','RUNNING')`，不需要 lease，因为取消来自客户端而不是 worker。
- 终态 Run 允许被新的 requestId 取代：`replaceTerminalWithReady` 的 `where status in ('SUCCEEDED','FAILED','CANCELLED')` 保证并发下只有一个新 generation 成功。重复 requestId 则直接返回既有 Run（含终态），不重复推进版本。

pin 由 [`CanvasFunctionResourcePinRepository`](../../canvas/core/src/main/java/fun/fengwk/kkstudio/canvas/CanvasFunctionResourcePinRepository.java) 持久化，`INPUT` 每个冻结引用一条、`OUTPUT` 一条；pin 只保护「无 owner 资源不被回收」，绝不参与 `storage_blob.ref_count`。blob 引用的增减属于 Platform 的 Resource lifecycle（见 [Platform](platform.md)）。

### 部署参数

[`CanvasFunctionRuntimeProperties`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeProperties.java) 的前缀是 `kk-studio.canvas.function.runtime`：

```text
workerConcurrency       = 2        maxDispatchTasks        = 2
leaseDurationMillis     = 30000    heartbeatIntervalMillis = 10000
pollIntervalMillis      = 1000     rejectionDelayMillis    = 1000
```

`validate()` 在创建 dispatcher 之前 fail fast：`workerConcurrency >= 1`、`1 <= maxDispatchTasks <= workerConcurrency`、四个时间参数为正毫秒、heartbeat 间隔必须小于 lease 时长。Executor、dispatcher、poll 与 heartbeat scheduler 的生命周期都由 [`CanvasFunctionRuntimeConfiguration`](../../canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeConfiguration.java) 装配；Catalog bean 只在缺失时创建，因此 adapter 集合仍然来自 Platform。

## 不变量与恢复

- `canvas_function_run` 是唯一队列事实：state 由 `node_id` 唯一（当前/最后 Run）、`(node_id, request_id)` 唯一、`attempt` 非负、lease token 与 until 成对、READY/RUNNING/终态的 available/lease 组合由 check constraint 固定。
- 所有图写入按 document → node → run 的固定顺序加锁；跨 Run、节点、画布的操作在一个事务里完成，异常整体回滚，version、pin、Resource 不出现部分提交。
- 迟到回调只能收敛为 no-op 或内部取消；新 owner claim 之后，旧 token 的 `checkpoint`/`transitionTerminal` 影响 0 行。
- 通知是可丢的提示。丢通知、listener 重连、进程重启都由 poll 加 lease 过期恢复；`findSnapshot` 的一致性由重读对比 generation 保证。
- 成功必须满足「目标资源已物化且媒体类型与冻结 model 输出类型一致」，不满足时拒绝 success 而不是写入半成品输出。

## 从哪里改

- 改 SQL 或加列：先看 [`V1__schema.sql`](../../schema/src/main/resources/db/migration/V1__schema.sql) 的 canvas 段与 [Schema](schema.md) 的重建规则，再改对应 mapper 与集成测试；`canvas_*` 的外键全部 RESTRICT，删除顺序不能靠 cascade。
- 改命令写路径：Core 端口的实现在 Infra，业务编排与生命周期在 [Platform](platform.md) 的 `PlatformCanvasCommandService`。
- 改 Runtime：`canvas/infra/src/main/java/fun/fengwk/kkstudio/canvas/infra/function/` 下的 dispatcher、worker、transactions、properties、codec 是一个整体；改动 lease 语义时同步检查 `claimNext` 的 `where` 与 `CanvasFunctionRunTransactions` 的 lock order。
- 新增模型能力：不要改 Infra；在 [Platform](platform.md) 实现 `CanvasFunctionAdapter`，由 Catalog 在启动时冻结。

测试入口（除架构守卫外都需要 Testcontainers PostgreSQL）：

- [`CanvasFunctionRuntimeFoundationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimeFoundationTest.java) 在真实库上验证锁序、requestId 幂等、冻结 manifest、成功挂接与事务回滚。
- [`CanvasFunctionWorkStoreIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/CanvasFunctionWorkStoreIntegrationTest.java) 验证多实例 `SKIP LOCKED` claim、租约恢复、fencing 与 `canvas_function_work` NOTIFY；schema check 的拒绝路径也在这里。
- [`CanvasFunctionWorkerHeartbeatTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionWorkerHeartbeatTest.java) 与 [`CanvasFunctionDispatcherTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionDispatcherTest.java) 覆盖续租丢失后阻止旧 worker 写终态、容量耗尽时的归还与 wake 恢复。
- [`PostgresqlCanvasStoreIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasStoreIntegrationTest.java)（含 `FOR UPDATE` 真实阻塞与 CAS 失败）、[`PostgresqlCanvasQueryServiceIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasQueryServiceIntegrationTest.java)、[`PostgresqlCanvasResourceRepositoryIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasResourceRepositoryIntegrationTest.java)、[`PostgresqlCanvasSessionRepositoryIntegrationTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresqlCanvasSessionRepositoryIntegrationTest.java) 覆盖四个 repository 端口契约。
- codec 与属性：[`CanvasFunctionConfigCodecTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionConfigCodecTest.java)、[`CanvasFunctionRunStateCodecTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRunStateCodecTest.java)、[`CanvasFunctionRuntimePropertiesTest.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/function/CanvasFunctionRuntimePropertiesTest.java)。
- 测试基座 [`PostgresCanvasInfraTestSupport.java`](../../canvas/infra/src/test/java/fun/fengwk/kkstudio/canvas/infra/postgresql/PostgresCanvasInfraTestSupport.java) 使用 `postgres:17-alpine` 进程级容器与 schema 模块的 Flyway baseline，并在每个测试前 drop/recreate public schema；Docker 不可用时测试直接失败，不用 mock 掩盖适配器问题。

---

上级：[系统设计](../system-design.md)。相关文档：[Canvas Core](canvas-core.md)、[Schema](schema.md)、[Web](web.md)、[Platform](platform.md)。
