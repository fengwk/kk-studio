package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
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
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 验证控制面入口的锁边界：单实体 renameThread / setThreadYolo / decideToolApproval / getThreadSnapshot 只锁本
 * Thread（及本 Thread 的 Model/Tool），不取执行树锁；manualCompactionAvailability / compactThread 与
 * getThreadTree 等结构/跨 Thread 路径仍先取执行树根锁。另验证行锁失败时的事务回滚。
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
    // 测试意图：验证 ThreadTreeLocks.lockForThread 在不存在的线程 ID 上加锁时，回退到目标 ID 自身加锁，并返回空表示合法缺失。
    UUID nonExistentId = TestIds.id(999);
    List<String> locks = new ArrayList<>();
    HarnessStore recording = recordingStore(store, locks);

    Optional<ThreadState> found =
        recording.transaction(tx -> ThreadTreeLocks.lockForThread(tx, nonExistentId));

    assertTrue(found.isEmpty());
    assertEquals(List.of("lockTree:" + nonExistentId), locks);
  }

  /** 构造「加树锁后行锁读不到 Thread」的 Store，模拟持树锁的并发深删除在锁等待期间提交。 */
  private HarnessStore threadDeletedWhileAcquiringTreeLockStore() {
    return sabotagingStore(store, Map.of("lockThread", args -> Optional.empty()));
  }

  @Test
  void lockThreadWithParentOnRootLocksOnlyRootThreadAndSession() {
    // 测试意图：根 Thread 没有父，helper 只锁自身 Session 与自身 Thread，不额外取任何祖先行锁。
    Baseline baseline = seedBaseline(store);
    List<String> locks = new ArrayList<>();
    HarnessStore recording = recordingStore(store, locks);

    ThreadState locked =
        recording.transaction(
            tx -> ThreadLifecycleCoordinator.lockThreadWithParent(tx, baseline.threadId()));

    assertNotNull(locked);
    assertEquals(
        List.of(
            "lockTree:" + baseline.threadId(),
            "lockSessionForKeyShare:" + baseline.sessionId(),
            "lockThread:" + baseline.threadId()),
        locks);
  }

  @Test
  void lockThreadWithParentReturnsNullWhenThreadDeletedWhileAcquiringTreeLock() {
    // 测试意图：取得树锁后 Thread 已被并发删除时协调器返回 null，而不是抛异常。
    Baseline baseline = seedBaseline(store);
    UUID childId =
        createChildThread(
            store,
            baseline.threadId(),
            baseline.sessionId(),
            baseline.rootEntryId(),
            baseline.threadId());
    HarnessStore deletingStore = threadDeletedWhileAcquiringTreeLockStore();

    ThreadState locked =
        deletingStore.transaction(
            tx -> ThreadLifecycleCoordinator.lockThreadWithParent(tx, childId));

    assertNull(locked);
  }

  @Test
  void lockThreadWithParentReturnsNullAfterTreeLockWhenTargetDisappeared() {
    // 测试意图：第一次 findThread 仍有值，取得树锁后重读为空时立即返回 null，绝不取任何 Session/Thread 行锁。
    Baseline baseline = seedBaseline(store);
    List<String> operations = new ArrayList<>();
    HarnessStore firstVisibleThenMissing =
        firstFindVisibleThenMissingStore(baseline.threadId(), operations);

    ThreadState locked =
        firstVisibleThenMissing.transaction(
            tx -> ThreadLifecycleCoordinator.lockThreadWithParent(tx, baseline.threadId()));

    assertNull(locked);
    assertEquals(
        List.of(
            "findThread:" + baseline.threadId(),
            "lockTree:" + baseline.threadId(),
            "findThread:" + baseline.threadId()),
        operations);
  }

  @Test
  void renameThreadLocksOnlyItsOwnThread() {
    // 测试意图：renameThread 只改本行元数据，仅锁目标 thread，不取 tree lock。
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
    assertEquals(List.of("lockThread:" + childId), locks);
  }

  @Test
  void setThreadYoloLocksOnlyItsOwnThread() {
    // 测试意图：setThreadYolo 只改根行，仅锁目标 thread，不取 tree lock。
    // 只有执行根拥有独立 YOLO 开关（子代理的开关取自执行根），因此目标必须是根线程本身。
    Baseline baseline = seedBaseline(store);

    List<String> locks = new ArrayList<>();
    HarnessRuntime recordingRuntime =
        HarnessRuntimeTestSupport.runtime(recordingStore(store, locks), clock);

    ThreadState updated =
        recordingRuntime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), true));
    assertTrue(updated.yoloEnabled());
    assertEquals(List.of("lockThread:" + baseline.threadId()), locks);
  }

  @Test
  void decideToolApprovalLocksOnlyTheOwningThread() {
    // 测试意图：decideToolApproval 只锁 owning thread -> model -> tool siblings，不取 tree lock。
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
            "lockThread:" + baseline.threadId(),
            "lockModelInvocation:" + baseline.modelId(),
            "lockToolInvocationsByAssistantEntryId:" + baseline.assistantEntryId()),
        locks);
  }

  @Test
  void getThreadSnapshotLocksOnlyTheOwnedThread() {
    // 测试意图：getThreadSnapshot 只锁目标 thread 及其适用 invocation，不取 tree lock。
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
    assertEquals(List.of("lockThread:" + childId), locks);
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
  void compactThreadAcquiresTreeSessionAndThreadLocksOnceInItsSingleTransaction() {
    // 测试意图：手工压缩在同一事务内按 tree -> session -> thread 锁序完成 resolve 与提交，不重复规划。
    Fixture fixture = manualCompactionFixture();
    ClosedTurnBaseline baseline = seedCompactionEligibleTurn(fixture.store);

    List<String> locks = new ArrayList<>();
    HarnessStore recording = recordingStore(fixture.store, locks);
    HarnessRuntime recordingRuntime =
        new HarnessRuntime(recording, fixture.resolver, fixture.compactionConfigProvider);

    CompactThreadResult result =
        recordingRuntime.compactThread(new CompactThreadCommand(baseline.threadId(), 0L));
    assertNotNull(result);
    assertNotNull(result.childThreadId());
    assertTrue(
        fixture
            .store
            .transaction(
                tx -> tx.findModelInvocationByTurn(baseline.threadId(), result.turnStartEntryId()))
            .isEmpty());
    assertNotNull(
        fixture.store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, result.childThreadId()))
                    .orElse(null)));
    assertNull(
        fixture.store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElse(null)));

    // 结构性写入仍需完整锁序，但只在该事务内获取一次。
    assertEquals(
        List.of(
            "lockTree:" + baseline.threadId(),
            "lockSessionForKeyShare:" + baseline.sessionId(),
            "lockThread:" + baseline.threadId()),
        locks);
  }

  @Test
  void treeLockFailureRollsBackStructuralCompaction() {
    // 测试意图：真正的树结构写入（手工压缩）在 tree lock 失败时整体回滚，不创建子 Thread/Work，也不改父 head/version。
    Fixture fixture = manualCompactionFixture();
    ClosedTurnBaseline baseline = seedCompactionEligibleTurn(fixture.store);
    HarnessStore sabotagedStore =
        sabotagingStore(
            fixture.store,
            Map.of(
                "lockTree",
                args -> {
                  throw new IllegalStateException("simulated tree lock conflict");
                }));
    HarnessRuntime sabotagedRuntime =
        new HarnessRuntime(sabotagedStore, fixture.resolver, fixture.compactionConfigProvider);

    assertThrows(
        IllegalStateException.class,
        () -> sabotagedRuntime.compactThread(new CompactThreadCommand(baseline.threadId(), 0L)));

    ThreadState stored =
        fixture.store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals("main", stored.name());
    assertEquals(0L, stored.version());
    assertEquals(baseline.turnEndId(), stored.headEntryId());
    assertEquals(
        1,
        fixture.store.transaction(tx -> tx.listThreadsBySession(baseline.sessionId())).size(),
        "failed tree-lock plan must not create the compaction child");
  }

  @Test
  void threadLockFailureRollsBackSingleEntityRename() {
    // 测试意图：单实体 rename 的行锁失败同样整体回滚，不留下任何修改。
    Baseline baseline = seedBaseline(store);
    HarnessStore sabotagedStore =
        sabotagingStore(
            store,
            Map.of(
                "lockThread",
                args -> {
                  throw new IllegalStateException("simulated thread lock conflict");
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
                  rootThreadId,
                  false,
                  SpawnTrigger.MODEL_TOOL,
                  ThreadConstraints.NONE,
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

  /** 目标 Thread 第一次读取可见，取得树锁后的重读返回空；记录所有 lock* 与 findThread 调用。 */
  private HarnessStore firstFindVisibleThenMissingStore(UUID threadId, List<String> operations) {
    AtomicBoolean firstRead = new AtomicBoolean(true);
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("transaction")) {
                return method.invoke(store, args);
              }
              @SuppressWarnings("unchecked")
              Function<HarnessStore.Transaction, Object> callback =
                  (Function<HarnessStore.Transaction, Object>) args[0];
              return store.transaction(
                  tx -> {
                    HarnessStore.Transaction wrapped =
                        (HarnessStore.Transaction)
                            Proxy.newProxyInstance(
                                HarnessStore.Transaction.class.getClassLoader(),
                                new Class<?>[] {HarnessStore.Transaction.class},
                                (transactionProxy, transactionMethod, transactionArgs) -> {
                                  String name = transactionMethod.getName();
                                  if (name.startsWith("lock")
                                      || (name.equals("findThread")
                                          && threadId.equals(transactionArgs[0]))) {
                                    operations.add(name + ":" + transactionArgs[0]);
                                  }
                                  if (name.equals("findThread")
                                      && threadId.equals(transactionArgs[0])) {
                                    if (firstRead.compareAndSet(true, false)) {
                                      return transactionMethod.invoke(tx, transactionArgs);
                                    }
                                    return Optional.empty();
                                  }
                                  return transactionMethod.invoke(tx, transactionArgs);
                                });
                    return callback.apply(wrapped);
                  });
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
        (threadId, candidatePath, preparation) -> {
          if (preparation != null) {
            return new TurnResolver.CompactionResolved(
                HarnessRuntimeTestSupport.settings().model(),
                4096L,
                100_000,
                16_384,
                new BranchSettings(
                    "compaction",
                    HarnessRuntimeTestSupport.settings().model(),
                    HarnessRuntimeTestSupport.settings().environmentName(),
                    null));
          }
          return new TurnResolver.Resolved(
              HarnessRuntimeTestSupport.modelRequest(), 100_000, 16_384);
        };
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
    Instant now = T0;
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID firstStartId = tx.nextId();
          UUID firstUserId = tx.nextId();
          UUID firstAssistantId = tx.nextId();
          UUID firstEndId = tx.nextId();
          UUID secondStartId = tx.nextId();
          UUID secondUserId = tx.nextId();
          UUID secondAssistantId = tx.nextId();
          UUID secondEndId = tx.nextId();
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
                      null,
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
                  new ModelUsage(50_000L, 2L, 0L, 0L, 0L, 0L, 50_002L));
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
                      null,
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
                  threadId,
                  false,
                  SpawnTrigger.USER,
                  ThreadConstraints.NONE,
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
