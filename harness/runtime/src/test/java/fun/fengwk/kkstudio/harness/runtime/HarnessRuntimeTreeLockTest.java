package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 验证控制面入口（renameThread、setThreadYolo、decideToolApproval、getThreadSnapshot、
 * manualCompactionAvailability、compactThread）在获取任何业务行锁（Session/Thread/Model/Tool）之前
 * 先按规范获取执行树根锁（tree-lock-before-business-lock protocol），以及锁失败时的事务回滚。
 */
class HarnessRuntimeTreeLockTest {

  private InMemoryHarnessStore store;
  private TestClock clock;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    clock = new TestClock(T0);
    runtime = HarnessRuntimeTestSupport.runtime(store, clock);
  }

  @Test
  void lockForThreadOnRootThreadAcquiresLockOnRoot() {
    // 测试意图：验证 ThreadTreeLocks.lockForThread 在根线程上加锁时直接锁定根线程 ID。
    Baseline baseline = seedBaseline(store);
    List<String> locks = new ArrayList<>();
    HarnessStore recording = recordingStore(store, locks);

    recording.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, baseline.threadId());
          return null;
        });

    assertEquals(List.of("lockTree:" + baseline.threadId()), locks);
  }

  @Test
  void lockForThreadOnChildAndGrandchildThreadAcquiresLockOnRootThread() {
    // 测试意图：验证 ThreadTreeLocks.lockForThread 在子线程和孙子线程上加锁时，正确追溯 ancestor chain 并锁定执行树的根线程 ID。
    Baseline baseline = seedBaseline(store);
    UUID childId =
        createChildThread(
            store,
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            baseline.threadId());
    UUID grandChildId =
        createChildThread(
            store, childId, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId());

    List<String> childLocks = new ArrayList<>();
    HarnessStore childRecording = recordingStore(store, childLocks);
    childRecording.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, childId);
          return null;
        });
    assertEquals(List.of("lockTree:" + baseline.threadId()), childLocks);

    List<String> grandChildLocks = new ArrayList<>();
    HarnessStore grandChildRecording = recordingStore(store, grandChildLocks);
    grandChildRecording.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, grandChildId);
          return null;
        });
    assertEquals(List.of("lockTree:" + baseline.threadId()), grandChildLocks);
  }

  @Test
  void lockForThreadOnNonExistentThreadLocksTargetId() {
    // 测试意图：验证 ThreadTreeLocks.lockForThread 在不存在的线程 ID 上加锁时，回退到目标 ID 自身加锁。
    UUID nonExistentId = TestIds.id(999);
    List<String> locks = new ArrayList<>();
    HarnessStore recording = recordingStore(store, locks);

    recording.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, nonExistentId);
          return null;
        });

    assertEquals(List.of("lockTree:" + nonExistentId), locks);
  }

  @Test
  void lockForThreadFailsWhenExecutionTreeChangesDuringLockAcquisition() {
    // 测试意图：验证加锁后二次读取 ancestor chain 不一致时抛出 IllegalStateException，防止执行树漂移。
    Baseline baseline = seedBaseline(store);
    UUID childId =
        createChildThread(
            store,
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            baseline.threadId());

    AtomicInteger chainCallCount = new AtomicInteger();
    HarnessStore sabotagedStore =
        sabotagingStore(
            store,
            Map.of(
                "findAncestorChain",
                args -> {
                  int call = chainCallCount.incrementAndGet();
                  if (call == 1) {
                    return List.of(childId, baseline.threadId());
                  }
                  // 第二次读取模拟祖先链变化
                  return List.of(childId, TestIds.id(888));
                }));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                sabotagedStore.transaction(
                    tx -> {
                      ThreadTreeLocks.lockForThread(tx, childId);
                      return null;
                    }));
    assertTrue(error.getMessage().contains("execution tree changed"));
  }

  @Test
  void renameThreadAcquiresTreeLockBeforeThreadLock() {
    // 测试意图：验证 renameThread 严格先获取 root 的 tree lock，再获取目标 thread 的行锁。
    Baseline baseline = seedBaseline(store);
    UUID childId =
        createChildThread(
            store,
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            baseline.threadId());

    List<String> locks = new ArrayList<>();
    HarnessRuntime recordingRuntime =
        HarnessRuntimeTestSupport.runtime(recordingStore(store, locks), clock);

    ThreadState renamed =
        recordingRuntime.renameThread(new RenameThreadCommand(childId, "new-child-name"));
    assertEquals("new-child-name", renamed.name());
    assertEquals(List.of("lockTree:" + baseline.threadId(), "lockThread:" + childId), locks);
  }

  @Test
  void setThreadYoloAcquiresTreeLockBeforeThreadLock() {
    // 测试意图：验证 setThreadYolo 严格先获取 root 的 tree lock，再获取目标 thread 的行锁。
    // 只有执行根拥有独立 YOLO 开关（子代理恒 Follow 执行根），因此目标必须是根线程本身。
    Baseline baseline = seedBaseline(store);

    List<String> locks = new ArrayList<>();
    HarnessRuntime recordingRuntime =
        HarnessRuntimeTestSupport.runtime(recordingStore(store, locks), clock);

    ThreadState updated =
        recordingRuntime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), true));
    assertTrue(updated.yoloPolicy().isEnabled());
    assertEquals(
        List.of("lockTree:" + baseline.threadId(), "lockThread:" + baseline.threadId()), locks);
  }

  @Test
  void decideToolApprovalAcquiresTreeLockBeforeThreadLock() {
    // 测试意图：验证 decideToolApproval 严格先获取 root 的 tree lock，再获取 thread/model/tool 行锁。
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);

    List<String> locks = new ArrayList<>();
    HarnessRuntime recordingRuntime =
        HarnessRuntimeTestSupport.runtime(recordingStore(store, locks), clock);

    ToolApprovalCommand command =
        new ToolApprovalCommand(
            baseline.threadId(),
            baseline.toolId(),
            ToolApprovalDecision.ALLOWED,
            TestIds.id(1),
            "alice",
            null);
    ToolInvocation decided = recordingRuntime.decideToolApproval(command);
    assertEquals(ToolInvocationStatus.READY, decided.status());
    assertEquals(
        List.of(
            "lockTree:" + baseline.threadId(),
            "lockThread:" + baseline.threadId(),
            "lockModelInvocation:" + baseline.modelId(),
            "lockToolInvocationsByAssistantEntryId:" + baseline.assistantEntryId()),
        locks);
  }

  @Test
  void getThreadSnapshotAcquiresTreeLockBeforeThreadLock() {
    // 测试意图：验证 getThreadSnapshot 严格先获取 root 的 tree lock，再获取 thread 行锁及后续 invocation 锁。
    Baseline baseline = seedBaseline(store);
    UUID childId =
        createChildThread(
            store,
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            baseline.threadId());

    List<String> locks = new ArrayList<>();
    HarnessRuntime recordingRuntime =
        HarnessRuntimeTestSupport.runtime(recordingStore(store, locks), clock);

    ThreadSnapshot snapshot = recordingRuntime.getThreadSnapshot(childId);
    assertNotNull(snapshot);
    assertEquals(List.of("lockTree:" + baseline.threadId(), "lockThread:" + childId), locks);
  }

  @Test
  void manualCompactionAvailabilityAcquiresTreeLockBeforeSessionAndThreadLocks() {
    // 测试意图：验证 manualCompactionAvailability 严格先获取 tree lock，再获取 Session KEY SHARE 和 Thread 行锁。
    Baseline baseline = seedBaseline(store);
    UUID childId =
        createChildThread(
            store,
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            baseline.threadId());

    List<String> locks = new ArrayList<>();
    HarnessRuntime recordingRuntime =
        HarnessRuntimeTestSupport.runtime(recordingStore(store, locks), clock);

    ManualCompactionAvailability availability =
        recordingRuntime.manualCompactionAvailability(childId);
    assertFalse(availability.available());
    assertEquals(
        List.of(
            "lockTree:" + baseline.threadId(),
            "lockSessionForKeyShare:" + baseline.sessionId(),
            "lockThread:" + childId),
        locks);
  }

  @Test
  void compactThreadAcquiresTreeLockInPlanAndCommitTransactions() {
    // 测试意图：验证 compactThread 在两阶段事务（planManual 与 commitManual）中均先获取 tree lock，再获取 session 和 thread
    // 行锁。
    Fixture fixture = manualCompactionFixture();
    ClosedTurnBaseline baseline = seedCompactionEligibleTurn(fixture.store);

    List<String> locks = new ArrayList<>();
    HarnessStore recording = recordingStore(fixture.store, locks);
    HarnessRuntime recordingRuntime =
        new HarnessRuntime(
            recording, fixture.clock, fixture.resolver, fixture.compactionConfigProvider);

    CompactThreadResult result =
        recordingRuntime.compactThread(new CompactThreadCommand(baseline.threadId(), 0L));
    assertNotNull(result);

    // 应该按顺序在第一阶段获取 tree -> session -> thread，在第二阶段再次获取 tree -> session -> thread
    assertEquals(
        List.of(
            "lockTree:" + baseline.threadId(),
            "lockSessionForKeyShare:" + baseline.sessionId(),
            "lockThread:" + baseline.threadId(),
            "lockTree:" + baseline.threadId(),
            "lockSessionForKeyShare:" + baseline.sessionId(),
            "lockThread:" + baseline.threadId()),
        locks);
  }

  @Test
  void treeLockFailureRollsBackTransaction() {
    // 测试意图：验证当 tree lock 获取失败抛出异常时，整个事务回滚，无任何行被修改。
    Baseline baseline = seedBaseline(store);
    HarnessStore sabotagedStore =
        sabotagingStore(
            store,
            Map.of(
                "lockTree",
                args -> {
                  throw new IllegalStateException("simulated tree lock conflict");
                }));

    HarnessRuntime sabotagedRuntime = HarnessRuntimeTestSupport.runtime(sabotagedStore, clock);

    assertThrows(
        IllegalStateException.class,
        () ->
            sabotagedRuntime.renameThread(
                new RenameThreadCommand(baseline.threadId(), "should-not-persist")));

    // 确认未持久化
    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals("main", stored.name());
  }

  private static UUID createChildThread(
      InMemoryHarnessStore store,
      UUID parentThreadId,
      UUID sessionId,
      UUID headEntryId,
      UUID rootThreadId) {
    UUID childId = UUID.randomUUID();
    Instant now = T0;
    store.transaction(
        tx -> {
          ThreadState child =
              new ThreadState(
                  childId,
                  sessionId,
                  parentThreadId,
                  headEntryId,
                  CREATION_REQUEST_HASH,
                  "child-branch",
                  ThreadYoloPolicy.follow(rootThreadId),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  now,
                  now);
          tx.insertThread(child);
          return null;
        });
    return childId;
  }

  private static HarnessStore recordingStore(
      InMemoryHarnessStore delegate, List<String> lockCalls) {
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
                                  (transactionProxy, transactionMethod, transactionArgs) -> {
                                    String name = transactionMethod.getName();
                                    if (name.equals("lockTree")
                                        || name.equals("lockSessionForKeyShare")
                                        || name.equals("lockSessionForUpdate")
                                        || name.equals("lockThread")
                                        || name.equals("lockModelInvocation")
                                        || name.equals("lockToolInvocation")
                                        || name.equals("lockToolInvocationsByAssistantEntryId")) {
                                      lockCalls.add(name + ":" + transactionArgs[0]);
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }

  private static HarnessStore sabotagingStore(
      InMemoryHarnessStore delegate, Map<String, Function<Object[], Object>> overrides) {
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
                                  (transactionProxy, transactionMethod, transactionArgs) -> {
                                    String name = transactionMethod.getName();
                                    Function<Object[], Object> override = overrides.get(name);
                                    if (override != null) {
                                      return override.apply(transactionArgs);
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }

  private static Fixture manualCompactionFixture() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    TurnResolver resolver =
        (threadId, candidatePath, preparation) ->
            new TurnResolver.Resolved(HarnessRuntimeTestSupport.modelRequest(), 100_000, 16_384);
    CompactionConfigProvider provider = () -> CompactionConfig.DEFAULT;
    return new Fixture(store, clock, resolver, provider);
  }

  private record Fixture(
      InMemoryHarnessStore store,
      Clock clock,
      TurnResolver resolver,
      CompactionConfigProvider compactionConfigProvider) {}

  private record ClosedTurnBaseline(UUID sessionId, UUID threadId, UUID turnEndId) {}

  private static ClosedTurnBaseline seedCompactionEligibleTurn(InMemoryHarnessStore store) {
    UUID sessionId = TestIds.id(1);
    UUID threadId = TestIds.id(2);
    UUID rootEntryId = TestIds.id(3);
    UUID firstStartId = TestIds.id(4);
    UUID firstUserId = TestIds.id(5);
    UUID firstAssistantId = TestIds.id(6);
    UUID firstEndId = TestIds.id(7);
    UUID secondStartId = TestIds.id(8);
    UUID secondUserId = TestIds.id(9);
    UUID secondAssistantId = TestIds.id(10);
    UUID secondEndId = TestIds.id(11);
    Instant now = T0;
    return store.transaction(
        tx -> {
          tx.insertSession(new Session(sessionId, "main", now));
          tx.insertEntry(
              new Entry(
                  rootEntryId,
                  sessionId,
                  null,
                  new RootPayload(HarnessRuntimeTestSupport.settings()),
                  now));
          tx.insertEntry(
              new Entry(
                  firstStartId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT,
                      HarnessRuntimeTestSupport.settings(),
                      threadId,
                      100_000,
                      16_384,
                      null),
                  now));
          tx.insertEntry(
              new Entry(
                  firstUserId,
                  sessionId,
                  firstStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent("historical user " + "h".repeat(50_000)))),
                      null,
                      null),
                  now));
          AssistantMessageMetadata metadata =
              new AssistantMessageMetadata(
                  GenerationStopReason.COMPLETE,
                  new ModelUsage(50_000L, 2L, 0L, 0L, 0L, 0L, 50_002L),
                  new ModelCost(
                      "USD",
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO,
                      BigDecimal.ZERO));
          tx.insertEntry(
              new Entry(
                  firstAssistantId,
                  sessionId,
                  firstUserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(
                              new TextMessageContent(
                                  "historical assistant " + "a".repeat(50_000)))),
                      metadata,
                      null),
                  now));
          tx.insertEntry(
              new Entry(
                  firstEndId,
                  sessionId,
                  firstAssistantId,
                  new TurnEndPayload(firstStartId, TurnEndOutcome.COMPLETED, false, null, null),
                  now));
          tx.insertEntry(
              new Entry(
                  secondStartId,
                  sessionId,
                  firstEndId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT,
                      HarnessRuntimeTestSupport.settings(),
                      threadId,
                      100_000,
                      16_384,
                      null),
                  now));
          tx.insertEntry(
              new Entry(
                  secondUserId,
                  sessionId,
                  secondStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER,
                          List.of(new TextMessageContent("user " + "x".repeat(90_000)))),
                      null,
                      null),
                  now));
          tx.insertEntry(
              new Entry(
                  secondAssistantId,
                  sessionId,
                  secondUserId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.ASSISTANT,
                          List.of(new TextMessageContent("assistant reply"))),
                      metadata,
                      null),
                  now));
          tx.insertEntry(
              new Entry(
                  secondEndId,
                  sessionId,
                  secondAssistantId,
                  new TurnEndPayload(secondStartId, TurnEndOutcome.COMPLETED, false, null, null),
                  now));
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  null,
                  secondEndId,
                  CREATION_REQUEST_HASH,
                  "main",
                  ThreadYoloPolicy.root(false),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  now,
                  now));
          return new ClosedTurnBaseline(sessionId, threadId, secondEndId);
        });
  }
}
