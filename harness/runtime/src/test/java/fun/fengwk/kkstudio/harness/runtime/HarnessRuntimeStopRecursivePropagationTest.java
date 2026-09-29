package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T6;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModelWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.MultiToolBaseline;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinProjector;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 递归停止传播（Recursive Stop Propagation）与门禁测试。
 *
 * <p>验证目标 Thread 被 Stop 时，其所有永久后代 Thread（无论属于同 Session 还是子 Session，无论是否有 join） 均在同一个短事务内按规范锁序（Tree
 * -> Sessions -> Threads -> Commands -> Models -> Tools -> Work） 被原子停止，并写下 durable 的 STOPPED
 * 事实边界，同时清理各层活跃的 Work/Commands/Invocations。
 */
class HarnessRuntimeStopRecursivePropagationTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = runtime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void threeLevelHierarchyIsStoppedRecursivelyInSingleTransaction() {
    // 测试意图：验证三层执行树（Root -> Child -> Grandchild）在 Root 执行 Stop 时，
    // Root、Child 和 Grandchild 均在单事务内被递归停止，各自写入 STOPPED head 事实并推进 version。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChildId = createChildThread(store, childId, root.sessionId(), root.rootEntryId());

    seedQueuedCommand(store, childId, 1L, userMessagePayload("child-cmd"), TestIds.id(10));
    seedThreadWork(store, root.threadId());
    seedThreadWork(store, childId);
    seedThreadWork(store, grandChildId);

    UUID stopRequestId = TestIds.id(1);
    StopResult result = runtime.stop(new StopCommand(root.threadId(), stopRequestId, 0));

    assertFalse(result.replayed());
    assertEquals(1L, result.thread().version());
    assertEquals(
        stopRequestId, assertStoppedTurnEnd(root.threadId(), stopRequestId).closeRequestId());

    UUID expectedChildStopId = StopControl.deriveChildStopRequestId(stopRequestId, childId);
    UUID expectedGrandChildStopId =
        StopControl.deriveChildStopRequestId(expectedChildStopId, grandChildId);

    TurnEndPayload childEnd = assertStoppedTurnEnd(childId, expectedChildStopId);
    assertEquals(expectedChildStopId, childEnd.closeRequestId());
    ThreadState childState = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    assertEquals(1L, childState.version());

    TurnEndPayload grandChildEnd = assertStoppedTurnEnd(grandChildId, expectedGrandChildStopId);
    assertEquals(expectedGrandChildStopId, grandChildEnd.closeRequestId());
    ThreadState grandChildState =
        store.transaction(tx -> tx.findThread(grandChildId).orElseThrow());
    assertEquals(1L, grandChildState.version());

    // 子线程排队命令已被取消
    ThreadCommand childCmd =
        store.transaction(
            tx -> tx.findCommandByIdempotencyKey(childId, TestIds.id(10)).orElseThrow());
    assertEquals(ThreadCommandState.CANCELLED, childCmd.state());
    assertEquals(expectedChildStopId, childCmd.stopRequestId());

    // 所有线程的 Work 均被清除
    assertFalse(hasWork(root.threadId(), WorkTargetType.THREAD));
    assertFalse(hasWork(childId, WorkTargetType.THREAD));
    assertFalse(hasWork(grandChildId, WorkTargetType.THREAD));
  }

  @Test
  void stoppingIntermediateChildOnlyStopsItsDescendantsAndLeavesRootUntouched() {
    // 测试意图：验证对中间层 Child 执行 Stop 时，仅 Child 与 Grandchild 被停止，Root 保持原样不被触碰。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChildId = createChildThread(store, childId, root.sessionId(), root.rootEntryId());

    seedThreadWork(store, root.threadId());
    seedThreadWork(store, childId);
    seedThreadWork(store, grandChildId);

    UUID stopRequestId = TestIds.id(2);
    StopResult result = runtime.stop(new StopCommand(childId, stopRequestId, 0));

    assertFalse(result.replayed());
    assertEquals(1L, result.thread().version());
    assertEquals(stopRequestId, assertStoppedTurnEnd(childId, stopRequestId).closeRequestId());

    UUID expectedGrandChildStopId =
        StopControl.deriveChildStopRequestId(stopRequestId, grandChildId);
    assertEquals(
        expectedGrandChildStopId,
        assertStoppedTurnEnd(grandChildId, expectedGrandChildStopId).closeRequestId());

    // Root 未被触碰：version 仍为 0，head 未变成 STOPPED
    ThreadState rootState = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    assertEquals(0L, rootState.version());
    assertEquals(root.rootEntryId(), rootState.headEntryId());
    assertTrue(hasWork(root.threadId(), WorkTargetType.THREAD));
  }

  @Test
  void recursiveStopWorksAcrossDifferentSessions() {
    // 测试意图：验证后代 Thread 属于独立子 Session 时，所有 Session 被按 UuidOrder 锁定并正确停止各 Session 的 Thread。
    Baseline root = seedBaseline(store);

    UUID childSessionId = UUID.randomUUID();
    UUID childRootEntryId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();

    store.transaction(
        tx -> {
          tx.insertSession(new Session(childSessionId, "child-session", T0));
          tx.insertEntry(
              new Entry(childRootEntryId, childSessionId, null, new RootPayload(settings()), T0));
          tx.insertThread(
              new ThreadState(
                  childId,
                  childSessionId,
                  root.threadId(),
                  childRootEntryId,
                  CREATION_REQUEST_HASH,
                  "child-thread",
                  false,
                  ThreadLifecycleStatus.IDLE,
                  1L,
                  0L,
                  T0,
                  T0));
          return null;
        });

    UUID stopRequestId = TestIds.id(3);
    StopResult result = runtime.stop(new StopCommand(root.threadId(), stopRequestId, 0));

    assertFalse(result.replayed());
    assertEquals(1L, result.thread().version());

    UUID expectedChildStopId = StopControl.deriveChildStopRequestId(stopRequestId, childId);
    TurnEndPayload childEnd = assertStoppedTurnEnd(childId, expectedChildStopId);
    assertEquals(expectedChildStopId, childEnd.closeRequestId());

    ThreadState childState = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    assertEquals(1L, childState.version());
    assertEquals(childSessionId, childState.sessionId());
  }

  @Test
  void recursiveStopStopsDescendantsRegardlessOfNoJoinOrPendingJoin() {
    // 测试意图：验证无 join 记录的子线程与携带 pending join 记录的子线程均能被正常递归停止。
    Baseline root = seedBaseline(store);
    UUID childWithoutJoin =
        createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID childWithPendingJoin =
        createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    seedQueuedCommand(
        store, childWithPendingJoin, 1L, userMessagePayload("child-cmd"), TestIds.id(10));

    UUID joinInvocationId = UUID.randomUUID();
    store.transaction(
        tx -> {
          tx.lockThread(childWithPendingJoin);
          tx.insertJoin(
              new ThreadJoin(
                  joinInvocationId,
                  CREATION_REQUEST_HASH,
                  root.threadId(),
                  childWithPendingJoin,
                  1L,
                  0L,
                  "test-agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          return null;
        });

    UUID stopRequestId = TestIds.id(4);
    StopResult result = runtime.stop(new StopCommand(root.threadId(), stopRequestId, 0));

    assertFalse(result.replayed());
    assertEquals(1L, result.thread().version());

    UUID stopId1 = StopControl.deriveChildStopRequestId(stopRequestId, childWithoutJoin);
    UUID stopId2 = StopControl.deriveChildStopRequestId(stopRequestId, childWithPendingJoin);

    assertEquals(stopId1, assertStoppedTurnEnd(childWithoutJoin, stopId1).closeRequestId());
    assertEquals(stopId2, assertStoppedTurnEnd(childWithPendingJoin, stopId2).closeRequestId());
  }

  @Test
  void lateCallbackOnDescendantLosesOwnershipAfterParentStop() {
    // 测试意图：验证子孙线程正在运行模型或工具调用时，父级 Stop 会清空子孙的所有 Work 租约，
    // 使得迟到的子孙执行回调立即失去 ownership。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    UUID childModelId = seedChildRunningModel(store, childId, root.sessionId(), root.rootEntryId());
    seedThreadWork(store, childId);
    seedModelWork(store, childModelId);

    ClaimedWork childModelClaim =
        store.transaction(
            tx ->
                tx.claimNextWork(
                        WorkTargetType.MODEL, T3, "child-model-lease", Duration.between(T3, T6))
                    .orElseThrow());

    runtime.stop(new StopCommand(root.threadId(), TestIds.id(5), 0));

    // 子线程 Model Work 已被删除，迟到的 child callback 失去 ownership
    assertTrue(store.transaction(tx -> tx.lockClaimedWork(childModelClaim, T5)).isEmpty());
    // 子线程 ModelInvocation 行已被物理删除
    assertTrue(store.transaction(tx -> tx.findModelInvocation(childModelId)).isEmpty());
  }

  @Test
  void replayOnParentIsDeterministicAndIdempotentForEntireTree() {
    // 测试意图：验证使用相同 stopRequestId 对 Root 进行重试调用时，返回精确重放事实且不重复写入任何停止屏障或递增版本。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChildId = createChildThread(store, childId, root.sessionId(), root.rootEntryId());

    UUID stopRequestId = TestIds.id(6);
    StopResult first = runtime.stop(new StopCommand(root.threadId(), stopRequestId, 0));
    assertFalse(first.replayed());
    assertEquals(1L, first.thread().version());

    ThreadState rootV1 = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    ThreadState childV1 = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    ThreadState grandChildV1 = store.transaction(tx -> tx.findThread(grandChildId).orElseThrow());

    // 再次以相同 stopRequestId 请求（即使 expectedVersion 过期）
    StopResult replay = runtime.stop(new StopCommand(root.threadId(), stopRequestId, 0));
    assertTrue(replay.replayed());
    assertEquals(first.stoppedTurnEndEntryId(), replay.stoppedTurnEndEntryId());

    ThreadState rootV2 = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    ThreadState childV2 = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    ThreadState grandChildV2 = store.transaction(tx -> tx.findThread(grandChildId).orElseThrow());

    assertEquals(rootV1.version(), rootV2.version());
    assertEquals(rootV1.headEntryId(), rootV2.headEntryId());
    assertEquals(childV1.version(), childV2.version());
    assertEquals(childV1.headEntryId(), childV2.headEntryId());
    assertEquals(grandChildV1.version(), grandChildV2.version());
    assertEquals(grandChildV1.headEntryId(), grandChildV2.headEntryId());
  }

  @Test
  void staleVersionOnParentAbortsAndRollsBackEntireTree() {
    // 测试意图：验证父级 Stop expectedVersion 不匹配时抛出 STALE_VERSION 冲突异常，整棵执行树状态均不发生修改。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    // 人工推进父版本到 1
    store.transaction(
        tx -> {
          ThreadState current = tx.lockThread(root.threadId()).orElseThrow();
          tx.updateThread(current.touchVersion(T3));
          return null;
        });

    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.stop(new StopCommand(root.threadId(), TestIds.id(7), 0)));

    ThreadState rootState = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    ThreadState childState = store.transaction(tx -> tx.findThread(childId).orElseThrow());

    assertEquals(1L, rootState.version());
    assertEquals(root.rootEntryId(), rootState.headEntryId());
    assertEquals(0L, childState.version());
    assertEquals(root.rootEntryId(), childState.headEntryId());
  }

  @Test
  void treeAndRowLocksFollowCanonicalOrderAcrossAllDescendants() {
    // 测试意图：验证整个递归 Stop 事务中，树锁与各实体行锁严格满足：
    // lockTree -> lockSessionForKeyShare (UuidOrder) -> lockThread (UuidOrder) ->
    // loadQueuedCommands -> lockWork -> deleteWork
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    List<String> calls = new ArrayList<>();
    HarnessStore recording = recordingStore(store, calls);
    HarnessRuntime recRuntime = runtime(recording, Clock.fixed(T5, ZoneOffset.UTC));

    recRuntime.stop(new StopCommand(root.threadId(), TestIds.id(8), 0));

    int treeLockIdx = calls.indexOf("lockTree:" + root.threadId());
    assertTrue(treeLockIdx >= 0, "lockTree must be called");

    int sessionLockIdx = calls.indexOf("lockSessionForKeyShare:" + root.sessionId());
    assertTrue(sessionLockIdx > treeLockIdx, "lockSession must occur after lockTree");

    int threadLockRootIdx = calls.indexOf("lockThread:" + root.threadId());
    int threadLockChildIdx = calls.indexOf("lockThread:" + childId);
    assertTrue(threadLockRootIdx > sessionLockIdx);
    assertTrue(threadLockChildIdx > sessionLockIdx);

    int lastThreadLock = Math.max(threadLockRootIdx, threadLockChildIdx);
    int firstWorkLock = calls.indexOf("lockWork:THREAD:" + root.threadId());
    if (firstWorkLock < 0) {
      firstWorkLock = calls.indexOf("lockWork:THREAD:" + childId);
    }
    if (firstWorkLock >= 0) {
      assertTrue(firstWorkLock > lastThreadLock, "lockWork must occur after lockThread");
    }
  }

  @Test
  void terminalApplyPendingOnChildBlocksStopAndRollsBackEntireTree() {
    // 测试意图：验证子线程存在未决的 terminal ModelInvocation 时，Stop 抛出 TERMINAL_APPLY_PENDING 冲突异常，
    // 父子线程状态均完整回滚。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              turnStartEntry(turnStartId, root.sessionId(), root.rootEntryId(), T1, childId));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, root.sessionId(), turnStartId, T1));
          var requestSpec = HarnessRuntimeTestSupport.tooledModelRequest(List.of("bash"));
          var response = HarnessRuntimeTestSupport.responseWithToolCalls("call-1");
          UUID assistantId = tx.nextId();
          tx.insertEntry(
              HarnessRuntimeTestSupport.mappedAssistantEntry(
                  assistantId, root.sessionId(), userEntryId, T1, requestSpec, response));
          ThreadState child = tx.lockThread(childId).orElseThrow();
          // thread head 停留在 userEntryId，尚未推进到 assistantId
          tx.updateThread(child.advanceHead(userEntryId, T1));
          UUID modelId = tx.nextId();
          ModelInvocation model =
              HarnessRuntimeTestSupport.modelInvocationWithRequest(
                  modelId, childId, turnStartId, userEntryId, requestSpec, T1);
          tx.insertModelInvocation(model);
          ModelInvocation dispatching = model.beginDispatch(T2);
          tx.updateModelInvocation(dispatching);
          ModelInvocation running = dispatching.markRunning(T2);
          tx.updateModelInvocation(running);
          ModelInvocation succeeded = running.succeed(response, T3);
          tx.updateModelInvocation(succeeded);
          return null;
        });

    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.stop(new StopCommand(root.threadId(), TestIds.id(9), 0)));

    ThreadState rootState = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    assertEquals(0L, rootState.version());
    assertEquals(root.rootEntryId(), rootState.headEntryId());
  }

  @Test
  void childOpenTurnWithCompleteAssistantResultIsClosedByReusingIt() {
    // 测试意图：验证子线程已有完整 assistant 结果的 open turn 被 Stop 停止时，直接在其内部闭合为 STOPPED TURN_END，
    // 既不新增第二个 TURN_START，也不伪造模型完成或追加多余取消屏障。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              turnStartEntry(turnStartId, root.sessionId(), root.rootEntryId(), T1, childId));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, root.sessionId(), turnStartId, T1));
          UUID assistantId = tx.nextId();
          tx.insertEntry(
              HarnessRuntimeTestSupport.assistantEntry(
                  assistantId, root.sessionId(), userEntryId, T1));
          ThreadState child = tx.lockThread(childId).orElseThrow();
          tx.updateThread(child.advanceHead(assistantId, T1));
          return null;
        });

    UUID stopRequestId = TestIds.id(10);
    StopResult result = runtime.stop(new StopCommand(root.threadId(), stopRequestId, 0));
    assertFalse(result.replayed());

    UUID expectedChildStopId = StopControl.deriveChildStopRequestId(stopRequestId, childId);
    TurnEndPayload childEnd = assertStoppedTurnEnd(childId, expectedChildStopId);
    assertEquals(expectedChildStopId, childEnd.closeRequestId());

    ThreadState childState = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    EntryPath childPath = store.transaction(tx -> tx.loadEntryPath(childState.headEntryId()));
    // 子分支路径：ROOT -> TURN_START(INPUT) -> USER -> ASSISTANT -> TURN_END(STOPPED)
    assertEquals(5, childPath.entries().size());
  }

  @Test
  void threeLevelHierarchyStatusesAndJoinReceiptsSettledRecursively() {
    // 测试意图：验证三层执行树（Root -> Child -> Grandchild）在 Root 执行 Stop 时，
    // 各层生命周期状态递归结算为 IDLE，各层 matchable joins（含 Root ticket）均与固定的 STOPPED head 匹配，
    // 且因父级处于 STOPPED 边界，delivery 凭据交付被冻结（保持 pending delivery，不注入命令也不唤醒父级）。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChildId = createChildThread(store, childId, root.sessionId(), root.rootEntryId());

    UUID grandChildJoinId = UUID.randomUUID();
    UUID childJoinId = UUID.randomUUID();
    UUID rootTicketId = UUID.randomUUID();

    seedQueuedCommand(store, root.threadId(), 1L, userMessagePayload("root-cmd"), TestIds.id(9));
    seedQueuedCommand(store, grandChildId, 1L, userMessagePayload("gc-cmd"), TestIds.id(10));
    seedQueuedCommand(store, childId, 1L, userMessagePayload("c-cmd"), TestIds.id(11));

    store.transaction(
        tx -> {
          List<UUID> setupThreads =
              List.of(root.threadId(), childId, grandChildId).stream()
                  .sorted(UuidOrder.COMPARATOR)
                  .toList();
          for (UUID tid : setupThreads) {
            tx.lockThread(tid);
          }
          tx.insertJoin(
              new ThreadJoin(
                  grandChildJoinId,
                  CREATION_REQUEST_HASH,
                  childId,
                  grandChildId,
                  1L,
                  0L,
                  "agent-gc",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          tx.insertJoin(
              new ThreadJoin(
                  childJoinId,
                  CREATION_REQUEST_HASH,
                  root.threadId(),
                  childId,
                  1L,
                  0L,
                  "agent-child",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          tx.insertJoin(
              new ThreadJoin(
                  rootTicketId,
                  CREATION_REQUEST_HASH,
                  null,
                  root.threadId(),
                  1L,
                  0L,
                  "agent-root",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          // 人工将各层置为非 IDLE 状态
          ThreadState r = tx.lockThread(root.threadId()).orElseThrow();
          tx.updateThread(r.changeLifecycleStatus(ThreadLifecycleStatus.WAITING_CHILDREN, T0));
          ThreadState c = tx.lockThread(childId).orElseThrow();
          tx.updateThread(c.changeLifecycleStatus(ThreadLifecycleStatus.WAITING_CHILDREN, T0));
          ThreadState g = tx.lockThread(grandChildId).orElseThrow();
          tx.updateThread(g.changeLifecycleStatus(ThreadLifecycleStatus.ACTIVE, T0));
          return null;
        });

    UUID stopRequestId = TestIds.id(11);
    StopResult result = runtime.stop(new StopCommand(root.threadId(), stopRequestId, 1L));

    assertFalse(result.replayed());
    assertEquals(2L, result.thread().version());
    assertEquals(ThreadLifecycleStatus.IDLE, result.thread().status());

    ThreadState childState = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    assertEquals(2L, childState.version());
    assertEquals(ThreadLifecycleStatus.IDLE, childState.status());

    ThreadState grandChildState =
        store.transaction(tx -> tx.findThread(grandChildId).orElseThrow());
    assertEquals(2L, grandChildState.version());
    assertEquals(ThreadLifecycleStatus.IDLE, grandChildState.status());

    // 凭据匹配且父级 STOP 边界冻结交付
    ThreadJoin grandChildJoin =
        store.transaction(tx -> tx.findJoin(grandChildJoinId).orElseThrow());
    assertTrue(grandChildJoin.matched());
    assertEquals(2L, grandChildJoin.matchedIdleVersion());
    assertEquals(grandChildState.headEntryId(), grandChildJoin.resultHeadEntryId());
    assertNull(grandChildJoin.deliveryCommandSequence());

    ThreadJoin childJoin = store.transaction(tx -> tx.findJoin(childJoinId).orElseThrow());
    assertTrue(childJoin.matched());
    assertEquals(2L, childJoin.matchedIdleVersion());
    assertEquals(childState.headEntryId(), childJoin.resultHeadEntryId());
    assertNull(childJoin.deliveryCommandSequence());

    ThreadJoin rootTicket = store.transaction(tx -> tx.findJoin(rootTicketId).orElseThrow());
    assertTrue(rootTicket.matched());
    assertEquals(2L, rootTicket.matchedIdleVersion());
    assertEquals(result.thread().headEntryId(), rootTicket.resultHeadEntryId());
    assertNull(rootTicket.deliveryCommandSequence());

    // 存储层 pending deliveries 包含相应 join
    List<ThreadJoin> childPending = store.transaction(tx -> tx.loadPendingDeliveries(childId));
    assertEquals(1, childPending.size());
    assertEquals(grandChildJoinId, childPending.get(0).invocationId());

    List<ThreadJoin> rootPending =
        store.transaction(tx -> tx.loadPendingDeliveries(root.threadId()));
    assertEquals(1, rootPending.size());
    assertEquals(childJoinId, rootPending.get(0).invocationId());
  }

  @Test
  void stopBeforeSourceExecutedProjectsCancelledOutcome() {
    // 测试意图：验证子线程在委派源命令尚未被模型消费（appliedTurnStartEntryId 为空）时被 Stop，
    // Join 被匹配后，其凭据投影为 CANCELLED 结果且错误信息说明在执行前被取消。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, childId, 1L, userMessagePayload("unexecuted-task"), TestIds.id(20));

    store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  root.threadId(),
                  childId,
                  1L,
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          return null;
        });

    UUID stopRequestId = TestIds.id(12);
    runtime.stop(new StopCommand(root.threadId(), stopRequestId, 0));

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());

    ThreadJoinReceipt receipt =
        store.transaction(tx -> ThreadJoinProjector.INSTANCE.project(tx, join).orElseThrow());
    assertEquals(ThreadJoinOutcome.CANCELLED, receipt.outcome());
    assertEquals("Cancelled before execution.", receipt.error());
    assertNull(receipt.report());
    assertNull(receipt.partialResult());
  }

  @Test
  void stoppingIntermediateChildDeliversNormallyToActiveParent() {
    // 测试意图：验证停止中间层 Child 时，若其父线程处于活跃等待（非 STOPPED），
    // 则在 Stop 事务内 Child 变为 IDLE、匹配 Join 并通过 CUSTOM_MESSAGE 正常交付给父线程，唤醒父线程 Work。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());

    store.transaction(
        tx -> {
          ThreadState r = tx.lockThread(root.threadId()).orElseThrow();
          tx.updateThread(r.changeLifecycleStatus(ThreadLifecycleStatus.WAITING_CHILDREN, T0));
          return null;
        });

    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, childId, 1L, userMessagePayload("child-task"), TestIds.id(21));

    store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  root.threadId(),
                  childId,
                  1L,
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          return null;
        });

    UUID stopRequestId = TestIds.id(13);
    StopResult result = runtime.stop(new StopCommand(childId, stopRequestId, 0));

    assertFalse(result.replayed());
    assertEquals(1L, result.thread().version());
    assertEquals(ThreadLifecycleStatus.IDLE, result.thread().status());

    // Join 被匹配且已成功交付（包含 deliveryCommandSequence）
    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.deliveryCommandSequence());

    // 父线程收到 CUSTOM_MESSAGE 交付命令，且 Work 被唤醒
    List<ThreadCommand> parentCmds =
        store.transaction(
            tx -> {
              tx.lockThread(root.threadId());
              return tx.loadQueuedCommands(root.threadId());
            });
    assertEquals(1, parentCmds.size());
    assertEquals(ThreadCommandType.CUSTOM_MESSAGE, parentCmds.get(0).type());
    assertTrue(hasWork(root.threadId(), WorkTargetType.THREAD));

    ThreadState rootState = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    assertEquals(2L, rootState.version());
  }

  @Test
  void parentStoppedBarrierHoldsDeliveryUntilRealInput() {
    // 测试意图：验证父线程先前已被 Stop，子线程后续被停止时，Join 结果匹配被保留但交付被父级 STOP 屏障冻结，
    // 直到父线程接受真实输入产生新轮次前不唤醒父线程。
    Baseline root = seedBaseline(store);

    // 1. 先对 Root 执行 Stop，使 Root 处于 STOPPED 边界
    runtime.stop(new StopCommand(root.threadId(), TestIds.id(14), 0));

    // 2. 在已被停止的 Root 下创建子线程与未决 Join
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, childId, 1L, userMessagePayload("child-task"), TestIds.id(22));
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  root.threadId(),
                  childId,
                  1L,
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          return null;
        });

    // 3. 对 Child 执行 Stop
    runtime.stop(new StopCommand(childId, TestIds.id(15), 0));

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNull(join.deliveryCommandSequence());

    // 父级未收到新命令，父级 Work 未被唤醒
    List<ThreadCommand> parentCmds =
        store.transaction(
            tx -> {
              tx.lockThread(root.threadId());
              return tx.loadQueuedCommands(root.threadId());
            });
    assertTrue(parentCmds.isEmpty());
    assertFalse(hasWork(root.threadId(), WorkTargetType.THREAD));
  }

  @Test
  void recursiveStopCancelsMixedModelAndToolExecutionsExactlyOnceWithIsolation() {
    // 测试意图：验证跨层递归 Stop 同时收集各层活跃的 Model 与 Tool 执行——目标层有 live Tool 兄弟、子层有 live
    // Model 时，两类本地取消各自恰好收到自己的执行 id（不互相吞并、不重复下发）；
    // 且某一类本地取消抛异常只记日志，不影响另一类的取消与已提交的 durable Stop。
    MultiToolBaseline toolRoot = seedToolBaseline(store, 2);
    UUID rootId = toolRoot.threadId();
    UUID childId = createChildThread(store, rootId, toolRoot.sessionId(), toolRoot.rootEntryId());
    UUID childModelId =
        seedChildRunningModel(store, childId, toolRoot.sessionId(), toolRoot.rootEntryId());
    seedThreadWork(store, rootId);
    seedThreadWork(store, childId);
    seedModelWork(store, childModelId);
    for (UUID toolId : toolRoot.toolIds()) {
      seedToolWork(store, toolId);
    }

    List<UUID> modelCalls = new ArrayList<>();
    List<UUID> toolCalls = new ArrayList<>();
    HarnessRuntime runtime =
        runtime(
            store,
            Clock.fixed(T5, ZoneOffset.UTC),
            invocationId -> {
              modelCalls.add(invocationId);
              throw new IllegalStateException("model cancellation failed");
            },
            toolCalls::add);

    long version = store.transaction(tx -> tx.findThread(rootId).orElseThrow().version());
    StopResult result = runtime.stop(new StopCommand(rootId, TestIds.id(30), version));

    assertStopped(result);
    // 两类执行的取消都在同一棵树的递归 Stop 中被收集，且每次恰好一次
    assertEquals(List.of(childModelId), modelCalls);
    assertEquals(toolRoot.toolIds(), toolCalls);
    // Model 取消失败不影响已提交的 Stop：子层 Model 行与目标层 Tool 行都已删除
    assertTrue(store.transaction(tx -> tx.findModelInvocation(childModelId)).isEmpty());
    for (UUID toolId : toolRoot.toolIds()) {
      assertTrue(store.transaction(tx -> tx.findToolInvocation(toolId)).isEmpty());
    }
  }

  @Test
  void stoppingJoinlessDeepDescendantConvergesEveryAncestorToIdleImmediately() {
    // 测试意图：验证无 join 的三层执行树（Root -> Child -> Grandchild）在停止最深后代时，
    // 被停止的 Grandchild 与仍处于 WAITING_CHILDREN 的 Child、Root 在同一个 Stop 事务内立即收敛为 IDLE，
    // 祖先仅更新状态与通知版本，不追加 STOP 历史。
    Baseline root = seedBaseline(store);
    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChildId = createChildThread(store, childId, root.sessionId(), root.rootEntryId());
    seedActiveHierarchy(root.threadId(), childId);

    ThreadState rootBefore = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    UUID rootHeadBefore = rootBefore.headEntryId();
    long rootVersionBefore = rootBefore.version();

    StopResult result = runtime.stop(new StopCommand(grandChildId, TestIds.id(31), 0));

    assertFalse(result.replayed());
    assertStoppedTurnEnd(grandChildId, TestIds.id(31));
    for (UUID threadId : List.of(grandChildId, childId, root.threadId())) {
      ThreadState state = store.transaction(tx -> tx.findThread(threadId).orElseThrow());
      assertEquals(ThreadLifecycleStatus.IDLE, state.status(), "thread " + threadId);
    }
    // 祖先 head 不变，状态改变必须推进通知版本。
    ThreadState rootAfter = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    assertEquals(rootHeadBefore, rootAfter.headEntryId());
    assertEquals(rootVersionBefore + 1, rootAfter.version());
  }

  @Test
  void stoppedParentWithQueuedUserInputAcceptsDeliveryInsteadOfFreezing() {
    // 测试意图：验证“暂停交付”只适用于 STOPPED head 且没有任何排队真实用户输入的父线程：
    // 父线程在 Stop 之后重新排入真实用户输入，就不再是暂停态，子线程停止时结果应立即交付并写入父 Thread 命令队列。
    Baseline root = seedBaseline(store);
    runtime.stop(new StopCommand(root.threadId(), TestIds.id(32), 0));

    // 父线程恢复：Stop 之后排入真实用户消息（尚未开始新的 turn）
    ThreadState stopped = runtime.getThreadSnapshot(root.threadId()).thread();
    var resume = userMessagePayload("resume-after-stop");
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                stopped.id(), stopped.headEntryId(), stopped.nextCommandSequence()),
            List.of(
                new NewThreadCommand(
                    resume, TestIds.id(33), ThreadCommandPayloadJsonCodec.requestHash(resume)))),
        AcceptancePreflight.IDENTITY);

    UUID childId = createChildThread(store, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, childId, 1L, userMessagePayload("child-task"), TestIds.id(34));
    seedJoin(store, joinId, root.threadId(), childId);

    runtime.stop(new StopCommand(childId, TestIds.id(35), 0));

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.deliveryCommandSequence());

    List<ThreadCommand> parentCommands =
        store.transaction(
            tx -> {
              tx.lockThread(root.threadId());
              return tx.loadQueuedCommands(root.threadId());
            });
    assertEquals(2, parentCommands.size());
    assertEquals(ThreadCommandType.USER_MESSAGE, parentCommands.get(0).type());
    assertEquals(ThreadCommandType.CUSTOM_MESSAGE, parentCommands.get(1).type());
    assertEquals(join.deliveryCommandSequence(), parentCommands.get(1).sequence());
    assertTrue(hasWork(root.threadId(), WorkTargetType.THREAD));
  }

  /** 把 Root / Child 置为非空闲状态，模拟真实递归活跃层级。 */
  private void seedActiveHierarchy(UUID rootThreadId, UUID childThreadId) {
    store.transaction(
        tx -> {
          List<UUID> ordered =
              List.of(rootThreadId, childThreadId).stream().sorted(UuidOrder.COMPARATOR).toList();
          for (UUID threadId : ordered) {
            tx.lockThread(threadId);
          }
          for (UUID threadId : ordered) {
            ThreadState thread = tx.lockThread(threadId).orElseThrow();
            tx.updateThread(
                thread.changeLifecycleStatus(ThreadLifecycleStatus.WAITING_CHILDREN, T0));
          }
          return null;
        });
  }

  /** 为已存在的子线程写入一条未匹配 join。 */
  private void seedJoin(
      InMemoryHarnessStore targetStore, UUID joinId, UUID parentId, UUID childId) {
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0));
          return null;
        });
  }

  private UUID seedChildRunningModel(
      InMemoryHarnessStore targetStore, UUID childThreadId, UUID sessionId, UUID headEntryId) {
    return targetStore.transaction(
        tx -> {
          UUID turnStartEntryId = tx.nextId();
          tx.insertEntry(
              turnStartEntry(turnStartEntryId, sessionId, headEntryId, T1, childThreadId));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, T1));
          ThreadState child = tx.lockThread(childThreadId).orElseThrow();
          tx.updateThread(child.advanceHead(userEntryId, T1));
          UUID modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, childThreadId, turnStartEntryId, userEntryId, T1);
          tx.insertModelInvocation(model);
          ModelInvocation dispatching = model.beginDispatch(T2);
          tx.updateModelInvocation(dispatching);
          ModelInvocation running = dispatching.markRunning(T2);
          tx.updateModelInvocation(running);
          return modelId;
        });
  }

  private boolean hasWork(UUID targetId, WorkTargetType type) {
    return store.transaction(tx -> tx.findWork(new WorkTarget(type, targetId)).isPresent());
  }

  private TurnEndPayload assertStoppedTurnEnd(UUID threadId, UUID expectedStopRequestId) {
    ThreadState thread = store.transaction(tx -> tx.findThread(threadId).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    Entry head = path.head();
    assertInstanceOf(TurnEndPayload.class, head.payload());
    TurnEndPayload end = (TurnEndPayload) head.payload();
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertEquals(expectedStopRequestId, end.closeRequestId());
    return end;
  }

  private static UUID createChildThread(
      InMemoryHarnessStore store, UUID parentThreadId, UUID sessionId, UUID headEntryId) {
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
                  false,
                  ThreadLifecycleStatus.IDLE,
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
                                  (txProxy, txMethod, txArgs) -> {
                                    String name = txMethod.getName();
                                    if (name.startsWith("lock") || name.startsWith("deleteWork")) {
                                      lockCalls.add(
                                          name
                                              + (txArgs != null && txArgs.length > 0
                                                  ? ":" + txArgs[0]
                                                  : ""));
                                    }
                                    return txMethod.invoke(tx, txArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }
}
