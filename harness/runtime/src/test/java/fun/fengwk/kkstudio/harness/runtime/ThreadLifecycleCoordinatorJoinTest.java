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

import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/** Join 结算的源输入前提与交付幂等：源输入应用前旧结果不得结算新 Join，交付后不重投。 */
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
    terminalEntryId =
        store.transaction(
            tx -> {
              UUID turnStart = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(turnStart, root.sessionId(), root.rootEntryId(), T1, childId));
              UUID userEntryId = tx.nextId();
              tx.insertEntry(userMessageEntry(userEntryId, root.sessionId(), turnStart, T1));
              UUID assistantEntryId = tx.nextId();
              tx.insertEntry(assistantEntry(assistantEntryId, root.sessionId(), userEntryId, T1));
              UUID turnEnd = tx.nextId();
              tx.insertEntry(
                  turnEndEntry(turnEnd, root.sessionId(), assistantEntryId, T1, turnStart, false));
              turnStartEntryId = turnStart;
              tx.insertThread(
                  new ThreadState(
                      childId,
                      root.sessionId(),
                      root.threadId(),
                      turnEnd,
                      CREATION_REQUEST_HASH,
                      "child",
                      false,
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
                          childId,
                          1L,
                          payload,
                          TestIds.id(51),
                          ThreadCommandPayloadJsonCodec.requestHash(payload),
                          null,
                          null,
                          null,
                          T1)));
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
              return turnEnd;
            });
  }

  /** J1：source 命令尚未 APPLIED 时，已提交结果不得结算该 Join、不得交付父通知。 */
  @Test
  void terminalDoesNotSettleJoinWithUnconsumedSource() {
    matchTerminal(false);

    assertFalse(store.transaction(tx -> tx.findJoin(joinId).orElseThrow()).matched());
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J2：source 消费后正常结算并交付一次；再次结算不得重投通知。 */
  @Test
  void settledJoinIsDeliveredExactlyOnce() {
    markSourceApplied();

    matchTerminal(false);
    ThreadJoin matched = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(matched.matched());
    assertNotNull(matched.deliveryCommandSequence());
    assertEquals(1, rootCommandCount());
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, root.threadId())).isPresent()));

    // 新结算事件（新对话结果）不重投：命令与 Join 交付均保持不变。
    ThreadJoin before = matched;
    matchTerminal(false);
    ThreadJoin after = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertEquals(before.deliveryCommandSequence(), after.deliveryCommandSequence());
    assertEquals(before.terminalEntryId(), after.terminalEntryId());
    assertEquals(1, rootCommandCount());
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

  private int rootCommandCount() {
    return store.<Integer>transaction(tx -> tx.loadCommandsByThread(root.threadId()).size());
  }

  private void assertNullWork() {
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, root.threadId())).isEmpty()));
  }
}
