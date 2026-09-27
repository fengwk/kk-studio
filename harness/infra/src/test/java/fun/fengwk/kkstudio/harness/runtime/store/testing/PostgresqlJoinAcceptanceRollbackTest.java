package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 真实 PostgreSQL / Testcontainers 事务环境下 Join 命令接受（acceptCommandsAndJoin）与异常回滚 / GC 残留测试。
 *
 * <p>核心验证点：
 *
 * <ul>
 *   <li><b>Preflight 失败全表原子回滚</b>：当 {@code acceptCommandsAndJoin} 中的 Preflight 抛出异常时，PostgreSQL 的
 *       Session / Thread / Entry / Command / Join / Work 全表数据原子回滚，零孤儿行残留；
 *   <li><b>配额超限与标识重用回滚</b>：子线程 Join 配额超限或 InvocationId 重用被拒绝时，同样在 PostgreSQL 中不产生任何孤儿记录；
 *   <li><b>并发接受 Tree Lock 串行化</b>：同一父级下的并发子线程接受请求通过 PG Advisory Lock 严格串行化，避免配额竞态；
 *   <li><b>完整生命周期与 GC 验证</b>：子线程从 accept -> model terminal -> IDLE 匹配 -> 父级交付 CUSTOM_MESSAGE ->
 *       父级消费， PostgreSQL 全表状态一致性与最终一致性检验。
 * </ul>
 */
class PostgresqlJoinAcceptanceRollbackTest {

  private static final String HASH = CREATION_REQUEST_HASH;

  private HarnessStore store;
  private HarnessRuntime runtime;
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T0, ZoneOffset.UTC),
            (threadId, path, prep) -> null,
            () -> CompactionConfig.DEFAULT);
    jdbcTemplate = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
  }

  private static AcceptCommandsCommand newSessionChild(
      UUID sessionId, UUID childThreadId, UUID parentThreadId, String prompt) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            sessionId, childThreadId, branchSettings(), parentThreadId, false),
        List.of(
            new NewThreadCommand(
                new UserMessageCommandPayload(AgentMessage.user(prompt)), UUID.randomUUID())));
  }

  private static ThreadJoinRequest joinRequest(
      UUID invocationId,
      UUID parentThreadId,
      UUID expectedHead,
      int maxTurns,
      int concurrentQuota) {
    return new ThreadJoinRequest(
        invocationId,
        parentThreadId,
        expectedHead,
        HASH,
        "test-agent",
        maxTurns,
        3,
        concurrentQuota,
        3);
  }

  @Test
  void acceptCommandsAndJoinPreflightFailureRollsBackAllTablesWithoutOrphans() {
    // 测试意图：验证当在已有的根/父线程下通过 acceptCommandsAndJoin 接受新子会话与 Join 凭据时，
    // 若 Preflight 抛出异常，PostgreSQL 的底层事务完整回滚，各表中均不残留任何孤儿行（Session、Thread、Entry、Command、Join、Work）。
    Baseline baseline = seedThreadBaseline(store);

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID joinInvocationId = UUID.randomUUID();

    AcceptCommandsCommand command =
        newSessionChild(childSessionId, childThreadId, baseline.threadId(), "run subagent task");
    ThreadJoinRequest join =
        joinRequest(joinInvocationId, baseline.threadId(), baseline.rootEntryId(), 5, 3);

    // 传入拒绝的 preflight
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                command,
                join,
                (tx, current, commands) -> {
                  throw new IllegalStateException(
                      "preflight rejected: insufficient balance or policy denial");
                }));

    // 直接从 PostgreSQL 真实数据表中查询各表的残留记录数（必须全部为 0）
    int sessionCount =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_session where id = ?", Integer.class, childSessionId);
    assertEquals(0, sessionCount, "harness_session must not contain rolled-back session");

    int threadCount =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread where id = ?", Integer.class, childThreadId);
    assertEquals(0, threadCount, "harness_thread must not contain rolled-back thread");

    int entryCount =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_entry where session_id = ?",
            Integer.class,
            childSessionId);
    assertEquals(0, entryCount, "harness_entry must not contain rolled-back entries");

    int commandCount =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_command where thread_id = ?",
            Integer.class,
            childThreadId);
    assertEquals(0, commandCount, "harness_thread_command must not contain rolled-back commands");

    int joinCount =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_join where invocation_id = ?",
            Integer.class,
            joinInvocationId);
    assertEquals(0, joinCount, "harness_thread_join must not contain rolled-back join");

    int workCount =
        jdbcTemplate.queryForObject(
            "select count(*) from harness_work where target_id = ?", Integer.class, childThreadId);
    assertEquals(0, workCount, "harness_work must not contain rolled-back work");

    // 父级状态完全不受影响
    ThreadState parentAfter =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, parentAfter.version());
    assertEquals(1L, parentAfter.nextCommandSequence());
    assertEquals(baseline.rootEntryId(), parentAfter.headEntryId());
  }

  @Test
  void acceptCommandsAndJoinQuotaExceededRollsBackEntirely() {
    // 测试意图：验证同一父级下子线程 Join 配额达到上限时，后续超限的 acceptCommandsAndJoin 抛出异常并完整回滚，
    // 第一个成功的子线程和 Join 记录不受破坏，第二个被拒绝的子线程在 PG 中零残留。
    Baseline baseline = seedThreadBaseline(store);

    UUID child1SessionId = UUID.randomUUID();
    UUID child1ThreadId = UUID.randomUUID();
    UUID join1InvocationId = UUID.randomUUID();

    // 允许 1 个未匹配 join 的配额
    ThreadJoinRequest join1 =
        joinRequest(join1InvocationId, baseline.threadId(), baseline.rootEntryId(), 5, 1);
    AcceptedCommands first =
        runtime.acceptCommandsAndJoin(
            newSessionChild(child1SessionId, child1ThreadId, baseline.threadId(), "task 1"),
            join1,
            AcceptancePreflight.IDENTITY);
    assertNotNull(first);

    // 第二个子线程请求超过配额
    UUID child2SessionId = UUID.randomUUID();
    UUID child2ThreadId = UUID.randomUUID();
    UUID join2InvocationId = UUID.randomUUID();
    ThreadJoinRequest join2 =
        joinRequest(join2InvocationId, baseline.threadId(), baseline.rootEntryId(), 5, 1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                newSessionChild(child2SessionId, child2ThreadId, baseline.threadId(), "task 2"),
                join2,
                AcceptancePreflight.IDENTITY));

    // 验证 PG 数据库：child 2 相关记录全部为 0
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_session where id = ?", Integer.class, child2SessionId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread where id = ?", Integer.class, child2ThreadId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_join where invocation_id = ?",
            Integer.class,
            join2InvocationId));

    // child 1 相关记录依然完好
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_join where invocation_id = ?",
            Integer.class,
            join1InvocationId));
    assertTrue(runtime.findJoin(join1InvocationId).isPresent());
  }

  @Test
  void acceptCommandsAndJoinReusedInvocationIdRejectionLeavesNoOrphans() {
    // 测试意图：验证重复使用同一个 invocationId 尝试创建不同的子线程时，抛出冲突异常并回滚，不污染数据库。
    Baseline baseline = seedThreadBaseline(store);

    UUID child1SessionId = UUID.randomUUID();
    UUID child1ThreadId = UUID.randomUUID();
    UUID sharedInvocationId = UUID.randomUUID();

    ThreadJoinRequest join1 =
        joinRequest(sharedInvocationId, baseline.threadId(), baseline.rootEntryId(), 5, 3);
    runtime.acceptCommandsAndJoin(
        newSessionChild(child1SessionId, child1ThreadId, baseline.threadId(), "initial subagent"),
        join1,
        AcceptancePreflight.IDENTITY);

    // 重用 sharedInvocationId 创建第二个不同的 session/thread
    UUID child2SessionId = UUID.randomUUID();
    UUID child2ThreadId = UUID.randomUUID();
    ThreadJoinRequest join2 =
        joinRequest(sharedInvocationId, baseline.threadId(), baseline.rootEntryId(), 5, 3);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                newSessionChild(
                    child2SessionId, child2ThreadId, baseline.threadId(), "conflicting subagent"),
                join2,
                AcceptancePreflight.IDENTITY));

    // 第二个会话和线程在 PG 中为 0 行
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_session where id = ?", Integer.class, child2SessionId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread where id = ?", Integer.class, child2ThreadId));

    // 原始 join 记录正常存在且属于 child 1
    ThreadJoin initialJoin = runtime.findJoin(sharedInvocationId).orElseThrow();
    assertEquals(child1ThreadId, initialJoin.childThreadId());
  }

  @Test
  void concurrentAcceptCommandsAndJoinOnSameTreeSerializesViaTreeLockOnPostgres() throws Exception {
    // 测试意图：验证同一父执行树下的并发 acceptCommandsAndJoin 能够通过 Tree Advisory Lock 在 PostgreSQL 级别实现安全串行化互斥，
    // 事务 A 持锁期间事务 B 阻塞等待，事务 A 提交后事务 B 顺利完成，最终两子树均正确落库且祖先链与子线程关系完整。
    Baseline baseline = seedThreadBaseline(store);

    UUID child1SessionId = UUID.randomUUID();
    UUID child1ThreadId = UUID.randomUUID();
    UUID join1Id = UUID.randomUUID();

    UUID child2SessionId = UUID.randomUUID();
    UUID child2ThreadId = UUID.randomUUID();
    UUID join2Id = UUID.randomUUID();

    CountDownLatch tx1HoldingLock = new CountDownLatch(1);
    CountDownLatch tx1CanCommit = new CountDownLatch(1);
    CountDownLatch tx2Started = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      // 线程 1：执行 acceptCommandsAndJoin，在 preflight 内通知持锁并等待
      Future<AcceptedCommands> future1 =
          executor.submit(
              () ->
                  runtime.acceptCommandsAndJoin(
                      newSessionChild(
                          child1SessionId, child1ThreadId, baseline.threadId(), "task 1"),
                      joinRequest(join1Id, baseline.threadId(), baseline.rootEntryId(), 5, 5),
                      (tx, current, commands) -> {
                        tx1HoldingLock.countDown();
                        try {
                          if (!tx1CanCommit.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timeout on tx1CanCommit");
                          }
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(e);
                        }
                        return commands;
                      }));

      // 等待线程 1 已经拿到树锁并进入 preflight
      assertTrue(tx1HoldingLock.await(10, TimeUnit.SECONDS));

      // 线程 2：尝试在同一个父树下执行 acceptCommandsAndJoin，内部 lockTreeAndAncestors 必须被 PG 阻塞
      Future<AcceptedCommands> future2 =
          executor.submit(
              () -> {
                tx2Started.countDown();
                return runtime.acceptCommandsAndJoin(
                    newSessionChild(child2SessionId, child2ThreadId, baseline.threadId(), "task 2"),
                    joinRequest(join2Id, baseline.threadId(), baseline.rootEntryId(), 5, 5),
                    AcceptancePreflight.IDENTITY);
              });

      assertTrue(tx2Started.await(10, TimeUnit.SECONDS));

      // 验证线程 2 此时处于阻塞状态
      assertThrows(
          TimeoutException.class,
          () -> future2.get(150, TimeUnit.MILLISECONDS),
          "Tx 2 must be blocked by Tx 1's tree advisory lock on PostgreSQL");

      // 允许线程 1 提交
      tx1CanCommit.countDown();
      AcceptedCommands accepted1 = future1.get(10, TimeUnit.SECONDS);
      AcceptedCommands accepted2 = future2.get(10, TimeUnit.SECONDS);

      assertNotNull(accepted1);
      assertNotNull(accepted2);

      // 验证在 PG 中两个子线程与 Join 均成功写入，且祖先链完全正确
      assertEquals(
          List.of(child1ThreadId, baseline.threadId()), runtime.findAncestorChain(child1ThreadId));
      assertEquals(
          List.of(child2ThreadId, baseline.threadId()), runtime.findAncestorChain(child2ThreadId));

      List<ThreadState> children = store.transaction(tx -> tx.listChildren(baseline.threadId()));
      assertEquals(2, children.size());
      assertTrue(children.stream().anyMatch(c -> c.id().equals(child1ThreadId)));
      assertTrue(children.stream().anyMatch(c -> c.id().equals(child2ThreadId)));

      assertTrue(runtime.findJoin(join1Id).isPresent());
      assertTrue(runtime.findJoin(join2Id).isPresent());
    }
  }

  @Test
  void garbageCollectionAndFullLifecyclePostgresVerification() throws Exception {
    // 测试意图：真实 PostgreSQL 端到端完整生命周期与 GC 清理验证：
    // 1. acceptCommandsAndJoin 接受子任务及 join 凭据；
    // 2. 模拟并发中途失败的垃圾请求并验证无脏数据残留；
    // 3. 由具体生产 ThreadProcessor 消费并推进子线程直至 IDLE；
    // 4. 直接查询 PG 数据表校验 harness_thread_join 各回执字段的真实落库（matched, idle_version, result_head,
    // delivery_seq）；
    // 5. 验证父线程接收到精确渲染的 CUSTOM_MESSAGE XML 命令，并由 ThreadProcessor 驱动父级顺利开启下一轮 Turn。
    Instant now = T1;
    Baseline baseline = seedThreadBaseline(store);

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID joinInvocationId = UUID.randomUUID();

    // 1. 正常接受子任务
    AcceptedCommands accepted =
        runtime.acceptCommandsAndJoin(
            newSessionChild(
                childSessionId, childThreadId, baseline.threadId(), "calculate factorial(5)"),
            joinRequest(joinInvocationId, baseline.threadId(), baseline.rootEntryId(), 5, 3),
            AcceptancePreflight.IDENTITY);
    assertNotNull(accepted);

    // 2. 插入并回滚一个失败的垃圾请求，验证 PG 表级隔离
    UUID abortedSessionId = UUID.randomUUID();
    UUID abortedThreadId = UUID.randomUUID();
    UUID abortedJoinId = UUID.randomUUID();
    assertThrows(
        RuntimeException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                newSessionChild(abortedSessionId, abortedThreadId, baseline.threadId(), "garbage"),
                joinRequest(abortedJoinId, baseline.threadId(), baseline.rootEntryId(), 5, 3),
                (tx, current, commands) -> {
                  throw new RuntimeException("abort");
                }));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread where id = ?", Integer.class, abortedThreadId));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from harness_thread_join where invocation_id = ?",
            Integer.class,
            abortedJoinId));

    // 3. 驱动子线程执行：第一步消费 INPUT 启动 Turn 并生成 ModelInvocation
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      TurnResolver resolver =
          (threadId, path, preparation) ->
              new TurnResolver.Resolved(StoreTestSupport.modelRequest(), 100_000, 16_384);
      ThreadProcessor processor =
          new ThreadProcessor(
              store,
              resolver,
              new ThreadProcessorConfig(
                  new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
                  Duration.ofSeconds(5),
                  () -> new CompactionConfig(20_000, null)),
              Clock.fixed(now, ZoneOffset.UTC),
              scheduler,
              Runnable::run);

      ClaimedWork childWork1 =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, now, "child-claim-1", now.plusSeconds(30)))
              .orElseThrow();
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(childWork1));

      // 模拟外部模型执行完成并回写 SUCCEEDED 结果
      UUID modelId =
          store.transaction(
              tx -> {
                tx.lockThread(childThreadId).orElseThrow();
                var path =
                    tx.loadEntryPath(tx.findThread(childThreadId).orElseThrow().headEntryId());
                UUID turnStartId = path.openTurnStart().orElseThrow().id();
                ModelInvocation found =
                    tx.findModelInvocationByTurn(childThreadId, turnStartId).orElseThrow();
                ModelInvocation model = tx.lockModelInvocation(found.id()).orElseThrow();
                tx.updateModelInvocation(model.beginDispatch(T2));
                model = tx.lockModelInvocation(found.id()).orElseThrow();
                tx.updateModelInvocation(model.markRunning(T2));
                model = tx.lockModelInvocation(found.id()).orElseThrow();
                tx.updateModelInvocation(model.succeed(StoreTestSupport.assistantResponse(), T2));
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, childThreadId), T2);
                return found.id();
              });

      // 驱动子线程执行：第二步 Terminal Model Apply，闭合 Turn，子线程进入 IDLE 并触发 Join 匹配和父级交付
      ClaimedWork childWork2 =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, T2, "child-claim-2", T2.plusSeconds(30)))
              .orElseThrow();
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(childWork2));

      // 4. 直接从 PG 数据库底层查询 harness_thread_join 表，确认各字段真实正确落库
      ThreadJoin joinInPg = store.transaction(tx -> tx.findJoin(joinInvocationId).orElseThrow());
      assertTrue(joinInPg.matched(), "Join must be matched in PostgreSQL");
      ThreadState finalChildState =
          store.transaction(tx -> tx.findThread(childThreadId).orElseThrow());
      assertEquals(ThreadLifecycleStatus.IDLE, finalChildState.status());
      assertEquals(finalChildState.version(), joinInPg.matchedIdleVersion());
      assertNotNull(joinInPg.resultHeadEntryId());
      assertEquals(1L, joinInPg.deliveryCommandSequence());

      // 5. 校验父线程收到唯一的 CUSTOM_MESSAGE 交付命令
      List<ThreadCommand> parentQueued =
          store.transaction(
              tx -> {
                tx.lockThread(baseline.threadId());
                return tx.loadQueuedCommands(baseline.threadId());
              });
      assertEquals(1, parentQueued.size());
      ThreadCommand deliveredCmd = parentQueued.get(0);
      assertEquals(ThreadCommandType.CUSTOM_MESSAGE, deliveredCmd.type());
      assertEquals(joinInvocationId, deliveredCmd.idempotencyKey());
      assertEquals(1L, deliveredCmd.sequence());

      // 6. 驱动父线程消费该交付回执，启动父级 Turn
      ClaimedWork parentWork =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, T2, "parent-claim-1", T2.plusSeconds(30)))
              .orElseThrow();
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(parentWork));

      // 父级 Turn 已开启，交付命令已成功从队列消费并绑定到新 Entry
      ThreadState parentAfterTurn =
          store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
      var parentPath = store.transaction(tx -> tx.loadEntryPath(parentAfterTurn.headEntryId()));
      assertTrue(parentPath.entries().size() > 1, "Parent path must advance past root");
    } finally {
      scheduler.shutdownNow();
    }
  }
}
