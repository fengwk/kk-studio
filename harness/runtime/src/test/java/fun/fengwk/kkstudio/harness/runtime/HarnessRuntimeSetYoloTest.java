package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.UUID;

/**
 * {@link HarnessRuntime#setThreadYolo}：直接 YOLO 控制的同值 no-op 先于 version CAS、STALE_VERSION 冲突、 version
 * 精确 +1，且绝不创建 Command / Entry / Work（不唤醒 processors）。
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

  /** 同值请求在任何 version CAS 之前按原样返回当前 Thread：过期 expectedVersion 不冲突，时间/version 零触碰。 */
  @Test
  void sameValueIsNoOpBeforeVersionCasWithoutTouchingTimestamps() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    clock.advance(T1);

    ThreadState result =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), 999, false));

    assertFalse(result.yoloEnabled());
    assertEquals(0L, result.version());
    assertEquals(T0, result.updatedAt());
    assertEquals(T0, result.createdAt());
    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, stored.version());
    assertEquals(T0, stored.updatedAt());
    assertNoCommandsEntriesOrWork(baseline.threadId());
  }

  /** 值不同且 version 不匹配：STALE_VERSION 冲突，整事务零 mutation。 */
  @Test
  void changedValueWithStaleVersionConflictsWithoutMutation() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    clock.advance(T1);

    HarnessRuntimeConflictException failure =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), 1, true)));

    assertEquals(HarnessRuntimeConflictException.Reason.STALE_VERSION, failure.reason());
    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertFalse(stored.yoloEnabled());
    assertEquals(0L, stored.version());
    assertEquals(T0, stored.updatedAt());
    assertNoCommandsEntriesOrWork(baseline.threadId());
  }

  /** 值不同且 version 精确匹配：一次调用更新 yoloEnabled 且 version 精确 +1，重复调用逐次推进。 */
  @Test
  void changedValueUpdatesYoloAndBumpsVersionExactlyOncePerCall() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    clock.advance(T1);

    ThreadState enabled =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), 0, true));
    assertTrue(enabled.yoloEnabled());
    assertEquals(1L, enabled.version());
    assertEquals(T1, enabled.updatedAt());
    assertEquals(T0, enabled.createdAt());

    clock.advance(T3);
    ThreadState disabled =
        runtime.setThreadYolo(new SetThreadYoloCommand(baseline.threadId(), 1, false));
    assertFalse(disabled.yoloEnabled());
    assertEquals(2L, disabled.version());
    assertEquals(T3, disabled.updatedAt());
    assertEquals(T0, disabled.createdAt());

    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertFalse(stored.yoloEnabled());
    assertEquals(2L, stored.version());
    assertNoCommandsEntriesOrWork(baseline.threadId());
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
