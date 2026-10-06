package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.UUID;

/**
 * {@link HarnessRuntime#setThreadYolo}：单字段幂等策略，不与完整 Thread version 做 CAS。同值 no-op 不推进
 * version；值变化时精确 +1，且绝不创建 Command / Entry / Work（不唤醒 processors）。
 */
class HarnessRuntimeSetYoloTest {

  private InMemoryHarnessStore store;
  private TestClock clock;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    clock = new TestClock(T0);
    runtime = HarnessRuntimeTestSupport.runtime(store, clock);
  }

  /** 同值请求按原样返回当前 Thread：时间与 version 零触碰，不创建任何运行副作用。 */
  @Test
  void sameValueIsNoOpWithoutTouchingTimestamps() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    clock.advance(T1);

    ThreadState result =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), false));

    assertFalse(result.yoloPolicy().isEnabled());
    assertEquals(0L, result.version());
    assertEquals(T0, result.updatedAt());
    assertEquals(T0, result.createdAt());
    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, stored.version());
    assertEquals(T0, stored.updatedAt());
    assertNoCommandsEntriesOrWork(baseline.threadId());
  }

  /** 无关的 version 推进（含处理器进行中的 touchVersion）不得拒绝 YOLO：值变化仍成功，且只在最新锁定行上精确 +1。 */
  @Test
  void unrelatedVersionAdvanceDoesNotRejectYoloUpdate() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    store.transaction(
        tx -> {
          ThreadState locked = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(locked.touchVersion(T1));
          return null;
        });
    clock.advance(T3);

    ThreadState enabled =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), true));

    assertTrue(enabled.yoloPolicy().isEnabled());
    assertEquals(2L, enabled.version());
    assertEquals(T3, enabled.updatedAt());
    assertNoCommandsEntriesOrWork(baseline.threadId());
  }

  /** 值变化时更新 YOLO policy 且 version 精确 +1，重复调用逐次推进。 */
  @Test
  void changedValueUpdatesYoloAndBumpsVersionExactlyOncePerCall() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    clock.advance(T1);

    ThreadState enabled =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), true));
    assertTrue(enabled.yoloPolicy().isEnabled());
    assertEquals(1L, enabled.version());
    assertEquals(T1, enabled.updatedAt());
    assertEquals(T0, enabled.createdAt());

    clock.advance(T3);
    ThreadState disabled =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), false));
    assertFalse(disabled.yoloPolicy().isEnabled());
    assertEquals(2L, disabled.version());
    assertEquals(T3, disabled.updatedAt());
    assertEquals(T0, disabled.createdAt());

    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertFalse(stored.yoloPolicy().isEnabled());
    assertEquals(2L, stored.version());
    assertNoCommandsEntriesOrWork(baseline.threadId());
  }

  /** 子代理跟随执行根、不拥有开关：对子线程 setThreadYolo 必须拒绝且不留任何 mutation。 */
  @Test
  void childThreadToggleIsRejectedWithoutMutation() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID childThreadId = seedChildThread(baseline);
    clock.advance(T1);

    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.setThreadYolo(new SetThreadYoloCommand(childThreadId, true)));

    ThreadState child = store.transaction(tx -> tx.findThread(childThreadId).orElseThrow());
    assertEquals(ThreadYoloPolicy.follow(baseline.threadId()), child.yoloPolicy());
    assertEquals(0L, child.version());
    assertEquals(T0, child.updatedAt());
  }

  /** 根开关只写根行：子代理的 FOLLOW 目标、version、updatedAt 与 Work 都不被触碰，也不唤醒 processors。 */
  @Test
  void rootToggleLeavesDescendantRowsUntouched() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID childThreadId = seedChildThread(baseline);
    clock.advance(T1);

    ThreadState enabled =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), true));

    assertTrue(enabled.yoloPolicy().isEnabled());
    assertEquals(1L, enabled.version());
    ThreadState child = store.transaction(tx -> tx.findThread(childThreadId).orElseThrow());
    assertEquals(ThreadYoloPolicy.follow(baseline.threadId()), child.yoloPolicy());
    assertEquals(0L, child.version());
    assertEquals(T0, child.updatedAt());
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, childThreadId)).isEmpty()));
  }

  /** 根开关不追认既有 WAITING_APPROVAL：审批请求保持未决，只 bump 根自身 version。 */
  @Test
  void rootToggleDoesNotRetroactivelyApproveWaitingApproval() {
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);
    setWaitingApproval(store, baseline);
    clock.advance(T1);
    long versionBefore =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow()).version();

    ThreadState enabled =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), true));

    assertTrue(enabled.yoloPolicy().isEnabled());
    assertEquals(versionBefore + 1, enabled.version());
    ToolInvocation tool =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, tool.status());
    assertTrue(tool.approval().required());
    assertTrue(tool.approval().isUndecided());
  }

  private UUID seedChildThread(HarnessRuntimeTestSupport.Baseline baseline) {
    return store.transaction(
        tx -> {
          UUID childThreadId = tx.nextId();
          tx.insertThread(
              new ThreadState(
                  childThreadId,
                  baseline.sessionId(),
                  baseline.threadId(),
                  baseline.rootEntryId(),
                  CREATION_REQUEST_HASH,
                  "child",
                  ThreadYoloPolicy.follow(baseline.threadId()),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  T0,
                  T0));
          return childThreadId;
        });
  }

  private void assertNoCommandsEntriesOrWork(UUID threadId) {
    ThreadState thread = store.transaction(tx -> tx.lockThread(threadId).orElseThrow());
    assertEquals(1, runtime.getSessionEntries(thread.sessionId()).size());
    boolean noWork =
        store.transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)).isEmpty());
    assertTrue(noWork);
    boolean noCommands =
        store.transaction(
            tx -> {
              tx.lockThread(threadId);
              return tx.loadQueuedCommands(threadId).isEmpty();
            });
    assertTrue(noCommands);
  }
}
