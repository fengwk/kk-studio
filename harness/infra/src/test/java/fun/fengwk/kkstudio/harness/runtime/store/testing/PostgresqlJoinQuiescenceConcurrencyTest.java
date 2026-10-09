package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantResponse;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.resolvedTurnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.withConsumedTurnStart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadLifecycleCoordinator;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 真实 PostgreSQL / Testcontainers 环境下 A-B-C 委派收敛的确定性与原子性测试。
 *
 * <p>核心验证点（针对「子执行仍有未完成后代/待处理输入时被过早向上结算」的缺陷）：
 *
 * <ul>
 *   <li><b>结算+父入队原子</b>：子执行 C 的 Join 匹配与父 B 的 NOTIFICATION 入队/唤醒在同一事务提交；在 match 之后、父命令插入之前暂停时，
 *       同树读写受同一 PostgreSQL advisory 树锁阻塞（以 {@code pg_locks} 未授予锁为真实证据），外部观察者不可能看到「C 已结算但 B 无通知」；
 *   <li><b>父不被过早结算</b>：无论 B 的本地 final 和 C 的交付谁先到，B 都必须消费通知并到达新的成功或不可继续失败终态，才向 A 交付回执；
 *   <li><b>回滚 exactly-once</b>：同一窗口故障回滚时 Entry/Join/父序列/通知/wake 一致回滚，从 durable claim 重试后恰一份回执；
 *   <li><b>确定性窗口</b>：通过 {@link CountDownLatch} 与 {@code pg_locks} 事实定位事务窗口，不靠延时制造竞态。
 * </ul>
 */
class PostgresqlJoinQuiescenceConcurrencyTest {

  private static final String REQUEST_HASH = CREATION_REQUEST_HASH;

  private HarnessStore store;
  private JdbcTemplate jdbc;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    runtime =
        new HarnessRuntime(
            store,
            Clock.fixed(T1, ZoneOffset.UTC),
            (threadId, path, preparation) -> null,
            () -> CompactionConfig.DEFAULT);
  }

  private record Tree(
      UUID sessionId,
      UUID rootEntryId,
      UUID threadA,
      UUID threadB,
      UUID threadC,
      UUID joinAB,
      UUID joinBC) {}

  /**
   * A(root) -&gt; B(已闭合终态、IDLE、上游 Join 未匹配) -&gt; C(打开 turn 且 terminal Model 已提交)。 C 的 THREAD claim
   * 已通过 requestWork 就绪，供真实 ThreadProcessor 消费。
   */
  private Tree seedThreeLevelTree(Instant now) {
    return seedThreeLevelTree(now, false);
  }

  private Tree seedThreeLevelTree(Instant now, boolean bTerminalPending) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));

          UUID threadA = tx.nextId();
          UUID threadB = tx.nextId();
          UUID threadC = tx.nextId();

          // B 的上游 Join A->B 尚未冻结；按调度场景保留待应用模型或闭合终态。
          UUID bStart = tx.nextId();
          UUID bUser = tx.nextId();
          UUID bAssistant = tx.nextId();
          UUID bEnd = tx.nextId();
          tx.insertEntry(
              new Entry(bStart, sessionId, rootEntryId, resolvedTurnStartPayload(threadB), now));
          tx.insertEntry(new Entry(bUser, sessionId, bStart, userMessagePayload(), now));
          if (!bTerminalPending) {
            tx.insertEntry(new Entry(bAssistant, sessionId, bUser, assistantPayload(), now));
            tx.insertEntry(
                new Entry(
                    bEnd,
                    sessionId,
                    bAssistant,
                    new TurnEndPayload(bStart, TurnEndOutcome.COMPLETED, false, null, null),
                    now));
          }

          // C：打开的 turn，terminal Model 已提交（ModelTerminalPending）。
          UUID cStart = tx.nextId();
          UUID cUser = tx.nextId();
          tx.insertEntry(
              new Entry(cStart, sessionId, rootEntryId, resolvedTurnStartPayload(threadC), now));
          tx.insertEntry(new Entry(cUser, sessionId, cStart, userMessagePayload(), now));

          // 锁序 SESSION -> THREAD：所有 Thread 必须先于任何 COMMAND 建立。
          tx.insertThread(
              new ThreadState(
                  threadA,
                  sessionId,
                  null,
                  rootEntryId,
                  REQUEST_HASH,
                  "root-A",
                  ThreadYoloPolicy.root(false),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  now,
                  now));
          tx.insertThread(
              new ThreadState(
                  threadB,
                  sessionId,
                  threadA,
                  bTerminalPending ? bUser : bEnd,
                  REQUEST_HASH,
                  "child-B",
                  ThreadYoloPolicy.follow(threadA),
                  ThreadExecutionControl.RUNNABLE,
                  1L,
                  2L,
                  0L,
                  now,
                  now));
          tx.insertThread(
              new ThreadState(
                  threadC,
                  sessionId,
                  threadB,
                  cUser,
                  REQUEST_HASH,
                  "child-C",
                  ThreadYoloPolicy.follow(threadA),
                  ThreadExecutionControl.RUNNABLE,
                  1L,
                  2L,
                  0L,
                  now,
                  now));

          ThreadCommand bCommand = command(threadB, 1L, tx.nextId());
          tx.insertCommands(List.of(bCommand));
          tx.updateCommands(List.of(withConsumedTurnStart(bCommand, bStart)));
          ThreadCommand cCommand = command(threadC, 1L, tx.nextId());
          tx.insertCommands(List.of(cCommand));
          tx.updateCommands(List.of(withConsumedTurnStart(cCommand, cStart)));

          UUID joinAB = tx.nextId();
          tx.insertJoin(
              new ThreadJoin(
                  joinAB,
                  REQUEST_HASH,
                  threadA,
                  threadB,
                  1L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  now,
                  now,
                  JoinPurpose.TASK,
                  null));
          UUID joinBC = tx.nextId();
          tx.insertJoin(
              new ThreadJoin(
                  joinBC,
                  REQUEST_HASH,
                  threadB,
                  threadC,
                  1L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  now,
                  now,
                  JoinPurpose.TASK,
                  null));

          if (bTerminalPending) {
            insertSucceededModel(tx, threadB, bStart, bUser, now);
          }
          insertSucceededModel(tx, threadC, cStart, cUser, now);

          if (bTerminalPending) {
            tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadB), now);
          }
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadC), now);
          return new Tree(sessionId, rootEntryId, threadA, threadB, threadC, joinAB, joinBC);
        });
  }

  private ThreadProcessor processor(
      HarnessStore targetStore, Instant now, ScheduledExecutorService scheduler) {
    TurnResolver resolver =
        (threadId, path, preparation) ->
            new TurnResolver.Rejected(
                new AssistantError("RESOLVER_REJECT", "no continuation in test"));
    return processor(targetStore, now, scheduler, resolver);
  }

  private ThreadProcessor processor(
      HarnessStore targetStore,
      Instant now,
      ScheduledExecutorService scheduler,
      TurnResolver resolver) {
    return new ThreadProcessor(
        targetStore,
        resolver,
        new ThreadProcessorConfig(
            new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
            Duration.ofSeconds(5),
            () -> new CompactionConfig(20_000)),
        Clock.fixed(now, ZoneOffset.UTC),
        scheduler,
        Runnable::run);
  }

  private static void insertSucceededModel(
      HarnessStore.Transaction tx, UUID threadId, UUID turnStart, UUID requestHead, Instant now) {
    ModelInvocation model =
        new ModelInvocation(
            tx.nextId(),
            threadId,
            turnStart,
            requestHead,
            succeededRequest(),
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
    succeedModel(tx, model, assistantResponse(), now);
  }

  private static void succeedModel(
      HarnessStore.Transaction tx, ModelInvocation model, ProviderResponse response, Instant now) {
    ModelInvocation dispatching = model.beginDispatch(now);
    tx.updateModelInvocation(dispatching);
    ModelInvocation running = dispatching.markRunning(now);
    tx.updateModelInvocation(running);
    tx.updateModelInvocation(running.succeed(response, now));
  }

  /** 不调用外部 Gateway，沿真实模型状态转换提交响应，再让 Processor 应用终态。 */
  private void completeReadyModel(UUID threadId, Instant now) {
    store.transaction(
        tx -> {
          ThreadState thread = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, threadId);
          UUID start = tx.loadEntryPath(thread.headEntryId()).openTurnStart().orElseThrow().id();
          ModelInvocation model = tx.findModelInvocationByTurn(threadId, start).orElseThrow();
          tx.lockModelInvocation(model.id());
          succeedModel(tx, model, assistantResponse(), now);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), now);
          return null;
        });
  }

  /** 两种确定性调度都必须先消费孙回执，再以新的成功 final 冻结 B 的上游结果。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void successfulParentSettlesAfterConsumingChildReceiptInEitherOrder(boolean parentFirst) {
    Tree tree = seedThreeLevelTree(T1, true);
    ClaimedWork first = claim("first", T1);
    ClaimedWork second = claim("second", T1);
    ClaimedWork bClaim = first.target().id().equals(tree.threadB()) ? first : second;
    ClaimedWork cClaim = first.target().id().equals(tree.threadC()) ? first : second;
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadProcessor processor =
          processor(
              store,
              T1,
              scheduler,
              (threadId, path, preparation) ->
                  new TurnResolver.Resolved(succeededRequest(), 100_000, 16_384));
      if (parentFirst) {
        assertEquals(ThreadProcessResult.COMPLETED, processor.process(bClaim));
        assertFalse(join(tree.joinAB()).matched());
        assertTrue(commandsOf(tree.threadA()).isEmpty());
        assertFalse(hasThreadWork(tree.threadA()));
        assertEquals(ThreadProcessResult.COMPLETED, processor.process(cClaim));
      } else {
        assertEquals(ThreadProcessResult.COMPLETED, processor.process(cClaim));
        assertEquals(1, queuedOf(tree.threadB()).size());
        assertEquals(ThreadProcessResult.COMPLETED, processor.process(bClaim));
      }
      UUID earlyFinal = runtime.getThreadSnapshot(tree.threadB()).thread().headEntryId();
      assertFalse(join(tree.joinAB()).matched(), "a local final is not delegation quiescence");
      assertTrue(commandsOf(tree.threadA()).isEmpty());
      assertFalse(hasThreadWork(tree.threadA()));
      assertEquals(1, queuedOf(tree.threadB()).size());
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(claim("b-input", T1)));
      ThreadSnapshot processing = runtime.getThreadSnapshot(tree.threadB());
      assertEquals(2L, processing.thread().inputThroughSequence());
      assertEquals(ThreadRuntimeStatus.MODEL_READY, processing.runtimeStatus());
      assertFalse(join(tree.joinAB()).matched(), "INPUT consumption alone is not completion");
      completeReadyModel(tree.threadB(), T1);
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(claim("b-final", T1)));
      ThreadJoin settled = join(tree.joinAB());
      assertTrue(settled.matched());
      assertNotEquals(earlyFinal, settled.terminalEntryId());
      assertEquals(
          runtime.getThreadSnapshot(tree.threadB()).thread().headEntryId(),
          settled.terminalEntryId());
      assertNotNull(settled.finalAnswerEntryId());
      assertEquals(
          ThreadJoinOutcome.COMPLETED,
          runtime.projectJoinReceipt(tree.joinAB()).orElseThrow().outcome());
      assertEquals(
          "assistant reply", runtime.projectJoinReceipt(tree.joinAB()).orElseThrow().report());
      assertEquals(1, commandsOf(tree.threadA()).size());
      // 重放已完成的 claim 不得改变冻结入口或重投。
      assertEquals(ThreadProcessResult.LOST_OWNERSHIP, processor.process(bClaim));
      assertEquals(settled, join(tree.joinAB()));
      assertEquals(1, commandsOf(tree.threadA()).size());
    } finally {
      scheduler.shutdownNow();
    }
  }

  /**
   * 在 {@code updateJoin}（C 匹配其子 Join）返回之后暂停事务：此时 delegate 已执行、父命令尚未 insertCommands。 可选在该点抛异常模拟故障回滚。
   */
  private static HarnessStore latchingOnJoinMatch(
      HarnessStore delegate,
      CountDownLatch matched,
      CountDownLatch release,
      boolean failInsteadOfBlock) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("transaction")) {
                return invokeUnwrapped(method, delegate, args);
              }
              @SuppressWarnings("unchecked")
              Function<HarnessStore.Transaction, Object> callback =
                  (Function<HarnessStore.Transaction, Object>) args[0];
              return delegate.transaction(
                  tx -> {
                    AtomicBoolean paused = new AtomicBoolean(false);
                    HarnessStore.Transaction wrapped =
                        (HarnessStore.Transaction)
                            Proxy.newProxyInstance(
                                HarnessStore.Transaction.class.getClassLoader(),
                                new Class<?>[] {HarnessStore.Transaction.class},
                                (txProxy, txMethod, txArgs) -> {
                                  Object result = invokeUnwrapped(txMethod, tx, txArgs);
                                  if (txMethod.getName().equals("updateJoin")
                                      && paused.compareAndSet(false, true)) {
                                    matched.countDown();
                                    if (failInsteadOfBlock) {
                                      throw new RuntimeException(
                                          "simulated crash after join match, before parent enqueue");
                                    }
                                    awaitRelease(release);
                                  }
                                  return result;
                                });
                    return callback.apply(wrapped);
                  });
            });
  }

  private static Object invokeUnwrapped(Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      // 不能把目标方法的受检异常泄露为 UndeclaredThrowableException，否则 runner 可能无限重试。
      throw e.getCause() == null ? e : e.getCause();
    }
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timeout waiting for test release latch");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Test
  void childSettlementAndParentEnqueueAreAtomicUnderRealTreeLock() throws Exception {
    Instant now = T1;
    Tree tree = seedThreeLevelTree(now);
    ClaimedWork childClaim = claim("c-lease", now);

    CountDownLatch matched = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HarnessStore latchingStore = latchingOnJoinMatch(store, matched, release, false);

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      ThreadProcessor latchingProcessor = processor(latchingStore, now, scheduler);
      Future<ThreadProcessResult> childFuture =
          executor.submit(() -> latchingProcessor.process(childClaim));

      // 确定窗口：C 已在其事务内执行了 updateJoin（match），但尚未 insertCommands/提交。
      assertTrue(matched.await(10, TimeUnit.SECONDS));

      // 同一执行树的读取必须进入同一 PG advisory 树锁并被阻塞：以 pg_locks 未授予锁为真实证据。
      CountDownLatch observerStarted = new CountDownLatch(1);
      Future<ThreadSnapshot> observerFuture =
          executor.submit(
              () -> {
                observerStarted.countDown();
                return runtime.getThreadSnapshot(tree.threadB());
              });
      assertTrue(observerStarted.await(10, TimeUnit.SECONDS));
      awaitTrue(
          () -> waitingAdvisoryLockCount() >= 1,
          "observer snapshot must block on the same-tree PostgreSQL advisory lock");
      assertThrows(
          TimeoutException.class,
          () -> observerFuture.get(150, TimeUnit.MILLISECONDS),
          "observer must not observe C settled without the atomic parent enqueue");
      assertFalse(childFuture.isDone(), "child settlement transaction must still be in flight");

      release.countDown();
      assertEquals(ThreadProcessResult.COMPLETED, childFuture.get(10, TimeUnit.SECONDS));
      ThreadSnapshot observed = observerFuture.get(10, TimeUnit.SECONDS);
      assertEquals(ThreadRuntimeStatus.QUEUED, observed.runtimeStatus());
      assertEquals(1, observed.queuedCommands().size());
      assertEquals(
          tree.threadC(),
          ((NotificationCommandPayload) observed.queuedCommands().getFirst().payload())
              .sourceThreadId());

      // 释放后：C 结算 + J_BC 交付；B 恰好一条 queued NOTIFICATION + THREAD wake；A-上游仍未结算、A 无命令无唤醒。
      ThreadJoin joinBC = join(tree.joinBC());
      assertTrue(joinBC.matched(), "C must settle its own join");
      assertEquals(2L, joinBC.deliveryCommandSequence(), "delivery follows B's own source command");
      assertFalse(join(tree.joinAB()).matched(), "B must not be settled to A while B is unsettled");

      List<ThreadCommand> bQueued = queuedOf(tree.threadB());
      assertEquals(
          1,
          bQueued.size(),
          "B must receive exactly one queued notification; queued=" + describe(bQueued));
      ThreadCommand bDelivery = bQueued.getFirst();
      assertEquals(ThreadCommandType.NOTIFICATION, bDelivery.type());
      assertEquals(ThreadCommandState.QUEUED, bDelivery.state());
      assertEquals(joinBC.invocationId(), bDelivery.idempotencyKey());
      NotificationCommandPayload payload = (NotificationCommandPayload) bDelivery.payload();
      assertEquals(tree.threadC(), payload.sourceThreadId());
      assertTrue(hasThreadWork(tree.threadB()), "B must be woken to consume the report");
      assertFalse(hasThreadWork(tree.threadA()), "A must not be woken while B is unsettled");
      assertTrue(
          commandsOf(tree.threadA()).isEmpty(), "A must receive no command while B is unsettled");

      // 只有真实 Processor 继续 B（B 消费通知并到达新的不可继续失败终态）后，A 才恰好收到一次回执。
      ClaimedWork bClaim = claim("b-lease", now);
      assertEquals(tree.threadB(), bClaim.target().id());
      ThreadProcessor realProcessor = processor(store, now, scheduler);
      assertEquals(ThreadProcessResult.COMPLETED, realProcessor.process(bClaim));

      ThreadJoin settledAB = join(tree.joinAB());
      assertTrue(settledAB.matched(), "B's upstream join settles only after B's own next terminal");
      assertNotNull(settledAB.deliveryCommandSequence());
      List<ThreadCommand> aDeliveries = commandsOf(tree.threadA());
      assertEquals(1, aDeliveries.size(), "A must receive exactly one receipt");
      assertEquals(settledAB.invocationId(), aDeliveries.getFirst().idempotencyKey());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void abortedChildSettlementRollsBackEntirelyAndRetryDeliversExactlyOnce() {
    Instant now = T1;
    Tree tree = seedThreeLevelTree(now);
    ClaimedWork childClaim = claim("c-lease", now);
    ThreadSnapshot cBefore = runtime.getThreadSnapshot(tree.threadC());
    ThreadState bBefore = runtime.getThreadSnapshot(tree.threadB()).thread();
    ThreadJoin bcBefore = join(tree.joinBC());
    List<Entry> entriesBefore =
        store.transaction(tx -> tx.loadEntriesBySessionId(tree.sessionId()));
    var workBefore = store.transaction(tx -> tx.findWork(childClaim.target()).orElseThrow());

    CountDownLatch matched = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HarnessStore failingStore = latchingOnJoinMatch(store, matched, release, true);

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadProcessor failingProcessor = processor(failingStore, now, scheduler);
      RuntimeException failure =
          assertThrows(RuntimeException.class, () -> failingProcessor.process(childClaim));
      assertEquals("simulated crash after join match, before parent enqueue", failure.getMessage());
      assertEquals(0L, matched.getCount());

      // 整组一致回滚：Join/父序列/通知/wake 均未落地，C 的历史与模型未被改写。
      ThreadJoin joinBCAfterRollback = join(tree.joinBC());
      assertFalse(joinBCAfterRollback.matched());
      assertEquals(1, count("harness_thread_join", "invocation_id", tree.joinBC()));
      assertFalse(join(tree.joinAB()).matched());
      assertEquals(bcBefore, joinBCAfterRollback);
      assertEquals(cBefore, runtime.getThreadSnapshot(tree.threadC()));
      assertEquals(bBefore, runtime.getThreadSnapshot(tree.threadB()).thread());
      assertEquals(
          entriesBefore, store.transaction(tx -> tx.loadEntriesBySessionId(tree.sessionId())));
      assertEquals(
          workBefore, store.transaction(tx -> tx.findWork(childClaim.target()).orElseThrow()));
      assertTrue(queuedOf(tree.threadB()).isEmpty(), "no parent notification may survive rollback");
      assertFalse(hasThreadWork(tree.threadB()), "no parent wake may survive rollback");
      assertTrue(commandsOf(tree.threadA()).isEmpty());

      // 从同一 durable claim 重试：真实 Processor 干净地重新完成结算，恰一份回执。
      ThreadProcessor retryProcessor = processor(store, now, scheduler);
      assertEquals(ThreadProcessResult.COMPLETED, retryProcessor.process(childClaim));

      ThreadJoin retriedJoin = join(tree.joinBC());
      assertTrue(retriedJoin.matched());
      assertEquals(2L, retriedJoin.deliveryCommandSequence());
      assertEquals(
          1,
          queuedOf(tree.threadB()).size(),
          "retry must produce exactly one receipt; queued=" + describe(queuedOf(tree.threadB())));
      assertEquals(
          2,
          count("harness_thread_command", "thread_id", tree.threadB()),
          "B keeps its applied source command plus exactly one delivered notification");
      assertFalse(
          join(tree.joinAB()).matched(), "B's upstream join stays unmatched until B is continued");
      assertTrue(commandsOf(tree.threadA()).isEmpty());
      assertEquals(ThreadProcessResult.LOST_OWNERSHIP, retryProcessor.process(childClaim));
      assertEquals(retriedJoin, join(tree.joinBC()));
      assertEquals(1, queuedOf(tree.threadB()).size());
    } finally {
      scheduler.shutdownNow();
    }
  }

  private ClaimedWork claim(String leaseId, Instant now) {
    return store
        .transaction(
            tx -> tx.claimNextWork(WorkTargetType.THREAD, now, leaseId, Duration.ofSeconds(30)))
        .orElseThrow();
  }

  private int waitingAdvisoryLockCount() {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from pg_locks where locktype = 'advisory' and not granted",
            Integer.class);
    return count == null ? 0 : count;
  }

  private ThreadJoin join(UUID invocationId) {
    return store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
  }

  private List<ThreadCommand> commandsOf(UUID threadId) {
    return store.transaction(tx -> tx.loadCommandsByThread(threadId));
  }

  private List<ThreadCommand> queuedOf(UUID threadId) {
    return store.transaction(
        tx -> {
          tx.lockThread(threadId);
          return tx.loadQueuedCommands(threadId);
        });
  }

  private boolean hasThreadWork(UUID threadId) {
    return store.<Boolean>transaction(
        tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)).isPresent());
  }

  private int count(String table, String column, UUID value) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    return count == null ? 0 : count;
  }

  private static String describe(List<ThreadCommand> commands) {
    StringBuilder text = new StringBuilder("[");
    for (ThreadCommand command : commands) {
      text.append(command.sequence())
          .append('/')
          .append(command.type())
          .append('/')
          .append(command.state())
          .append('/')
          .append(command.idempotencyKey())
          .append(", ");
    }
    return text.append(']').toString();
  }

  private static void awaitTrue(BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for: " + message, interrupted);
      }
    }
    throw new AssertionError("timed out waiting for: " + message);
  }
}
