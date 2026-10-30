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
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.JoinStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 终止边界 Join 结算的收敛判据与交付幂等：源输入应用前旧结果不得结算新 Join；普通终止只在没有未完成的直接子 Join、没有待处理输入时结算；Stop
 * 只放宽未应用源输入，仍要求同一收敛事实；交付后不重投。
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
          tx.threads().lock(childId);
          tx.joins()
              .insert(
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
                      T0,
                      JoinDelivery.NOTIFY_PARENT,
                      null));
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
            tx ->
                tx.work()
                    .find(new WorkTarget(WorkTargetType.THREAD, root.threadId()))
                    .isPresent()));

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

  /** J3b：本 Thread 仅有 QUEUED SET_* 控制命令时，不创建模型义务也不阻塞上游 Join 结算。 */
  @Test
  void setOnlyQueuedCommandDoesNotBlockUpstreamJoinSettlement() {
    markSourceApplied();
    seedQueuedSetAgentCommandAtSequenceTwo();

    matchTerminal(false);
    ThreadJoin matched = join(joinId);
    assertTrue(matched.matched());
    assertNotNull(matched.deliveryCommandSequence());
    assertEquals(1, rootCommandCount());
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.work()
                    .find(new WorkTarget(WorkTargetType.THREAD, root.threadId()))
                    .isPresent()));
  }

  /** J3c：本 Thread 存在 QUEUED CUSTOM_MESSAGE 时，构成待处理输入需求，阻塞上游 Join 结算。 */
  @Test
  void queuedCustomMessageBlocksUpstreamJoinSettlement() {
    markSourceApplied();
    seedQueuedCustomMessageCommandAtSequenceTwo();

    matchTerminal(false);
    assertFalse(join(joinId).matched());
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J3d：本 Thread 存在 QUEUED NOTIFICATION 时，构成待处理输入需求，阻塞上游 Join 结算。 */
  @Test
  void queuedNotificationBlocksUpstreamJoinSettlement() {
    markSourceApplied();
    seedQueuedNotificationCommandAtSequenceTwo();

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
    assertEquals(1, store.<Integer>transaction(tx -> tx.joins().countIncompleteChildren(childId)));
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J5：直接子 Join 终止时单次原子事务完成结果冻结与父通知投递；未消费的通知作为输入需求阻止上游 Join 结算。 */
  @Test
  void grandChildDeliveryAtomicallyNotifiesChildAndBlocksUpstreamSettlementUntilConsumed() {
    markSourceApplied();
    UUID grandChildId = UUID.randomUUID();
    SeededChild grandChild = seedQuiescentChild(grandChildId, childId, 71L);
    UUID grandChildTerminal = grandChild.turnEndId();
    UUID grandChildJoinId = UUID.randomUUID();
    insertUnmatchedJoin(grandChildJoinId, childId, grandChildId);

    // 应用 grandChild 的源输入命令
    store.transaction(
        tx -> {
          tx.threads().lock(grandChildId);
          tx.commands().loadQueued(grandChildId);
          ThreadCommand source = tx.commands().find(grandChildId, 1L).orElseThrow();
          tx.commands().update(List.of(source.markApplied(grandChild.turnStartId())));
          return null;
        });

    // grandChild 终止，原子冻结结果并投递到 child 的 seq 2
    store.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, grandChildId);
          for (UUID threadId :
              List.of(grandChildId, childId).stream().sorted(UuidOrder.COMPARATOR).toList()) {
            tx.threads().lock(threadId);
          }
          ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
              tx,
              tx.threads().find(grandChildId).orElseThrow(),
              grandChildTerminal,
              null,
              T1,
              false);
          return null;
        });

    ThreadJoin deliveredGrandChild = join(grandChildJoinId);
    assertTrue(deliveredGrandChild.matched());
    assertEquals(2L, deliveredGrandChild.deliveryCommandSequence());

    // child 尚未消费该通知，上游 joinId 不得结算
    matchTerminal(false);
    assertFalse(join(joinId).matched());
    assertEquals(0, rootCommandCount());
    assertNullWork();
  }

  /** J5b：NOTIFY_PARENT 结算时仅调用一次 updateJoin，且传入的必为已分配父交付序列的终态 Join，绝不持久化中间 matched 态。 */
  @Test
  void notifyParentSettlementUpdatesJoinExactlyOnceWithDeliveredSequence() {
    markSourceApplied();
    List<ThreadJoin> updatedJoins = new ArrayList<>();
    store.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, childId);
          for (UUID threadId :
              List.of(childId, root.threadId()).stream().sorted(UuidOrder.COMPARATOR).toList()) {
            tx.threads().lock(threadId);
          }
          JoinStore joins =
              (JoinStore)
                  Proxy.newProxyInstance(
                      JoinStore.class.getClassLoader(),
                      new Class<?>[] {JoinStore.class},
                      (proxy, method, args) -> {
                        if (method.getName().equals("update")) {
                          updatedJoins.add((ThreadJoin) args[0]);
                        }
                        return method.invoke(tx.joins(), args);
                      });
          HarnessStore.Transaction wrapped =
              (HarnessStore.Transaction)
                  Proxy.newProxyInstance(
                      HarnessStore.Transaction.class.getClassLoader(),
                      new Class<?>[] {HarnessStore.Transaction.class},
                      (proxy, method, args) ->
                          method.getName().equals("joins") ? joins : method.invoke(tx, args));
          ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
              wrapped, tx.threads().find(childId).orElseThrow(), terminalEntryId, null, T1, false);
          return null;
        });

    assertEquals(
        1, updatedJoins.size(), "updateJoin must be called exactly once for NOTIFY_PARENT");
    ThreadJoin singleUpdate = updatedJoins.getFirst();
    assertTrue(singleUpdate.matched());
    assertEquals(1L, singleUpdate.deliveryCommandSequence());
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
          tx.threads().lock(childId);
          ThreadState child = tx.threads().find(childId).orElseThrow();
          tx.threads().update(child.advanceInputThroughSequence(2L, T1));
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

  /** J7：Stop 只放宽未应用源输入，不绕过收敛判据；存在未完成直接子 Join 时仍不结算上游 Join。 */
  @Test
  void stopSettlementStillRequiresQuiescence() {
    markSourceApplied();
    UUID grandChildId = UUID.randomUUID();
    seedQuiescentChild(grandChildId, childId, 81L);
    insertUnmatchedJoin(UUID.randomUUID(), childId, grandChildId);

    matchTerminal(true);

    ThreadJoin matched = join(joinId);
    assertFalse(matched.matched());
    assertNull(matched.deliveryCommandSequence());
    assertEquals(0, rootCommandCount());
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
          tx.entries()
              .insert(
                  turnStartEntry(turnStart, root.sessionId(), root.rootEntryId(), T1, threadId));
          UUID userEntryId = tx.nextId();
          tx.entries().insert(userMessageEntry(userEntryId, root.sessionId(), turnStart, T1));
          UUID assistantEntryId = tx.nextId();
          tx.entries().insert(assistantEntry(assistantEntryId, root.sessionId(), userEntryId, T1));
          UUID turnEnd = tx.nextId();
          tx.entries()
              .insert(
                  turnEndEntry(turnEnd, root.sessionId(), assistantEntryId, T1, turnStart, false));
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      root.sessionId(),
                      parentThreadId,
                      turnEnd,
                      CREATION_REQUEST_HASH,
                      "thread-" + threadId,
                      root.threadId(),
                      false,
                      SpawnTrigger.MODEL_TOOL,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      2L,
                      0L,
                      T0,
                      T0));
          var payload = userMessagePayload("task");
          tx.commands()
              .insert(
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
          tx.threads().lock(childThreadId);
          tx.joins()
              .insert(
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
                      T0,
                      JoinDelivery.NOTIFY_PARENT,
                      null));
          return null;
        });
  }

  /** 在 child 的 seq 2 插入一条 QUEUED 用户命令，构造“仍有待处理输入”的合法状态。 */
  private void seedQueuedUserCommandAtSequenceTwo() {
    store.transaction(
        tx -> {
          tx.threads().lock(childId);
          var payload = userMessagePayload("steering");
          tx.commands()
              .insert(
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

  /** 在 child 的 seq 2 插入一条 QUEUED SET_* 控制命令，构造仅有控制维护的合法状态。 */
  private void seedQueuedSetAgentCommandAtSequenceTwo() {
    store.transaction(
        tx -> {
          tx.threads().lock(childId);
          var payload = new SetAgentCommandPayload("assistant");
          tx.commands()
              .insert(
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

  /** 在 child 的 seq 2 插入一条 QUEUED CUSTOM_MESSAGE，构造待处理输入需求。 */
  private void seedQueuedCustomMessageCommandAtSequenceTwo() {
    store.transaction(
        tx -> {
          tx.threads().lock(childId);
          var payload = new CustomMessageCommandPayload(AgentMessage.user("custom-steering"));
          tx.commands()
              .insert(
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

  /** 在 child 的 seq 2 插入一条 QUEUED NOTIFICATION，构造待处理输入需求。 */
  private void seedQueuedNotificationCommandAtSequenceTwo() {
    store.transaction(
        tx -> {
          tx.threads().lock(childId);
          var payload =
              new NotificationCommandPayload(
                  UUID.randomUUID(),
                  NotificationKind.SUBAGENT_RESULT,
                  childId,
                  AgentMessage.user("queued-result"));
          tx.commands()
              .insert(
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
          tx.threads().lock(childId);
          ThreadState child = tx.threads().find(childId).orElseThrow();
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
          tx.commands().insert(List.of(command));
          UUID entryId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      entryId,
                      root.sessionId(),
                      child.headEntryId(),
                      new NotificationPayload(
                          notificationId, NotificationKind.SUBAGENT_RESULT, childId, receipt),
                      T1));
          tx.commands().update(List.of(command.markApplied(entryId)));
          tx.threads().update(child.reserveCommandSequences(1, T1));
          return null;
        });
  }

  private void matchTerminal(boolean includeUnappliedSource) {
    store.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, childId);
          for (UUID threadId :
              List.of(childId, root.threadId()).stream().sorted(UuidOrder.COMPARATOR).toList()) {
            tx.threads().lock(threadId);
          }
          ThreadState child = tx.threads().find(childId).orElseThrow();
          ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
              tx, child, terminalEntryId, null, T1, includeUnappliedSource);
          return null;
        });
  }

  private void markSourceApplied() {
    store.transaction(
        tx -> {
          tx.threads().lock(childId);
          tx.commands().loadQueued(childId);
          ThreadCommand source = tx.commands().find(childId, 1L).orElseThrow();
          tx.commands().update(List.of(source.markApplied(turnStartEntryId)));
          return null;
        });
  }

  private ThreadJoin join(UUID invocationId) {
    return store.transaction(tx -> tx.joins().find(invocationId).orElseThrow());
  }

  /**
   * COMPACTION 终态只冻结 receipt 并唤醒父 Thread Work：不投递 SUBAGENT_RESULT、不进入 task pending
   * delivery，未完成时也不占普通 task 配额。
   */
  @Test
  void compactionJoinFreezesReceiptAndWakesParentWithoutNotificationOrDelivery() {
    UUID compactionChildId = UUID.randomUUID();
    SeededChild compaction = seedQuiescentChild(compactionChildId, root.threadId(), 91L);
    UUID compactionJoinId = UUID.randomUUID();
    store.transaction(
        tx -> {
          tx.threads().lock(compactionChildId);
          tx.joins()
              .insert(
                  new ThreadJoin(
                      compactionJoinId,
                      CREATION_REQUEST_HASH,
                      root.threadId(),
                      compactionChildId,
                      1L,
                      "compactor",
                      10,
                      0L,
                      null,
                      null,
                      null,
                      T0,
                      T0,
                      JoinDelivery.SETTLE_OWNER_TURN,
                      null));
          return null;
        });
    // 未完成 COMPACTION join 不占普通 task 配额：计数仍只含 setUp 的 TASK join。
    assertEquals(
        1, store.<Integer>transaction(tx -> tx.joins().countIncompleteChildren(root.threadId())));

    // 应用 COMPACTION 子线程的源命令，使其可通过收敛判据。
    store.transaction(
        tx -> {
          tx.threads().lock(compactionChildId);
          tx.commands().loadQueued(compactionChildId);
          ThreadCommand source = tx.commands().find(compactionChildId, 1L).orElseThrow();
          tx.commands().update(List.of(source.markApplied(compaction.turnStartId())));
          return null;
        });

    store.transaction(
        tx -> {
          ThreadTreeLocks.lockForThread(tx, compactionChildId);
          for (UUID threadId :
              List.of(compactionChildId, root.threadId()).stream()
                  .sorted(UuidOrder.COMPARATOR)
                  .toList()) {
            tx.threads().lock(threadId);
          }
          ThreadState compactionChild = tx.threads().find(compactionChildId).orElseThrow();
          ThreadLifecycleCoordinator.matchAndDeliverTerminalJoins(
              tx, compactionChild, compaction.turnEndId(), null, T1, false);
          return null;
        });

    ThreadJoin matched = join(compactionJoinId);
    assertTrue(matched.matched());
    assertNull(matched.deliveryCommandSequence());
    // 不投递 SUBAGENT_RESULT：父 Thread 不新增任何 command。
    assertEquals(0, rootCommandCount());
    // 但唤醒父 Thread Work 供压缩 owner 消费。
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.work()
                    .find(new WorkTarget(WorkTargetType.THREAD, root.threadId()))
                    .isPresent()));
    // COMPACTION 冻结结果不属于 NOTIFY_PARENT 子任务，不占用子任务并发计数（仍只有 setUp 中的普通 task join）。
    assertEquals(
        1,
        store
            .<Integer>transaction(tx -> tx.joins().countIncompleteChildren(root.threadId()))
            .intValue());
  }

  private int rootCommandCount() {
    return store.<Integer>transaction(tx -> loadCommandsByThread(tx, root.threadId()).size());
  }

  private void assertNullWork() {
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.work().find(new WorkTarget(WorkTargetType.THREAD, root.threadId())).isEmpty()));
  }
}
