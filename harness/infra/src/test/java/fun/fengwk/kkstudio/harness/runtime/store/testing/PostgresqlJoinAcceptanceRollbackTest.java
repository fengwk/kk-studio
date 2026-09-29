package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
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
import java.util.concurrent.ExecutionException;
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
  void concurrentIdenticalChildNewSessionReplayBlocksOnTreeLockWithoutDuplicateRows()
      throws Exception {
    // 测试意图：同一父执行树下两个完全相同的 NEW_SESSION(子) 请求并发到达时，第二个必须在 PG 树 advisory lock 上阻塞；
    // 第一个提交后第二个在树锁内复读到已存在的子线程，走 exact replay：Session/Entry/Thread/Command/Join/Work 均只有一行。
    // 子 Session/Thread 的 UUID 故意小于父，验证复读后的补锁仍按 UUID 升序完成，不存在“父已锁、回补低 UUID 子”的逆序。
    UUID childSessionId = TestIds.id(5000);
    UUID childThreadId = TestIds.id(5001);
    UUID childCommandKey = TestIds.id(5002);
    UUID joinInvocationId = TestIds.id(5003);
    UUID parentSessionId = TestIds.id(5004);
    UUID parentRootEntryId = TestIds.id(5005);
    UUID parentThreadId = TestIds.id(5006);
    assertTrue(UuidOrder.COMPARATOR.compare(childSessionId, parentSessionId) < 0);
    assertTrue(UuidOrder.COMPARATOR.compare(childThreadId, parentThreadId) < 0);

    store.transaction(
        tx -> {
          tx.insertSession(StoreTestSupport.session(parentSessionId));
          tx.insertEntry(StoreTestSupport.rootEntry(parentRootEntryId, parentSessionId));
          tx.insertThread(
              new ThreadState(
                  parentThreadId,
                  parentSessionId,
                  null,
                  parentRootEntryId,
                  HASH,
                  "parent",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1L,
                  0L,
                  T0,
                  T0));
          return null;
        });

    AcceptCommandsCommand request =
        newSession(childSessionId, childThreadId, parentThreadId, childCommandKey, "child task");
    ThreadJoinRequest join = joinRequest(joinInvocationId, parentThreadId, parentRootEntryId, 5, 3);

    CountDownLatch tx1HoldingTreeLock = new CountDownLatch(1);
    CountDownLatch tx1CanCommit = new CountDownLatch(1);
    CountDownLatch tx2Started = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<AcceptedCommands> first =
          executor.submit(
              () ->
                  runtime.acceptCommandsAndJoin(
                      request,
                      join,
                      (tx, current, commands) -> {
                        tx1HoldingTreeLock.countDown();
                        awaitOrFail(tx1CanCommit);
                        return commands;
                      }));
      assertTrue(tx1HoldingTreeLock.await(10, TimeUnit.SECONDS));

      Future<AcceptedCommands> second =
          executor.submit(
              () -> {
                tx2Started.countDown();
                return runtime.acceptCommandsAndJoin(request, join, AcceptancePreflight.IDENTITY);
              });
      assertTrue(tx2Started.await(10, TimeUnit.SECONDS));
      assertThrows(
          TimeoutException.class,
          () -> second.get(250, TimeUnit.MILLISECONDS),
          "identical replay must block on the parent tree advisory lock");

      tx1CanCommit.countDown();
      AcceptedCommands acceptedFirst = first.get(10, TimeUnit.SECONDS);
      AcceptedCommands acceptedSecond = second.get(10, TimeUnit.SECONDS);

      assertFalse(acceptedFirst.replayed());
      assertTrue(acceptedSecond.replayed());
      assertEquals(childThreadId, acceptedSecond.thread().id());
    }

    assertSingleRow("harness_session", "id", childSessionId);
    assertSingleRow("harness_entry", "session_id", childSessionId);
    assertSingleRow("harness_thread", "id", childThreadId);
    assertSingleRow("harness_thread_command", "thread_id", childThreadId);
    assertSingleRow("harness_thread_join", "invocation_id", joinInvocationId);
    assertSingleRow("harness_work", "target_id", childThreadId);
    assertEquals(List.of(childThreadId, parentThreadId), runtime.findAncestorChain(childThreadId));
  }

  @Test
  void concurrentIdenticalRootNewSessionReplayBlocksOnTreeLockWithoutDuplicateRows()
      throws Exception {
    // 测试意图：两个完全相同的 NEW_SESSION(根，无父) 请求并发到达时，第二个阻塞在新建 Thread 自身的树锁上；
    // 第一个提交后第二个 exact replay，不重复插入 Session/Entry/Thread/Command/Join/Work。
    UUID sessionId = TestIds.id(6000);
    UUID threadId = TestIds.id(6001);
    UUID commandKey = TestIds.id(6002);
    UUID joinInvocationId = TestIds.id(6003);

    AcceptCommandsCommand request = newSession(sessionId, threadId, null, commandKey, "root task");
    ThreadJoinRequest ticket =
        new ThreadJoinRequest(joinInvocationId, null, null, HASH, "test-agent", 5, 3, 3, 3);

    CountDownLatch tx1HoldingTreeLock = new CountDownLatch(1);
    CountDownLatch tx1CanCommit = new CountDownLatch(1);
    CountDownLatch tx2Started = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<AcceptedCommands> first =
          executor.submit(
              () ->
                  runtime.acceptCommandsAndJoin(
                      request,
                      ticket,
                      (tx, current, commands) -> {
                        tx1HoldingTreeLock.countDown();
                        awaitOrFail(tx1CanCommit);
                        return commands;
                      }));
      assertTrue(tx1HoldingTreeLock.await(10, TimeUnit.SECONDS));

      Future<AcceptedCommands> second =
          executor.submit(
              () -> {
                tx2Started.countDown();
                return runtime.acceptCommandsAndJoin(request, ticket, AcceptancePreflight.IDENTITY);
              });
      assertTrue(tx2Started.await(10, TimeUnit.SECONDS));
      assertThrows(
          TimeoutException.class,
          () -> second.get(250, TimeUnit.MILLISECONDS),
          "identical root replay must block on the root thread's tree advisory lock");

      tx1CanCommit.countDown();
      assertFalse(first.get(10, TimeUnit.SECONDS).replayed());
      assertTrue(second.get(10, TimeUnit.SECONDS).replayed());
    }

    assertSingleRow("harness_session", "id", sessionId);
    assertSingleRow("harness_entry", "session_id", sessionId);
    assertSingleRow("harness_thread", "id", threadId);
    assertSingleRow("harness_thread_command", "thread_id", threadId);
    assertSingleRow("harness_thread_join", "invocation_id", joinInvocationId);
    assertSingleRow("harness_work", "target_id", threadId);
  }

  private static AcceptCommandsCommand newSession(
      UUID sessionId, UUID threadId, UUID parentThreadId, UUID commandKey, String prompt) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            sessionId, threadId, branchSettings(), parentThreadId, false),
        List.of(
            new NewThreadCommand(
                new UserMessageCommandPayload(AgentMessage.user(prompt)), commandKey)));
  }

  private static void awaitOrFail(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for the coordinating latch");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void assertSingleRow(String table, String column, UUID id) {
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, id),
        table + " must contain exactly one row for " + column + " = " + id);
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
                          WorkTargetType.THREAD, now, "child-claim-1", Duration.ofSeconds(30)))
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
                          WorkTargetType.THREAD, T2, "child-claim-2", Duration.ofSeconds(30)))
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
                          WorkTargetType.THREAD, T2, "parent-claim-1", Duration.ofSeconds(30)))
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

  @Test
  void globalJoinAdmissionSerializesConcurrentNewSessionsAcrossRootTreesAndEnforcesCap()
      throws Exception {
    // 测试意图：task 的全局并发上限在真实 PG 上跨 root 生效——两个不同执行树的子 Session 创建并发到达时，
    // 第二个必须在全局 Join 准入锁上阻塞（两棵树互不相干，Tree advisory lock 不会相遇）；第一个提交后
    // 全局活跃子线程已达上限，第二个在准入锁内被确定性拒绝且不留下任何 Session/Entry/Thread/Command/Join/Work 孤儿；
    // 额度释放后同一请求可成功创建，exact replay 不重复占额度。
    UUID rootASessionId = TestIds.id(7000);
    UUID rootARootEntryId = TestIds.id(7001);
    UUID rootAThreadId = TestIds.id(7002);
    UUID childASessionId = TestIds.id(7003);
    UUID childAThreadId = TestIds.id(7004);
    UUID childACommandKey = TestIds.id(7005);
    UUID joinAInvocationId = TestIds.id(7006);
    UUID rootBSessionId = TestIds.id(7010);
    UUID rootBRootEntryId = TestIds.id(7011);
    UUID rootBThreadId = TestIds.id(7012);
    UUID childBSessionId = TestIds.id(7013);
    UUID childBThreadId = TestIds.id(7014);
    UUID childBCommandKey = TestIds.id(7015);
    UUID joinBInvocationId = TestIds.id(7016);

    seedRootThread(rootASessionId, rootARootEntryId, rootAThreadId);
    seedRootThread(rootBSessionId, rootBRootEntryId, rootBThreadId);

    AcceptCommandsCommand requestA =
        newSession(childASessionId, childAThreadId, rootAThreadId, childACommandKey, "child A");
    ThreadJoinRequest joinA =
        globalJoinRequest(joinAInvocationId, rootAThreadId, rootARootEntryId, 1);
    AcceptCommandsCommand requestB =
        newSession(childBSessionId, childBThreadId, rootBThreadId, childBCommandKey, "child B");
    ThreadJoinRequest joinB =
        globalJoinRequest(joinBInvocationId, rootBThreadId, rootBRootEntryId, 1);

    CountDownLatch tx1HoldingAdmission = new CountDownLatch(1);
    CountDownLatch tx1CanCommit = new CountDownLatch(1);
    CountDownLatch tx2Started = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<AcceptedCommands> first =
          executor.submit(
              () ->
                  runtime.acceptCommandsAndJoin(
                      requestA,
                      joinA,
                      (tx, current, commands) -> {
                        tx1HoldingAdmission.countDown();
                        awaitOrFail(tx1CanCommit);
                        return commands;
                      }));
      assertTrue(tx1HoldingAdmission.await(10, TimeUnit.SECONDS));

      Future<AcceptedCommands> second =
          executor.submit(
              () -> {
                tx2Started.countDown();
                return runtime.acceptCommandsAndJoin(requestB, joinB, AcceptancePreflight.IDENTITY);
              });
      assertTrue(tx2Started.await(10, TimeUnit.SECONDS));
      assertThrows(
          TimeoutException.class,
          () -> second.get(250, TimeUnit.MILLISECONDS),
          "concurrent child admission on another root must block on the global join admission lock");

      tx1CanCommit.countDown();
      assertFalse(first.get(10, TimeUnit.SECONDS).replayed());
      ExecutionException rejected =
          assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
      assertInstanceOf(IllegalArgumentException.class, rejected.getCause());
    }

    // 额度被 A 树的 childA1 占满，被拒绝的 B 请求零残留
    assertEquals(1, activeSubagentThreads());
    assertNoRow("harness_session", "id", childBSessionId);
    assertNoRow("harness_entry", "session_id", childBSessionId);
    assertNoRow("harness_thread", "id", childBThreadId);
    assertNoRow("harness_thread_command", "thread_id", childBThreadId);
    assertNoRow("harness_thread_join", "invocation_id", joinBInvocationId);
    assertNoRow("harness_work", "target_id", childBThreadId);

    // 释放额度：childA1 回到 IDLE
    store.transaction(
        tx -> {
          ThreadState child = tx.lockThread(childAThreadId).orElseThrow();
          tx.updateThread(child.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T1));
          return null;
        });
    assertEquals(0, activeSubagentThreads());

    // 同一请求在额度释放后成功创建
    AcceptedCommands retried =
        runtime.acceptCommandsAndJoin(requestB, joinB, AcceptancePreflight.IDENTITY);
    assertFalse(retried.replayed());
    assertSingleRow("harness_session", "id", childBSessionId);
    assertSingleRow("harness_thread", "id", childBThreadId);
    assertSingleRow("harness_thread_command", "thread_id", childBThreadId);
    assertSingleRow("harness_thread_join", "invocation_id", joinBInvocationId);
    assertEquals(1, activeSubagentThreads());

    // exact replay 命中既有 Join，不重复占额度
    AcceptedCommands replayed =
        runtime.acceptCommandsAndJoin(requestB, joinB, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(1, activeSubagentThreads());
  }

  /** 种入一个无父 root Thread（只含 ROOT Entry），用于构造互不相干的执行树。 */
  private void seedRootThread(UUID sessionId, UUID rootEntryId, UUID threadId) {
    store.transaction(
        tx -> {
          tx.insertSession(StoreTestSupport.session(sessionId));
          tx.insertEntry(StoreTestSupport.rootEntry(rootEntryId, sessionId));
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  null,
                  rootEntryId,
                  HASH,
                  "root",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1L,
                  0L,
                  T0,
                  T0));
          return null;
        });
  }

  private static ThreadJoinRequest globalJoinRequest(
      UUID invocationId, UUID parentThreadId, UUID expectedHead, int globalCap) {
    return new ThreadJoinRequest(
        invocationId, parentThreadId, expectedHead, HASH, "test-agent", 5, 3, 5, globalCap);
  }

  private int activeSubagentThreads() {
    return store.transaction(tx -> tx.countActiveSubagentThreads());
  }

  private void assertNoRow(String table, String column, UUID id) {
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, id),
        table + " must not contain any row for " + column + " = " + id);
  }
}
