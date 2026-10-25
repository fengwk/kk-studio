package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.modelInvocationWithRequest;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.modelRequest;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.responseWithToolCalls;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.targetReceipt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stop 的子树范围、每节点持久回执与 Join 结算契约。
 *
 * <p>Stop 把目标 Thread 与完整父子后代一次性置为 {@link ThreadExecutionControl#STOPPED}，不按 Join
 * 完成状态过滤；祖先保持不动。每个受影响节点 持久保存自己的 {@link StoppedThreadReceipt}，旧 stopRequestId
 * 的重放直接返回持久保存的整批回执且不停止其后启动的新工作。子 Join 在停止边界冻结为 终止结果；父 Thread 仍 RUNNABLE 时收到系统通知，父也在停止集合内时通知直接固化到历史。
 */
class HarnessRuntimeStopSubtreeTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = runtime(store, Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC));
  }

  @Test
  void stopSetsEverySubtreeNodeStoppedAndPersistsReceipts() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChild =
        createChildThread(child, root.threadId(), root.sessionId(), root.rootEntryId());

    StopResult result = runtime.stop(new StopCommand(root.threadId(), TestIds.id(11), 0L));

    assertFalse(result.replayed());
    assertEquals(3, result.stoppedThreads().size());
    assertEquals(ThreadExecutionControl.STOPPED, result.thread().executionControl());
    assertNotNull(targetReceipt(result).stoppedTurnEndEntryId());

    assertEquals(
        ThreadExecutionControl.STOPPED,
        store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow()).executionControl());
    assertEquals(
        ThreadExecutionControl.STOPPED,
        store.transaction(tx -> tx.findThread(child).orElseThrow()).executionControl());
    assertEquals(
        ThreadExecutionControl.STOPPED,
        store.transaction(tx -> tx.findThread(grandChild).orElseThrow()).executionControl());

    // 每个节点恰好递增一次 version，并各有一条自己的停止边界。
    assertEquals(1L, version(root.threadId()));
    assertEquals(1L, version(child));
    assertEquals(1L, version(grandChild));
    for (StoppedThreadReceipt receipt : result.stoppedThreads()) {
      assertNotNull(receipt.stoppedTurnEndEntryId());
    }
    assertEquals(TestIds.id(11), targetReceipt(result).stopRequestId());
    assertEquals(
        List.of(root.threadId(), child, grandChild).stream().sorted().toList(),
        result.stoppedThreads().stream().map(StoppedThreadReceipt::threadId).sorted().toList());
    // 每个后代使用由根请求派生的确定性 stopRequestId。
    assertEquals(
        StopControl.deriveChildStopRequestId(TestIds.id(11), child),
        result.stoppedThreads().stream()
            .filter(receipt -> receipt.threadId().equals(child))
            .findFirst()
            .orElseThrow()
            .stopRequestId());
  }

  @Test
  void stoppingIntermediateChildLeavesRootUntouched() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChild =
        createChildThread(child, root.threadId(), root.sessionId(), root.rootEntryId());

    StopResult result = runtime.stop(new StopCommand(child, TestIds.id(12), 0L));

    assertEquals(2, result.stoppedThreads().size());
    assertEquals(1L, version(child));
    assertEquals(1L, version(grandChild));
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow()).executionControl());
    assertEquals(0L, version(root.threadId()));
  }

  @Test
  void stoppingChildDoesNotLockItsUnrelatedSibling() {
    // 测试意图：Stop 只锁目标子树与为 Join 交付所需的祖先，不锁同一父下无关的兄弟 Thread。
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID sibling =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    List<String> locks = new ArrayList<>();
    HarnessRuntime recording =
        runtime(
            recordingStore(store, locks), Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC));

    recording.stop(new StopCommand(child, TestIds.id(16), 0L));

    assertTrue(locks.contains("lockTree:" + root.threadId()), "structural Stop keeps tree lock");
    assertTrue(locks.contains("lockThread:" + child), "Stop locks the stopped subtree");
    assertTrue(locks.contains("lockThread:" + root.threadId()), "Stop locks joined ancestors");
    assertFalse(locks.contains("lockThread:" + sibling), "Stop must not lock unrelated siblings");
  }

  @Test
  void stoppingDeepTargetLocksOnlySubtreeAndDirectParent() {
    // 测试意图：深层目标 Stop 仅锁目标子树与目标直接父；grandparent/unrelated sibling 不锁，
    // 直接父收到 Join 通知与 Work，深层祖先 head/version 不变，receipt 集合可精确 replay。
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID parent =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID target = createChildThread(parent, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID descendant =
        createChildThread(target, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID relatedSibling =
        createChildThread(parent, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID rootSibling =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, target, 1L, userMessagePayload("delegated-task"), TestIds.id(50));
    insertJoin(joinId, parent, target);
    ThreadState rootBefore = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    UUID parentHeadBefore =
        store.transaction(tx -> tx.findThread(parent).orElseThrow()).headEntryId();

    List<String> locks = new ArrayList<>();
    HarnessRuntime recording =
        runtime(
            recordingStore(store, locks), Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC));
    StopResult first = recording.stop(new StopCommand(target, TestIds.id(23), 0L));

    assertFalse(first.replayed());
    assertEquals(
        List.of(target, descendant).stream().sorted(UuidOrder.COMPARATOR).toList(),
        first.stoppedThreads().stream()
            .map(StoppedThreadReceipt::threadId)
            .sorted(UuidOrder.COMPARATOR)
            .toList());
    List<String> expectedLocks = new ArrayList<>();
    expectedLocks.add("lockTree:" + root.threadId());
    expectedLocks.add("lockSessionForKeyShare:" + root.sessionId());
    for (UUID threadId :
        List.of(parent, target, descendant).stream().sorted(UuidOrder.COMPARATOR).toList()) {
      expectedLocks.add("lockThread:" + threadId);
    }
    assertEquals(
        expectedLocks,
        locks.stream()
            .filter(
                op ->
                    op.startsWith("lockTree:")
                        || op.startsWith("lockSessionForKeyShare:")
                        || op.startsWith("lockThread:"))
            .toList());

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.deliveryCommandSequence());
    List<ThreadCommand> parentCommands = store.transaction(tx -> tx.loadCommandsByThread(parent));
    assertEquals(1, parentCommands.size());
    assertTrue(parentCommands.getFirst().payload() instanceof NotificationCommandPayload);
    assertEquals(ThreadCommandState.QUEUED, parentCommands.getFirst().state());
    assertNotNull(
        store.transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, parent)).orElse(null)));
    assertEquals(1L, version(target));
    assertEquals(1L, version(descendant));
    assertEquals(1L, version(parent));
    assertEquals(
        parentHeadBefore,
        store.transaction(tx -> tx.findThread(parent).orElseThrow()).headEntryId());
    ThreadState rootAfter = store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow());
    assertEquals(rootBefore.headEntryId(), rootAfter.headEntryId());
    assertEquals(0L, rootAfter.version());
    assertEquals(0L, version(relatedSibling));
    assertEquals(0L, version(rootSibling));

    // exact replay：同一 stopRequestId 返回持久回执集合，不重算当前树、不推进任何 version。
    StopResult replay = recording.stop(new StopCommand(target, TestIds.id(23), 1L));
    assertTrue(replay.replayed());
    assertEquals(
        first.stoppedThreads().stream().map(StoppedThreadReceipt::threadId).sorted().toList(),
        replay.stoppedThreads().stream().map(StoppedThreadReceipt::threadId).sorted().toList());
    assertEquals(1L, version(target));
    assertEquals(1L, version(descendant));
    assertEquals(1L, version(parent));
  }

  @Test
  void stoppingParentMaterializesChildJoinToStoppedDirectParentWithoutWake() {
    // 测试意图：目标父本身在停止集合内时，子 Join 通知直接物化到该父历史，不留 queued 命令或 Work；
    // 仅目标直接父被锁，停止集合外的 grandparent/unrelated sibling 保持不变。
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID parent =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID child = createChildThread(parent, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID sibling = createChildThread(parent, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID rootSibling =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, child, 1L, userMessagePayload("delegated-task"), TestIds.id(51));
    insertJoin(joinId, parent, child);

    List<String> locks = new ArrayList<>();
    HarnessRuntime recording =
        runtime(
            recordingStore(store, locks), Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC));
    StopResult result = recording.stop(new StopCommand(parent, TestIds.id(24), 0L));

    assertEquals(
        List.of(parent, child, sibling).stream().sorted(UuidOrder.COMPARATOR).toList(),
        result.stoppedThreads().stream()
            .map(StoppedThreadReceipt::threadId)
            .sorted(UuidOrder.COMPARATOR)
            .toList());
    assertTrue(locks.contains("lockTree:" + root.threadId()));
    assertTrue(locks.contains("lockThread:" + parent));
    assertTrue(locks.contains("lockThread:" + child));
    assertTrue(locks.contains("lockThread:" + sibling));
    assertTrue(
        locks.contains("lockThread:" + root.threadId()), "direct parent of target is locked");
    assertFalse(
        locks.contains("lockThread:" + rootSibling), "unrelated sibling must not be locked");

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.deliveryCommandSequence());
    assertEquals(1, notifications(parent).size());
    assertTrue(
        store.<Boolean>transaction(
            tx -> {
              tx.lockThread(parent);
              return tx.loadQueuedCommands(parent).isEmpty();
            }));
    assertNull(
        store.transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, parent)).orElse(null)));
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow()).executionControl());
    assertEquals(0L, version(root.threadId()));
    assertEquals(0L, version(rootSibling));
  }

  @Test
  void replayReturnsPersistedReceiptSetWithoutStoppingNewWork() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());

    StopResult first = runtime.stop(new StopCommand(child, TestIds.id(13), 0L));
    long childVersionAfterStop = version(child);

    // 旧请求重放：返回同一持久回执集合，不写新工作。
    StopResult replay = runtime.stop(new StopCommand(child, TestIds.id(13), childVersionAfterStop));
    assertTrue(replay.replayed());
    assertEquals(
        List.copyOf(first.stoppedThreads()).stream()
            .map(StoppedThreadReceipt::threadId)
            .sorted()
            .toList(),
        replay.stoppedThreads().stream().map(StoppedThreadReceipt::threadId).sorted().toList());
    assertEquals(childVersionAfterStop, version(child));

    // 旧请求重放不得停止其后新建的后代。
    UUID newChild = createChildThread(child, root.threadId(), root.sessionId(), root.rootEntryId());
    StopResult replayAgain =
        runtime.stop(new StopCommand(child, TestIds.id(13), childVersionAfterStop));
    assertTrue(replayAgain.replayed());
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(newChild).orElseThrow()).executionControl());
  }

  @Test
  void stopFreezesChildJoinAndNotifiesRunnableParent() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, child, 1L, userMessagePayload("delegated-task"), TestIds.id(20));
    insertJoin(joinId, root.threadId(), child);

    StopResult result = runtime.stop(new StopCommand(child, TestIds.id(14), 0L));

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertEquals(targetReceipt(result).stoppedTurnEndEntryId(), join.terminalEntryId());
    assertNotNull(join.deliveryCommandSequence());

    // 父仍 RUNNABLE：通知以命令交付并请求父 Thread Work。
    List<ThreadCommand> parentCommands =
        store.transaction(tx -> tx.loadCommandsByThread(root.threadId()));
    assertEquals(1, parentCommands.size());
    assertTrue(parentCommands.getFirst().payload() instanceof NotificationCommandPayload);
    assertEquals(ThreadCommandState.QUEUED, parentCommands.getFirst().state());
  }

  @Test
  void stopWhenParentAlsoStoppedMaterializesNotificationToHistory() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, child, 1L, userMessagePayload("delegated-task"), TestIds.id(21));
    insertJoin(joinId, root.threadId(), child);

    StopResult result = runtime.stop(new StopCommand(root.threadId(), TestIds.id(15), 0L));

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.deliveryCommandSequence());
    assertNotEquals(0L, join.deliveryCommandSequence());

    // 父也在停止集合内：通知直接固化到历史，不留下 queued 命令。
    assertTrue(rootPathContainsNotification(root.threadId()));
    boolean noQueuedCommands =
        store.transaction(
            tx -> {
              tx.lockThread(root.threadId());
              return tx.loadQueuedCommands(root.threadId()).isEmpty();
            });
    assertTrue(noQueuedCommands);
    assertEquals(ThreadExecutionControl.STOPPED, result.thread().executionControl());
  }

  @Test
  void stopBeforeSourceExecutedProjectsCancelledJoin() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, child, 1L, userMessagePayload("unexecuted-task"), TestIds.id(22));
    insertJoin(joinId, root.threadId(), child);

    runtime.stop(new StopCommand(child, TestIds.id(16), 0L));

    assertTrue(store.transaction(tx -> tx.findJoin(joinId).orElseThrow()).matched());
    assertEquals(
        ThreadJoinOutcome.CANCELLED, runtime.projectJoinReceipt(joinId).orElseThrow().outcome());
  }

  private long version(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow()).version();
  }

  private boolean rootPathContainsNotification(UUID threadId) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.findThread(threadId).orElseThrow();
          EntryPath path = tx.loadEntryPath(thread.headEntryId());
          for (Entry entry : path.entries()) {
            if (entry.payload() instanceof NotificationPayload) {
              return true;
            }
          }
          return false;
        });
  }

  /** S3：Stop 前已存在的 queued 系统通知不取消，而是物化为 APPLIED 命令 + 恰一条 NOTIFICATION Entry（推进 head）。 */
  @Test
  void stopPreservesQueuedNotificationAsAppliedEntry() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID notificationId = UUID.randomUUID();
    UUID sourceThreadId = UUID.randomUUID();
    seedQueuedCommand(
        store,
        root.threadId(),
        1L,
        new NotificationCommandPayload(
            notificationId,
            NotificationKind.SUBAGENT_RESULT,
            sourceThreadId,
            new AgentMessage(
                AgentMessageRole.USER, List.of(new TextMessageContent("child-result")))),
        TestIds.id(30));

    // 手工种入的 QUEUED notification 也须预留序列，保持 nextCommandSequence 领先于已用 sequence。
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(root.threadId()).orElseThrow();
          tx.updateThread(thread.reserveCommandSequences(1, T1));
          return null;
        });

    runtime.stop(
        new StopCommand(
            root.threadId(),
            TestIds.id(16),
            store.transaction(tx -> tx.findThread(root.threadId()).orElseThrow()).version()));

    // 通知命令保留为 APPLIED，且恰指向一条物化 NOTIFICATION Entry。
    ThreadCommand command =
        store.transaction(tx -> tx.findCommand(root.threadId(), 1L).orElseThrow());
    assertEquals(ThreadCommandState.APPLIED, command.state());
    assertNotNull(command.appliedEntryId());
    List<Entry> notifications = notifications(root.threadId());
    assertEquals(1, notifications.size());
    assertEquals(command.appliedEntryId(), notifications.getFirst().id());
    NotificationPayload payload = (NotificationPayload) notifications.getFirst().payload();
    assertEquals(notificationId, payload.notificationId());
    assertEquals(sourceThreadId, payload.sourceThreadId());
    // 没有残留 QUEUED 命令。
    assertTrue(
        store.<Boolean>transaction(
            tx -> {
              tx.lockThread(root.threadId());
              return tx.loadQueuedCommands(root.threadId()).isEmpty();
            }));
  }

  /**
   * S3：Stop 覆盖的子树内 child 已提交最终结果（terminal Model 尚未 apply）时，先交付 committed final 再交付 Stop
   * joins；父也在停止集合内，父通知直接固化到历史（APPLIED + 恰一条 NOTIFICATION Entry），绝不遗留 QUEUED 或 Work。
   */
  @Test
  void stopDeliversCommittedChildFinalToStoppedParentWithoutQueuedOrWorkLeftover() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    UUID modelId = seedCommittedChildFinal(root, child, joinId);

    StopResult result = runtime.stop(new StopCommand(root.threadId(), TestIds.id(17), 0L));

    assertTrue(result.stoppedThreads().stream().anyMatch(r -> r.threadId().equals(child)));
    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.finalAnswerEntryId());
    assertNotNull(join.deliveryCommandSequence());

    // 父也在停止集合：通知直接固化到父历史，命令为 APPLIED 而非 QUEUED。
    List<ThreadCommand> parentCommands =
        store.transaction(tx -> tx.loadCommandsByThread(root.threadId()));
    assertEquals(1, parentCommands.size());
    ThreadCommand delivered = parentCommands.getFirst();
    assertTrue(delivered.payload() instanceof NotificationCommandPayload);
    assertEquals(ThreadCommandState.APPLIED, delivered.state());
    assertNotNull(delivered.appliedEntryId());
    assertEquals(1, notifications(root.threadId()).size());
    assertTrue(
        store.<Boolean>transaction(
            tx -> {
              tx.lockThread(root.threadId());
              return tx.loadQueuedCommands(root.threadId()).isEmpty();
            }));

    // 零遗留 Work：child THREAD、child MODEL 与父 THREAD 的 Work 全部删除。
    assertNull(
        store.transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, child)).orElse(null)));
    assertNull(
        store.transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.MODEL, modelId)).orElse(null)));
    assertNull(
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, root.threadId())).orElse(null)));
  }

  private List<Entry> notifications(UUID threadId) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.findThread(threadId).orElseThrow();
          EntryPath path = tx.loadEntryPath(thread.headEntryId());
          return path.entries().stream()
              .filter(entry -> entry.payload() instanceof NotificationPayload)
              .toList();
        });
  }

  /** 子 Thread 上已提交未 apply 的 COMPLETE 最终结果，源命令已 APPLIED，并挂一条指向 root 的 join。 */
  private UUID seedCommittedChildFinal(
      HarnessRuntimeTestSupport.Baseline root, UUID childId, UUID joinId) {
    return store.transaction(
        tx -> {
          UUID turnStartEntryId = tx.nextId();
          tx.insertEntry(
              turnStartEntry(turnStartEntryId, root.sessionId(), root.rootEntryId(), T1, childId));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, root.sessionId(), turnStartEntryId, T1));
          ThreadState child = tx.lockThread(childId).orElseThrow();
          tx.updateThread(
              child.reserveCommandSequencesAdvanceHeadAndInput(
                  1, userEntryId, child.inputThroughSequence(), T1));

          ThreadCommandPayload payload = userMessagePayload("delegated-task");
          ThreadCommand source =
              new ThreadCommand(
                  childId,
                  1L,
                  payload,
                  TestIds.id(40),
                  ThreadCommandPayloadJsonCodec.requestHash(payload),
                  null,
                  null,
                  null,
                  T1);
          tx.insertCommands(List.of(source));
          tx.updateCommands(List.of(source.markApplied(turnStartEntryId)));

          UUID modelId = tx.nextId();
          ModelInvocation model =
              modelInvocationWithRequest(
                  modelId, childId, turnStartEntryId, userEntryId, modelRequest(), T1);
          tx.insertModelInvocation(model);
          ProviderResponse response = responseWithToolCalls();
          tx.updateModelInvocation(model.markRunning(T2));
          tx.updateModelInvocation(model.markRunning(T2).succeed(response, T2));

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
                  T0,
                  JoinDelivery.NOTIFY_PARENT,
                  null));
          return modelId;
        });
  }

  private UUID createChildThread(
      UUID parentThreadId, UUID rootThreadId, UUID sessionId, UUID headEntryId) {
    UUID childId = UUID.randomUUID();
    store.transaction(
        tx -> {
          tx.insertThread(
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
                  T0,
                  T0));
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
              if (!method.getName().equals("transaction")) {
                return method.invoke(delegate, args);
              }
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
                                      || name.equals("lockThread")) {
                                    lockCalls.add(name + ":" + transactionArgs[0]);
                                  }
                                  return transactionMethod.invoke(tx, transactionArgs);
                                });
                    return callback.apply(wrapped);
                  });
            });
  }

  private void insertJoin(UUID joinId, UUID parentThreadId, UUID childThreadId) {
    store.transaction(
        tx -> {
          tx.lockThread(childThreadId);
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
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
}
