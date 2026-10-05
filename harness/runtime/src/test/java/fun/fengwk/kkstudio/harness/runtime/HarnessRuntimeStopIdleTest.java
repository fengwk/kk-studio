package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.targetReceipt;
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

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 空闲 Stop 的持久契约：显式 Stop 绝不 no-op。
 *
 * <p>父 Thread 等异步子委派时本地 turn 已结束，此时 Stop 若不留 durable 事实就只是一个空动作，后台扫描器随后会照常注入子结果并唤醒父。 因此每次 idle Stop
 * 都必须在同一事务里写一个完整的 STOP barrier Turn：TURN_START({@code STOP}) → ASSISTANT_ERROR(CANCELLED) →
 * TURN_END(STOPPED, closeRequestId)，把 head 推进到该 TURN_END，并且只递增 一次 version；queued Command 在同一 now
 * 被取消；只有 Work 行时也照样留下停止边界；任何 deleteWork 失败让整个事务回滚 （Entry、Command、version 都不落盘）。
 */
class HarnessRuntimeStopIdleTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = runtime(store, Clock.fixed(T3, ZoneOffset.UTC));
  }

  @Test
  void idleStopPersistsStopBarrierTurnAndBumpsVersionOnce() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertFalse(result.replayed());
    assertEquals(0, targetReceipt(result).cancelledCommandCount());
    assertEquals(1L, result.thread().version());
    UUID turnEndId =
        assertStopBarrierTurn(pathOf(baseline.threadId()), baseline.threadId(), TestIds.id(1));
    assertEquals(turnEndId, targetReceipt(result).stoppedTurnEndEntryId());
    assertEquals(turnEndId, result.thread().headEntryId());

    // 停止边界不是模型工作：没有排队命令、没有 Model/Tool 执行。
    var snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertTrue(snapshot.queuedCommands().isEmpty());
    assertFalse(snapshot.model() != null);
    assertTrue(snapshot.toolSiblings().isEmpty());
  }

  @Test
  void idleWithQueuedCommandsCancelsAllAtOneNowAndBumpsVersionExactlyOnce() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("a"), TestIds.id(1));
    seedQueuedCommand(store, baseline.threadId(), 2L, userMessagePayload("b"), TestIds.id(2));
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertFalse(result.replayed());
    assertEquals(2, targetReceipt(result).cancelledCommandCount());
    // 取消 Command 与写入停止边界在同一个事务里共用一次 version 递增（绝不两次）。
    assertEquals(1L, result.thread().version());
    assertEquals(1L, result.thread().nextCommandSequence());
    assertEquals(
        targetReceipt(result).stoppedTurnEndEntryId(),
        assertStopBarrierTurn(pathOf(baseline.threadId()), baseline.threadId(), TestIds.id(1)));
    List<ThreadCommand> commands =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId());
            });
    assertTrue(commands.isEmpty());
    for (long id : List.of(1L, 2L)) {
      ThreadCommand command =
          store.transaction(
              tx ->
                  tx.findCommandByIdempotencyKey(
                          baseline.threadId(), id == 1 ? TestIds.id(1) : TestIds.id(2))
                      .orElseThrow());
      assertEquals(ThreadCommandState.CANCELLED, command.state());
      assertEquals(T3, command.cancelledAt());
    }
  }

  @Test
  void idleWorkOnlyStopStillPersistsStopBarrierAndDeletesWork() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedThreadWork(store, baseline.threadId());
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertFalse(result.replayed());
    assertEquals(0, targetReceipt(result).cancelledCommandCount());
    assertEquals(1L, result.thread().version());
    assertEquals(
        targetReceipt(result).stoppedTurnEndEntryId(),
        assertStopBarrierTurn(pathOf(baseline.threadId()), baseline.threadId(), TestIds.id(1)));
    assertFalse(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
  }

  @Test
  void idleWithCommandsAndWorkBumpsVersionAndDeletesTheWorkRow() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("a"), TestIds.id(1));
    seedThreadWork(store, baseline.threadId());
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0));
    assertEquals(1, targetReceipt(result).cancelledCommandCount());
    assertEquals(1L, result.thread().version());
    assertEquals(
        targetReceipt(result).stoppedTurnEndEntryId(),
        assertStopBarrierTurn(pathOf(baseline.threadId()), baseline.threadId(), TestIds.id(1)));
    assertFalse(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1)).orElseThrow());
    assertEquals(ThreadCommandState.CANCELLED, command.state());
  }

  /** deleteWork 是 final mutation：失败时整个事务回滚，停止边界 Entry、Command 取消与 version bump 都不落盘。 */
  @Test
  void deleteFailureRollsBackCommandsVersionEntriesAndWorkDeletion() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    seedQueuedCommand(store, baseline.threadId(), 1L, userMessagePayload("a"), TestIds.id(1));
    seedThreadWork(store, baseline.threadId());
    HarnessRuntime failingRuntime =
        runtime(storeFailingDeleteWork(store), Clock.fixed(T3, ZoneOffset.UTC));
    assertThrows(
        IllegalStateException.class,
        () -> failingRuntime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 0)));
    ThreadState thread = store.transaction(tx -> tx.lockThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, thread.version());
    assertEquals(baseline.rootEntryId(), thread.headEntryId());
    // 停止边界 Entry 与 version 一起回滚：Session 里只剩 ROOT。
    EntryPath path = pathOf(baseline.threadId());
    assertEquals(1, path.entries().size());
    ThreadCommand command =
        store.transaction(
            tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1)).orElseThrow());
    assertEquals(ThreadCommandState.QUEUED, command.state());
    assertTrue(
        store
            .transaction(
                tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())))
            .isPresent());
  }

  /**
   * open Turn 属于本 Thread 但没有 live Invocation（共享历史/已收尾 Model）：停止必须在该 Turn 内部收尾（取消屏障 + STOPPED
   * TURN_END），绝不嵌套第二个 TURN_START。
   */
  @Test
  void ownOpenTurnWithoutLiveExecutionIsClosedInsideItself() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID turnStartId =
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
              UUID turnStart = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.turnStartEntry(
                      turnStart,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      T3,
                      baseline.threadId()));
              UUID userId = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.userMessageEntry(
                      userId, baseline.sessionId(), turnStart, T3));
              tx.updateThread(thread.advanceHead(userId, T3));
              return turnStart;
            });
    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    assertFalse(result.replayed());
    // 打开 turn 已让 version 前进一次，停止边界再前进一次（一次 Stop 只加一次）。
    assertEquals(2L, result.thread().version());
    // ROOT + TURN_START(INPUT) + USER + 取消屏障 + STOPPED TURN_END。
    EntryPath path = pathOf(baseline.threadId());
    assertEquals(5, path.entries().size());
    // 同一个 TURN_START 被自己的 STOPPED TURN_END 关闭：没有第二个 TURN_START。
    assertEquals(turnStartId, path.entries().get(1).id());
    Entry turnEnd = path.head();
    TurnEndPayload end = assertInstanceOf(TurnEndPayload.class, turnEnd.payload());
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(turnStartId, end.turnStartEntryId());
    assertEquals(TestIds.id(1), end.closeRequestId());
    assertEquals(turnEnd.id(), targetReceipt(result).stoppedTurnEndEntryId());
  }

  /**
   * open Turn 内已有完整 assistant 结果（无 tool call）：停止直接复用它并按 STOPPED 关闭，绝不追加第二个 assistant 结果 （validator
   * 会拒绝 "assistant result must not repeat"）。
   */
  @Test
  void ownOpenTurnWithCompleteAssistantResultIsClosedByReusingIt() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID turnStartId =
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
              UUID turnStart = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.turnStartEntry(
                      turnStart,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      T3,
                      baseline.threadId()));
              UUID userId = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.userMessageEntry(
                      userId, baseline.sessionId(), turnStart, T3));
              UUID assistantId = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.assistantEntry(
                      assistantId, baseline.sessionId(), userId, T3));
              tx.updateThread(thread.advanceHead(assistantId, T3));
              return turnStart;
            });

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    assertFalse(result.replayed());
    assertEquals(2L, result.thread().version());
    EntryPath path = pathOf(baseline.threadId());
    // ROOT + TURN_START(INPUT) + USER + ASSISTANT + STOPPED TURN_END：没有取消屏障。
    assertEquals(5, path.entries().size());
    assertEquals(
        1,
        path.entries().stream()
            .filter(entry -> entry.payload() instanceof MessagePayload)
            .filter(
                entry ->
                    ((MessagePayload) entry.payload()).message().role()
                        == AgentMessageRole.ASSISTANT)
            .count());
    TurnEndPayload end = assertInstanceOf(TurnEndPayload.class, path.head().payload());
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(turnStartId, end.turnStartEntryId());
    assertEquals(TestIds.id(1), end.closeRequestId());
    assertEquals(path.head().id(), targetReceipt(result).stoppedTurnEndEntryId());
  }

  /**
   * open Turn 是还没有任何输入的 INPUT Turn：不足以按 STOPPED 关闭（需要输入），停止按既有 history cut 语义以
   * CANCELLED/HISTORY_CUT 收尾并照常写停止边界——既不报模糊错误，也不伪造模型完成。
   */
  @Test
  void ownOpenInputTurnWithoutInputIsClosedAsHistoryCutBeforeTheStopBoundary() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID turnStartId =
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
              UUID turnStart = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.turnStartEntry(
                      turnStart,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      T3,
                      baseline.threadId()));
              tx.updateThread(thread.advanceHead(turnStart, T3));
              return turnStart;
            });

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    assertFalse(result.replayed());
    assertEquals(2L, result.thread().version());
    EntryPath path = pathOf(baseline.threadId());
    // ROOT + TURN_START(INPUT) + CANCELLED/HISTORY_CUT TURN_END + STOP barrier Turn 的三条 Entry。
    assertEquals(6, path.entries().size());
    TurnEndPayload cut = assertInstanceOf(TurnEndPayload.class, path.entries().get(2).payload());
    assertEquals(TurnEndOutcome.CANCELLED, cut.outcome());
    assertEquals(TurnEndReason.HISTORY_CUT, cut.reason());
    assertEquals(turnStartId, cut.turnStartEntryId());
    assertNull(cut.closeRequestId());
    assertEquals(
        targetReceipt(result).stoppedTurnEndEntryId(),
        assertStopBarrierTurn(path, baseline.threadId(), TestIds.id(1)));
    // 停止边界是路径末尾的 STOP turn：历史 cut 的 TURN_END 不是停止边界。
    assertEquals(
        TurnStartReason.STOP, ((TurnStartPayload) path.entries().get(3).payload()).reason());
  }

  /**
   * open Turn 的 assistant 结果有未被回答的 tool call：既不能宣称 STOPPED（tool 结果缺失），也不能补造 assistant 完成；停止按既有
   * history cut 语义补写 synthetic UNKNOWN/HISTORY_CUT ToolResult 后以 CANCELLED 收尾，再写停止边界。
   */
  @Test
  void ownOpenTurnWithUnansweredToolCallIsClosedAsHistoryCutWithoutFakingCompletion() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID turnStartId =
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
              UUID turnStart = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.turnStartEntry(
                      turnStart,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      T3,
                      baseline.threadId()));
              UUID userId = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.userMessageEntry(
                      userId, baseline.sessionId(), turnStart, T3));
              UUID assistantId = tx.nextId();
              tx.insertEntry(
                  HarnessRuntimeTestSupport.assistantEntry(
                      assistantId, baseline.sessionId(), userId, T3, "call-1"));
              tx.updateThread(thread.advanceHead(assistantId, T3));
              return turnStart;
            });

    StopResult result = runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(1), 1));

    assertFalse(result.replayed());
    assertEquals(2L, result.thread().version());
    EntryPath path = pathOf(baseline.threadId());
    // ROOT + TURN_START + USER + ASSISTANT + synthetic TOOL + CANCELLED TURN_END + STOP barrier
    // Turn 的三条 Entry。
    assertEquals(9, path.entries().size());
    MessagePayload toolResult =
        assertInstanceOf(MessagePayload.class, path.entries().get(4).payload());
    assertEquals(AgentMessageRole.TOOL, toolResult.message().role());
    assertEquals(true, toolResult.toolResultMetadata().synthetic());
    assertEquals("UNKNOWN", toolResult.toolResultMetadata().status().name());
    assertEquals("HISTORY_CUT", toolResult.toolResultMetadata().reason().name());
    TurnEndPayload cut = assertInstanceOf(TurnEndPayload.class, path.entries().get(5).payload());
    assertEquals(TurnEndOutcome.CANCELLED, cut.outcome());
    assertEquals(TurnEndReason.HISTORY_CUT, cut.reason());
    assertEquals(turnStartId, cut.turnStartEntryId());
    // 只有一个 assistant 结果：停止没有追加取消屏障，也没有伪造第二个 assistant。
    assertEquals(
        1,
        path.entries().stream()
            .filter(entry -> entry.payload() instanceof MessagePayload)
            .filter(
                entry ->
                    ((MessagePayload) entry.payload()).message().role()
                        == AgentMessageRole.ASSISTANT)
            .count());
    assertEquals(
        targetReceipt(result).stoppedTurnEndEntryId(),
        assertStopBarrierTurn(path, baseline.threadId(), TestIds.id(1)));
  }

  /**
   * open Turn 属于其它 Thread 的共享历史：本 Thread 既没有自己的 live 执行，也无权关闭别人的 Turn，因此只能保留"仅取消
   * Command"的尽力语义（已知限制，不写停止边界）。
   */
  @Test
  void foreignOpenTurnStopOnlyCancelsCommandsWithoutWritingAMarker() {
    HarnessRuntimeTestSupport.TurnBaseline owner = HarnessRuntimeTestSupport.seedOpenTurn(store);
    UUID sibling = HarnessRuntimeTestSupport.seedThreadAt(store, owner.turnStartEntryId());
    seedQueuedCommand(store, sibling, 1L, userMessagePayload("hi"), TestIds.id(2));

    StopResult result = runtime.stop(new StopCommand(sibling, TestIds.id(1), 0));

    assertFalse(result.replayed());
    assertEquals(1, targetReceipt(result).cancelledCommandCount());
    assertEquals(1L, result.thread().version());
    assertNull(targetReceipt(result).stoppedTurnEndEntryId());
    assertEquals(owner.turnStartEntryId(), result.thread().headEntryId());
    EntryPath ownerPath = store.transaction(tx -> tx.loadEntryPath(owner.turnStartEntryId()));
    assertEquals(2, ownerPath.entries().size());
  }

  /** 断言路径末尾是一个完整的 STOP barrier Turn，并返回它的 TURN_END id。 */
  private UUID assertStopBarrierTurn(EntryPath path, UUID ownerThreadId, UUID stopRequestId) {
    List<Entry> entries = path.entries();
    Entry turnEnd = path.head();
    TurnEndPayload end = assertInstanceOf(TurnEndPayload.class, turnEnd.payload());
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertFalse(end.continueModel());
    assertEquals(stopRequestId, end.closeRequestId());

    Entry barrier = entries.get(entries.size() - 2);
    assertEquals(turnEnd.id(), entries.get(entries.size() - 1).id());
    assertEquals(barrier.id(), turnEnd.parentEntryId());
    AssistantErrorPayload error = assertInstanceOf(AssistantErrorPayload.class, barrier.payload());
    assertEquals("CANCELLED", error.error().code());

    Entry turnStart = entries.get(entries.size() - 3);
    TurnStartPayload start = assertInstanceOf(TurnStartPayload.class, turnStart.payload());
    assertEquals(TurnStartReason.STOP, start.reason());
    assertEquals(ownerThreadId, start.ownerThreadId());
    assertEquals(turnStart.id(), end.turnStartEntryId());
    assertEquals(turnStart.id(), barrier.parentEntryId());
    assertNotNull(start.settings());
    return turnEnd.id();
  }

  private EntryPath pathOf(UUID threadId) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          return tx.loadEntryPath(thread.headEntryId());
        });
  }

  /** 仅测试用的委托 store：其事务使每次 deleteWork 调用都失败。 */
  private static HarnessStore storeFailingDeleteWork(InMemoryHarnessStore delegate) {
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
                                    if (transactionMethod.getName().equals("deleteWork")) {
                                      throw new IllegalStateException("fenced delete failure");
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }
}
