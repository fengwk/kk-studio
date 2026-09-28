package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
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
import java.util.function.Function;

/**
 * 真实 PostgreSQL / Testcontainers 事务环境下父线程 Stop 与子线程 Terminal/Join 交付的并发与事务回滚测试。
 *
 * <p>核心验证点：
 *
 * <ul>
 *   <li><b>Tree Lock 互斥与阻塞</b>：PG 事务级 advisory 锁 {@code lockTree(rootThreadId)} 保证同一执行树的父级 Stop 与子级
 *       Terminal Apply 严格互斥串行化，子级 processor 必须阻塞等待父级 Stop 事务提交；
 *   <li><b>Stop 优先场景下的交付冻结</b>：父级 Stop 提交后，子级后续执行观察到 STOPPED 边界，Join 凭据交付被冻结（不向父级注入活跃命令，不重复交付）；
 *   <li><b>Terminal 优先场景下的交付取消</b>：子级 Terminal Apply 先提交完成 Join 交付（注入 CUSTOM_MESSAGE）后，父级 Stop
 *       能精确取消该入队命令并保持版本与事实一致；
 *   <li><b>无 Arbitrary Sleep 与确定性 Barrier</b>：全流程通过 {@link CountDownLatch} 编排并发交错时序；
 *   <li><b>全事务原子回滚</b>：子级 Terminal 崩溃或父级 Stop CAS 冲突时，整个 PostgreSQL 事务完整回滚，释放锁且无孤儿或污染数据残留。
 * </ul>
 */
class PostgresqlParentStopChildJoinConcurrencyTest {

  private static final String REQUEST_HASH = CREATION_REQUEST_HASH;

  private HarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T0, ZoneOffset.UTC),
            (threadId, path, prep) -> null,
            () -> CompactionConfig.DEFAULT);
  }

  private static EntryPayload userMessagePayload(String text) {
    return new MessagePayload(AgentMessage.user(text), null, null);
  }

  private record ExecutionTreeFixture(
      UUID sessionId,
      UUID rootEntryId,
      UUID parentThreadId,
      UUID childThreadId,
      UUID turnStartId,
      UUID userMsgId,
      UUID modelId,
      UUID joinInvocationId) {}

  private ExecutionTreeFixture seedParentAndChild(Instant now, boolean modelSucceeded) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID parentThreadId = tx.nextId();

          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertThread(
              new ThreadState(
                  parentThreadId,
                  sessionId,
                  null,
                  rootEntryId,
                  REQUEST_HASH,
                  "parent-thread",
                  false,
                  ThreadLifecycleStatus.WAITING_CHILDREN,
                  1L,
                  0L,
                  now,
                  now));

          UUID childThreadId = tx.nextId();
          UUID turnStartId = tx.nextId();
          UUID userMsgId = tx.nextId();

          tx.insertEntry(
              new Entry(
                  turnStartId,
                  sessionId,
                  rootEntryId,
                  resolvedTurnStartPayload(childThreadId),
                  now));
          tx.insertEntry(
              new Entry(
                  userMsgId, sessionId, turnStartId, userMessagePayload("calculate 1+1"), now));
          tx.insertThread(
              new ThreadState(
                  childThreadId,
                  sessionId,
                  parentThreadId,
                  userMsgId,
                  REQUEST_HASH,
                  "child-thread",
                  false,
                  ThreadLifecycleStatus.ACTIVE,
                  2L,
                  0L,
                  now,
                  now));

          UUID childCommandKey = tx.nextId();
          UserMessageCommandPayload cmdPayload =
              new UserMessageCommandPayload(AgentMessage.user("calculate 1+1"));
          ThreadCommand childCmd =
              new ThreadCommand(
                  childThreadId,
                  1L,
                  cmdPayload,
                  childCommandKey,
                  REQUEST_HASH,
                  null,
                  null,
                  null,
                  now);
          tx.insertCommands(List.of(childCmd));
          tx.updateCommands(List.of(StoreTestSupport.withConsumedTurnStart(childCmd, turnStartId)));

          UUID modelId = tx.nextId();
          ModelRequestSpec request = StoreTestSupport.succeededRequest();
          ProviderResponse response = StoreTestSupport.assistantResponse();
          ModelInvocation model =
              new ModelInvocation(
                  modelId,
                  childThreadId,
                  turnStartId,
                  userMsgId,
                  request,
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  now,
                  now);
          tx.insertModelInvocation(model);
          var dispatching = model.beginDispatch(now);
          tx.updateModelInvocation(dispatching);
          var running = dispatching.markRunning(now);
          tx.updateModelInvocation(running);
          if (modelSucceeded) {
            var succeeded = running.succeed(response, now);
            tx.updateModelInvocation(succeeded);
          }

          UUID joinInvocationId = tx.nextId();
          tx.insertJoin(
              new ThreadJoin(
                  joinInvocationId,
                  REQUEST_HASH,
                  parentThreadId,
                  childThreadId,
                  1L,
                  0L,
                  "subagent-calculator",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  now,
                  now));

          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, childThreadId), now);

          return new ExecutionTreeFixture(
              sessionId,
              rootEntryId,
              parentThreadId,
              childThreadId,
              turnStartId,
              userMsgId,
              modelId,
              joinInvocationId);
        });
  }

  private ThreadProcessor createThreadProcessor(Instant now, ScheduledExecutorService scheduler) {
    TurnResolver resolver =
        (threadId, path, preparation) ->
            new TurnResolver.Rejected(
                new AssistantError("RESOLVER_REJECT", "no continuation in test"));
    return new ThreadProcessor(
        store,
        resolver,
        new ThreadProcessorConfig(
            new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
            Duration.ofSeconds(5),
            () -> new CompactionConfig(20_000, null)),
        Clock.fixed(now, ZoneOffset.UTC),
        scheduler,
        Runnable::run);
  }

  private static HarnessStore latchingTreeLockStore(
      HarnessStore delegate,
      UUID targetRoot,
      CountDownLatch treeLockedLatch,
      CountDownLatch canCommitLatch) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("transaction")) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, Object> callback =
                    (Function<HarnessStore.Transaction, Object>) args[0];
                return delegate.transaction(
                    tx -> {
                      HarnessStore.Transaction wrapped =
                          (HarnessStore.Transaction)
                              Proxy.newProxyInstance(
                                  HarnessStore.Transaction.class.getClassLoader(),
                                  new Class<?>[] {HarnessStore.Transaction.class},
                                  (txProxy, txMethod, txArgs) -> {
                                    Object res = txMethod.invoke(tx, txArgs);
                                    if (txMethod.getName().equals("lockTree")
                                        && txArgs != null
                                        && txArgs.length > 0
                                        && targetRoot.equals(txArgs[0])) {
                                      treeLockedLatch.countDown();
                                      try {
                                        if (!canCommitLatch.await(10, TimeUnit.SECONDS)) {
                                          throw new IllegalStateException(
                                              "timeout on canCommitLatch");
                                        }
                                      } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(e);
                                      }
                                    }
                                    return res;
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }

  @Test
  void advisoryTreeLockBlocksConcurrentChildProcessorUntilParentStopCommitsOnPostgres()
      throws Exception {
    // 测试意图：验证在真实 PostgreSQL 事务中，父线程 Stop 率先获取 Tree Advisory Lock 时，
    // 并发执行的子线程 ThreadProcessor 在尝试获取同树祖先锁时会被 PostgreSQL 事务锁强制阻塞；
    // 直到父级 Stop 事务提交后，子级 Processor 解除阻塞继续执行，并确认 Join 凭据未向已停止的父级重复或错误注入活跃命令。
    Instant now = T1;
    // 子线程处于 RUNNING 状态，父级 Stop 可顺利停止并取消子级
    ExecutionTreeFixture fixture = seedParentAndChild(now, false);

    ClaimedWork childClaim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, now, "child-lease", now.plusSeconds(30)))
            .orElseThrow();

    CountDownLatch parentTreeLocked = new CountDownLatch(1);
    CountDownLatch parentCanCommit = new CountDownLatch(1);
    CountDownLatch childProcessorStarted = new CountDownLatch(1);
    CountDownLatch childProcessorFinished = new CountDownLatch(1);

    HarnessStore latchingStore =
        latchingTreeLockStore(store, fixture.parentThreadId(), parentTreeLocked, parentCanCommit);
    HarnessRuntime latchingRuntime =
        new HarnessRuntime(
            latchingStore,
            Clock.fixed(now, ZoneOffset.UTC),
            (t, p, c) -> null,
            () -> CompactionConfig.DEFAULT);

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      ThreadProcessor processor = createThreadProcessor(now, scheduler);

      UUID stopRequestId = UUID.randomUUID();
      Future<StopResult> stopFuture =
          executor.submit(
              () ->
                  latchingRuntime.stop(
                      new StopCommand(fixture.parentThreadId(), stopRequestId, 0)));

      // 等待父级 Stop 拿到 PG 树级 advisory lock
      assertTrue(parentTreeLocked.await(10, TimeUnit.SECONDS));

      // 线程 2：子级 ThreadProcessor 执行 claim，其内部 lockThreadWithAncestors 会尝试获取同一根节点的 tree lock
      Future<ThreadProcessResult> childFuture =
          executor.submit(
              () -> {
                childProcessorStarted.countDown();
                try {
                  return processor.process(childClaim);
                } finally {
                  childProcessorFinished.countDown();
                }
              });

      assertTrue(childProcessorStarted.await(10, TimeUnit.SECONDS));

      // 关键断言：子级 processor 正在被 PostgreSQL advisory lock 阻塞，无法在短时间内完成
      assertThrows(
          TimeoutException.class,
          () -> childFuture.get(150, TimeUnit.MILLISECONDS),
          "Child processor must be blocked on PostgreSQL tree advisory lock held by parent stop transaction");
      assertEquals(1L, childProcessorFinished.getCount(), "Child processor must still be waiting");

      // 允许父级 Stop 事务提交
      parentCanCommit.countDown();
      StopResult stopResult = stopFuture.get(10, TimeUnit.SECONDS);
      assertNotNull(stopResult);
      assertFalse(stopResult.replayed());

      // 父级 Stop 提交后，子级 Processor 应该立即解除阻塞并执行完成
      ThreadProcessResult childResult = childFuture.get(10, TimeUnit.SECONDS);
      assertNotNull(childResult);

      // 验证 PostgreSQL 中的最终状态
      ThreadState parentState =
          store.transaction(tx -> tx.findThread(fixture.parentThreadId()).orElseThrow());
      EntryPath parentPath = store.transaction(tx -> tx.loadEntryPath(parentState.headEntryId()));
      TurnEndPayload parentEnd =
          assertInstanceOf(TurnEndPayload.class, parentPath.head().payload());
      assertEquals(TurnEndOutcome.STOPPED, parentEnd.outcome());
      assertEquals(TurnEndReason.USER_STOP, parentEnd.reason());

      // 子线程同样被递归 Stop
      ThreadState childState =
          store.transaction(tx -> tx.findThread(fixture.childThreadId()).orElseThrow());
      EntryPath childPath = store.transaction(tx -> tx.loadEntryPath(childState.headEntryId()));
      TurnEndPayload childEnd = assertInstanceOf(TurnEndPayload.class, childPath.head().payload());
      assertEquals(TurnEndOutcome.STOPPED, childEnd.outcome());

      // 父线程命令队列中不得存在未协同的活跃自定义消息
      List<ThreadCommand> parentCommands =
          store.transaction(
              tx -> {
                tx.lockThread(fixture.parentThreadId());
                return tx.loadQueuedCommands(fixture.parentThreadId());
              });
      assertTrue(
          parentCommands.isEmpty()
              || parentCommands.stream().allMatch(c -> c.state() == ThreadCommandState.CANCELLED),
          "Parent commands must not have active custom messages after stop");

      // Join 记录未交付给父级活跃队列（deliveryCommandSequence 为空或被 hold）
      ThreadJoin join =
          store.transaction(tx -> tx.findJoin(fixture.joinInvocationId()).orElseThrow());
      assertNull(
          join.deliveryCommandSequence(),
          "Join must not deliver active sequence to stopped parent");
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void childTerminalApplyDeliversReceiptOnceThenParentStopCancelsQueuedDeliveryOnPostgres() {
    // 测试意图：验证子线程率先执行 Terminal Apply 并成功闭合 Turn 时：
    // 1. 在同一个 PostgreSQL 事务内子线程置为 IDLE，Join 凭据被原子匹配并向父级注入唯一的 CUSTOM_MESSAGE 回执命令；
    // 2. 紧接着父线程执行 Stop 时，父级在原子 Stop 事务内将该已入队但未消费的回执命令置为 CANCELLED，
    //    且 Join 凭据的 deliveryCommandSequence 保持原值，不发生重复交付或序列错乱。
    Instant now = T1;
    ExecutionTreeFixture fixture = seedParentAndChild(now, true);

    ClaimedWork childClaim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, now, "child-lease", now.plusSeconds(30)))
            .orElseThrow();

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadProcessor processor = createThreadProcessor(now, scheduler);

      // 第一步：子线程消费 claim，执行 Terminal Model Apply
      ThreadProcessResult result = processor.process(childClaim);
      assertEquals(ThreadProcessResult.COMPLETED, result);

      // 验证 PostgreSQL 数据库中的中间状态：子线程已空闲，Join 已匹配，父线程已有 1 条入队 CUSTOM_MESSAGE
      ThreadState childState =
          store.transaction(tx -> tx.findThread(fixture.childThreadId()).orElseThrow());
      assertEquals(ThreadLifecycleStatus.IDLE, childState.status());
      assertEquals(1L, childState.version());

      ThreadJoin matchedJoin =
          store.transaction(tx -> tx.findJoin(fixture.joinInvocationId()).orElseThrow());
      assertTrue(matchedJoin.matched());
      assertEquals(childState.version(), matchedJoin.matchedIdleVersion());
      assertEquals(childState.headEntryId(), matchedJoin.resultHeadEntryId());
      assertEquals(1L, matchedJoin.deliveryCommandSequence());

      ThreadState parentState =
          store.transaction(tx -> tx.findThread(fixture.parentThreadId()).orElseThrow());
      assertEquals(ThreadLifecycleStatus.ACTIVE, parentState.status());
      assertEquals(2L, parentState.nextCommandSequence());

      List<ThreadCommand> parentQueued =
          store.transaction(
              tx -> {
                tx.lockThread(fixture.parentThreadId());
                return tx.loadQueuedCommands(fixture.parentThreadId());
              });
      assertEquals(1, parentQueued.size());
      ThreadCommand queuedCmd = parentQueued.get(0);
      assertEquals(ThreadCommandType.CUSTOM_MESSAGE, queuedCmd.type());
      assertEquals(fixture.joinInvocationId(), queuedCmd.idempotencyKey());
      assertEquals(1L, queuedCmd.sequence());
      assertEquals(ThreadCommandState.QUEUED, queuedCmd.state());

      CustomMessageCommandPayload payload =
          assertInstanceOf(CustomMessageCommandPayload.class, queuedCmd.payload());
      String rendered = payload.message().contents().get(0).toString();
      assertTrue(rendered.contains("subagent-calculator"));

      // 第二步：对父线程发起 Stop
      UUID stopRequestId = UUID.randomUUID();
      StopResult stopResult =
          runtime.stop(
              new StopCommand(fixture.parentThreadId(), stopRequestId, parentState.version()));
      assertFalse(stopResult.replayed());

      // 验证 PostgreSQL 数据库中的最终状态：父线程已 STOPPED，已投递的 CUSTOM_MESSAGE 被标记为 CANCELLED
      ThreadState stoppedParent =
          store.transaction(tx -> tx.findThread(fixture.parentThreadId()).orElseThrow());
      EntryPath parentPath = store.transaction(tx -> tx.loadEntryPath(stoppedParent.headEntryId()));
      TurnEndPayload parentEnd =
          assertInstanceOf(TurnEndPayload.class, parentPath.head().payload());
      assertEquals(TurnEndOutcome.STOPPED, parentEnd.outcome());
      assertEquals(stopRequestId, parentEnd.closeRequestId());

      ThreadCommand cancelledCmd =
          store.transaction(
              tx ->
                  tx.findCommandByIdempotencyKey(
                          fixture.parentThreadId(), fixture.joinInvocationId())
                      .orElseThrow());
      assertEquals(ThreadCommandState.CANCELLED, cancelledCmd.state());
      assertEquals(stopRequestId, cancelledCmd.stopRequestId());

      // Join 凭据交付序列号依然是 1（精确一次交付，没有二次修改）
      ThreadJoin finalJoin =
          store.transaction(tx -> tx.findJoin(fixture.joinInvocationId()).orElseThrow());
      assertEquals(1L, finalJoin.deliveryCommandSequence());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void concurrentParentStopAndChildTerminalSerializeDeterministicallyWithoutDuplicateDelivery()
      throws Exception {
    // 测试意图：高并发竞争测试：父线程 Stop 与子线程 Terminal Apply 几乎同时并发启动，
    // 验证通过 PG Advisory Tree Lock 能够无死锁、无异常地完成串行化，且 Join 凭据交付命令至多 1 次，绝对不会产生重复交付。
    Instant now = T1;
    ExecutionTreeFixture fixture = seedParentAndChild(now, true);

    ClaimedWork childClaim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, now, "child-lease", now.plusSeconds(30)))
            .orElseThrow();

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      ThreadProcessor processor = createThreadProcessor(now, scheduler);

      CountDownLatch startBarrier = new CountDownLatch(1);
      UUID stopRequestId = UUID.randomUUID();

      Future<StopResult> stopFuture =
          executor.submit(
              () -> {
                startBarrier.await();
                return runtime.stop(new StopCommand(fixture.parentThreadId(), stopRequestId, 0));
              });

      Future<ThreadProcessResult> childFuture =
          executor.submit(
              () -> {
                startBarrier.await();
                return processor.process(childClaim);
              });

      // 同时释放两线程
      startBarrier.countDown();

      try {
        StopResult stopResult = stopFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(stopResult);
      } catch (ExecutionException e) {
        // 若 Stop 在子线程处于 terminal apply pending 窗口时执行，按规范拒绝为 TERMINAL_APPLY_PENDING 并回滚
        assertInstanceOf(HarnessRuntimeConflictException.class, e.getCause());
      }

      ThreadProcessResult childResult = childFuture.get(10, TimeUnit.SECONDS);
      assertNotNull(childResult);

      // 验证无论哪个事务先提交，交付命令数量至多为 1，绝对不发生重复交付
      List<ThreadCommand> allParentCommands =
          store.transaction(
              tx -> {
                tx.lockThread(fixture.parentThreadId());
                return tx.loadQueuedCommands(fixture.parentThreadId());
              });
      assertTrue(allParentCommands.size() <= 1, "Parent must not receive duplicate join commands");

      // 校验历史中同一 invocationId 的 command 记录至多 1 条
      var customCmd =
          store.transaction(
              tx ->
                  tx.findCommandByIdempotencyKey(
                      fixture.parentThreadId(), fixture.joinInvocationId()));
      if (customCmd.isPresent()) {
        assertTrue(
            customCmd.get().state() == ThreadCommandState.CANCELLED
                || customCmd.get().state() == ThreadCommandState.QUEUED);
      }
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void abortedChildTerminalTransactionRollsBackEntirelyLeavingPostgresConsistent() {
    // 测试意图：验证子线程 Terminal Apply 事务在执行到一半因异常中断（回滚）时，
    // PostgreSQL 能够回滚全部未提交的数据变更（不残留部分 Entry、不修改 Join 状态、不向父级注入虚假命令），
    // 释放锁后后续重试能够完整且幂等地重新完成单次交付。
    Instant now = T1;
    ExecutionTreeFixture fixture = seedParentAndChild(now, true);

    // 模拟子线程 Terminal 事务中途失败回滚
    assertThrows(
        RuntimeException.class,
        () ->
            store.transaction(
                tx -> {
                  // 1. 拿树锁与行锁
                  tx.lockTree(fixture.parentThreadId());
                  tx.lockSessionForKeyShare(fixture.sessionId()).orElseThrow();
                  tx.lockThread(fixture.parentThreadId()).orElseThrow();
                  ThreadState child = tx.lockThread(fixture.childThreadId()).orElseThrow();

                  // 2. 插入部分 Entry
                  UUID partialEntryId = tx.nextId();
                  tx.insertEntry(
                      new Entry(
                          partialEntryId,
                          fixture.sessionId(),
                          child.headEntryId(),
                          userMessagePayload("partial text"),
                          now));
                  tx.updateThread(child.advanceHead(partialEntryId, now));

                  // 3. 故意抛出异常中断事务
                  throw new RuntimeException("simulated database crash during terminal apply");
                }));

    // 验证 PostgreSQL 中所有数据完整回滚，毫无残留
    ThreadState childAfterRollback =
        store.transaction(tx -> tx.findThread(fixture.childThreadId()).orElseThrow());
    assertEquals(fixture.userMsgId(), childAfterRollback.headEntryId());
    assertEquals(0L, childAfterRollback.version());

    ThreadJoin joinAfterRollback =
        store.transaction(tx -> tx.findJoin(fixture.joinInvocationId()).orElseThrow());
    assertFalse(joinAfterRollback.matched());
    assertNull(joinAfterRollback.deliveryCommandSequence());

    List<ThreadCommand> parentCommands =
        store.transaction(
            tx -> {
              tx.lockThread(fixture.parentThreadId());
              return tx.loadQueuedCommands(fixture.parentThreadId());
            });
    assertTrue(parentCommands.isEmpty());

    // 随后通过正常的 ThreadProcessor 重新消费，验证可干净地成功完成
    ClaimedWork childClaim =
        store
            .transaction(
                tx ->
                    tx.claimNextWork(
                        WorkTargetType.THREAD, now, "child-lease", now.plusSeconds(30)))
            .orElseThrow();

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadProcessor processor = createThreadProcessor(now, scheduler);
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(childClaim));

      ThreadJoin finalJoin =
          store.transaction(tx -> tx.findJoin(fixture.joinInvocationId()).orElseThrow());
      assertTrue(finalJoin.matched());
      assertEquals(1L, finalJoin.deliveryCommandSequence());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void staleVersionParentStopRollsBackEntireTransactionLeavingTreeUntouchedOnPostgres() {
    // 测试意图：验证父级 Stop 在遭遇 expectedVersion 过期（CAS 冲突）时，
    // 抛出 STALE_VERSION 异常并将整棵执行树在 PostgreSQL 中的所有修改完整回滚。
    Instant now = T1;
    ExecutionTreeFixture fixture = seedParentAndChild(now, true);

    // 外部并发修改推进父版本
    store.transaction(
        tx -> {
          ThreadState parent = tx.lockThread(fixture.parentThreadId()).orElseThrow();
          tx.updateThread(parent.touchVersion(T3));
          return null;
        });

    // 传入旧版本 expectedVersion = 0 发起 Stop
    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.stop(new StopCommand(fixture.parentThreadId(), UUID.randomUUID(), 0)));

    // 验证 PostgreSQL 中父子线程状态未被 Stop 破坏
    ThreadState parent =
        store.transaction(tx -> tx.findThread(fixture.parentThreadId()).orElseThrow());
    assertEquals(1L, parent.version());
    assertEquals(fixture.rootEntryId(), parent.headEntryId());

    ThreadState child =
        store.transaction(tx -> tx.findThread(fixture.childThreadId()).orElseThrow());
    assertEquals(0L, child.version());
    assertEquals(fixture.userMsgId(), child.headEntryId());
  }
}
