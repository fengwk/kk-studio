package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.targetReceipt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
    UUID child = createChildThread(root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChild = createChildThread(child, root.sessionId(), root.rootEntryId());

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
    UUID child = createChildThread(root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChild = createChildThread(child, root.sessionId(), root.rootEntryId());

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
  void replayReturnsPersistedReceiptSetWithoutStoppingNewWork() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child = createChildThread(root.threadId(), root.sessionId(), root.rootEntryId());

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
    UUID newChild = createChildThread(child, root.sessionId(), root.rootEntryId());
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
    UUID child = createChildThread(root.threadId(), root.sessionId(), root.rootEntryId());
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
    UUID child = createChildThread(root.threadId(), root.sessionId(), root.rootEntryId());
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
    UUID child = createChildThread(root.threadId(), root.sessionId(), root.rootEntryId());
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

  private UUID createChildThread(UUID parentThreadId, UUID sessionId, UUID headEntryId) {
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
                  false,
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
                  T0));
          return null;
        });
  }
}
