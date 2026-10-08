package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnEndEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/**
 * 终止边界 Join 结算的收敛判据与交付幂等：源输入应用前旧结果不得结算新 Join；普通终止只在没有未完成的直接子 Join、没有未送达子回执、 没有待处理输入时结算；Stop
 * 强制结算不受此限制；交付后不重投。
 */
class ThreadLifecycleCoordinatorJoinTest {

  private InMemoryHarnessStore store;
  private HarnessRuntimeTestSupport.Baseline root;
  private UUID childId;
  private UUID turnStartEntryId;
  private UUID terminalEntryId;
  private UUID joinId;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    root = seedBaseline(store);
    childId = UUID.randomUUID();
    joinId = UUID.randomUUID();
    // 子 Thread B：源命令 seq 1 尚未应用；A->B 的 Join 尚未冻结结果。
    SeededChild child = seedQuiescentChild(childId, root.threadId(), 51L);
    turnStartEntryId = child.turnStartId();
    terminalEntryId = child.turnEndId();
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

  /** J1：source 命令尚未 APPLIED 时，已提交结果不得结算该 Join、不得交付父通知。 */
  @Test
  void terminalDoesNotSettleJoinWithUnconsumedSource() {
    matchTerminal(false);

    assertFalse(join(joinId).matched());
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J2：source 消费后正常结算并交付一次；再次结算不得重投通知。 */
  @Test
  void settledJoinIsDeliveredExactlyOnce() {
    markSourceApplied();

    matchTerminal(false);
    ThreadJoin matched = join(joinId);
    assertTrue(matched.matched());
    assertNotNull(matched.deliveryCommandSequence());
    assertEquals(1, rootCommandCount());
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, root.threadId())).isPresent()));

    // 新结算事件（新对话结果）不重投：命令与 Join 交付均保持不变。
    ThreadJoin before = matched;
    matchTerminal(false);
    ThreadJoin after = join(joinId);
    assertEquals(before.deliveryCommandSequence(), after.deliveryCommandSequence());
    assertEquals(before.terminalEntryId(), after.terminalEntryId());
    assertEquals(1, rootCommandCount());
  }

  /** J3：本 Thread 仍有 QUEUED 输入时不得结算上游 Join。 */
  @Test
  void queuedInputBlocksUpstreamJoinSettlement() {
    markSourceApplied();
    seedQueuedUserCommandAtSequenceTwo();

    matchTerminal(false);
    assertFalse(join(joinId).matched());
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J4：本 Thread 有尚未冻结结果的直接子 Join 时不得结算上游 Join。 */
  @Test
  void incompleteChildJoinBlocksUpstreamJoinSettlement() {
    markSourceApplied();
    UUID grandChildId = UUID.randomUUID();
    seedQuiescentChild(grandChildId, childId, 61L);
    insertUnmatchedJoin(UUID.randomUUID(), childId, grandChildId);

    matchTerminal(false);
    assertFalse(join(joinId).matched());
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(childId)));
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J5：直接子 Join 已冻结但尚未交付（pending delivery）时同样不得结算上游 Join。 */
  @Test
  void pendingChildDeliveryBlocksUpstreamJoinSettlement() {
    markSourceApplied();
    UUID grandChildId = UUID.randomUUID();
    UUID grandChildTerminal = seedQuiescentChild(grandChildId, childId, 71L).turnEndId();
    UUID grandChildJoinId = UUID.randomUUID();
    insertUnmatchedJoin(grandChildJoinId, childId, grandChildId);
    matchChildJoinWithoutDelivery(grandChildJoinId, grandChildId, grandChildTerminal);

    matchTerminal(false);
    assertFalse(join(joinId).matched());
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J6：已物化但尚未越过输入水位的 APPLIED 通知也构成待处理输入；水位推进（INPUT 接纳）后才结算，且恰一次。 */
  @Test
  void appliedNotificationBeyondWatermarkBlocksUntilConsumedThenSettlesExactlyOnce() {
    markSourceApplied();
    seedAppliedNotificationAtSequenceTwo();

    matchTerminal(false);
    assertFalse(join(joinId).matched());
    assertEquals(0, rootCommandCount());

    // 模拟 INPUT 接纳该通知：水位推进到 seq 2，后续结算恰一次。
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          ThreadState child = tx.findThread(childId).orElseThrow();
          tx.updateThread(child.advanceInputThroughSequence(2L, T1));
          return null;
        });
    matchTerminal(false);
    assertTrue(join(joinId).matched());
    assertEquals(1, rootCommandCount());
    ThreadJoin frozen = join(joinId);
    matchTerminal(false);
    assertEquals(frozen, join(joinId));
    assertEquals(1, rootCommandCount());
  }

  /** J7：Stop 强制结算不受收敛判据限制：有未完成直接子 Join 时仍结算上游 Join。 */
  @Test
  void stopForcedSettlementBypassesQuiescenceBlockers() {
    markSourceApplied();
    UUID grandChildId = UUID.randomUUID();
    seedQuiescentChild(grandChildId, childId, 81L);
    insertUnmatchedJoin(UUID.randomUUID(), childId, grandChildId);

    matchTerminal(true);

    ThreadJoin matched = join(joinId);
    assertTrue(matched.matched());
    assertNotNull(matched.deliveryCommandSequence());
    assertEquals(1, rootCommandCount());
  }

  /**
   * 种子一个已闭合终态的 Thread（ROOT -&gt; TURN_START(owner) -&gt; USER -&gt; ASSISTANT -&gt; TURN_END）与其
   * source 命令（seq 1，尚未应用）。
   */
  private SeededChild seedQuiescentChild(
      UUID threadId, UUID parentThreadId, Long idempotencyKeySeed) {
    return store.transaction(
        tx -> {
          UUID turnStart = tx.nextId();
          tx.insertEntry(
              turnStartEntry(turnStart, root.sessionId(), root.rootEntryId(), T1, threadId));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, root.sessionId(), turnStart, T1));
          UUID assistantEntryId = tx.nextId();
          tx.insertEntry(assistantEntry(assistantEntryId, root.sessionId(), userEntryId, T1));
          UUID turnEnd = tx.nextId();
          tx.insertEntry(
              turnEndEntry(turnEnd, root.sessionId(), assistantEntryId, T1, turnStart, false));
          tx.insertThread(
              new ThreadState(
                  threadId,
                  root.sessionId(),
                  parentThreadId,
                  turnEnd,
                  CREATION_REQUEST_HASH,
                  "thread-" + threadId,
                  ThreadYoloPolicy.follow(root.threadId()),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  2L,
                  0L,
                  T0,
                  T0));
          var payload = userMessagePayload("task");
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      threadId,
                      1L,
                      payload,
                      TestIds.id(idempotencyKeySeed),
                      ThreadCommandPayloadJsonCodec.requestHash(payload),
                      null,
                      null,
                      null,
                      T1)));
          return new SeededChild(turnStart, turnEnd);
        });
  }

  private record SeededChild(UUID turnStartId, UUID turnEndId) {}

  /** 在给定父 Thread 下插入一个尚未冻结结果的直接子 Join。 */
  private void insertUnmatchedJoin(UUID newJoinId, UUID parentThreadId, UUID childThreadId) {
    store.transaction(
        tx -> {
          tx.lockThread(childThreadId);
          tx.insertJoin(
              new ThreadJoin(
                  newJoinId,
                  CREATION_REQUEST_HASH,
                  parentThreadId,
                  childThreadId,
                  1L,
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

  /** 冻结子 Join 的结果但不交付（deliveryCommandSequence 保持 null），构造 pending delivery。 */
  private void matchChildJoinWithoutDelivery(
      UUID childJoinId, UUID childThreadId, UUID terminalEntryOfChild) {
    store.transaction(
        tx -> {
          tx.lockThread(childThreadId);
          ThreadJoin join = tx.findJoin(childJoinId).orElseThrow();
          tx.updateJoin(join.match(terminalEntryOfChild, null, T1));
          return null;
        });
  }

  /** 在 child 的 seq 2 插入一条 QUEUED 用户命令，构造“仍有待处理输入”的合法状态。 */
  private void seedQueuedUserCommandAtSequenceTwo() {
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          var payload = userMessagePayload("steering");
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      childId,
                      2L,
                      payload,
                      TestIds.id(52),
                      ThreadCommandPayloadJsonCodec.requestHash(payload),
                      null,
                      null,
                      null,
                      T1)));
          return null;
        });
  }

  /** 在 child 的 seq 2 物化一条 APPLIED NOTIFICATION（水位未推进），构造“已物化但未被 INPUT 接纳”。 */
  private void seedAppliedNotificationAtSequenceTwo() {
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          ThreadState child = tx.findThread(childId).orElseThrow();
          UUID notificationId = UUID.randomUUID();
          AgentMessage receipt = AgentMessage.user("receipt");
          NotificationCommandPayload payload =
              new NotificationCommandPayload(
                  notificationId, NotificationKind.SUBAGENT_RESULT, childId, receipt);
          ThreadCommand command =
              new ThreadCommand(
                  childId,
                  2L,
                  payload,
                  TestIds.id(53),
                  ThreadCommandPayloadJsonCodec.requestHash(payload),
                  null,
                  null,
                  null,
                  T1);
          tx.insertCommands(List.of(command));
          UUID entryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  entryId,
                  root.sessionId(),
                  child.headEntryId(),
                  new NotificationPayload(
                      notificationId, NotificationKind.SUBAGENT_RESULT, childId, receipt),
                  T1));
          tx.updateCommands(List.of(command.markApplied(entryId)));
          tx.updateThread(child.reserveCommandSequences(1, T1));
          return null;
        });
  }

  private void matchTerminal(boolean includeUnappliedSource) {
    store.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, childId);
          for (UUID threadId :
              List.of(childId, root.threadId()).stream().sorted(UuidOrder.COMPARATOR).toList()) {
            tx.lockThread(threadId);
          }
          ThreadState child = tx.findThread(childId).orElseThrow();
          ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
              tx, child, terminalEntryId, null, T1, includeUnappliedSource);
          return null;
        });
  }

  private void markSourceApplied() {
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.loadQueuedCommands(childId);
          ThreadCommand source = tx.findCommand(childId, 1L).orElseThrow();
          tx.updateCommands(List.of(source.markApplied(turnStartEntryId)));
          return null;
        });
  }

  private ThreadJoin join(UUID invocationId) {
    return store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
  }

  private int rootCommandCount() {
    return store.<Integer>transaction(tx -> tx.loadCommandsByThread(root.threadId()).size());
  }

  private void assertNullWork() {
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, root.threadId())).isEmpty()));
  }
}
