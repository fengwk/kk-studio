package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;

class ThreadActivityEvaluatorTest {

  @Test
  void statusValuesAreExactlyTheThreePublicStates() {
    // 测试意图：对外运行时状态只有 RUNNING / WAITING / IDLE 三态，与 executionControl 正交。
    assertEquals(
        Set.of(ThreadRuntimeStatus.RUNNING, ThreadRuntimeStatus.WAITING, ThreadRuntimeStatus.IDLE),
        Set.of(ThreadRuntimeStatus.values()));
  }

  @Test
  void mapsLocalContextsToRunningOrIdle() {
    // 测试意图：验证中立 live context 投影：仅 IdleOrHistorical 非 live，其余活跃阶段统一映射为 live。
    assertEquals(ThreadLiveState.IDLE_OR_HISTORICAL, ThreadLiveState.valueOf("IDLE_OR_HISTORICAL"));
    assertFalse(ThreadLiveState.IDLE_OR_HISTORICAL.isLive());
    for (ThreadLiveState state : ThreadLiveState.values()) {
      assertEquals(state != ThreadLiveState.IDLE_OR_HISTORICAL, state.isLive());
      assertEquals(
          state == ThreadLiveState.IDLE_OR_HISTORICAL
              ? ThreadRuntimeStatus.IDLE
              : ThreadRuntimeStatus.RUNNING,
          ThreadActivityEvaluator.evaluate(
              ThreadExecutionControl.RUNNABLE, state.isLive(), false, false, 0, false));
    }
  }

  @Test
  void evaluatorEnforcesInvariantsAndEvaluatesStatesAccurately() {
    boolean idle = false;
    boolean active = true;

    // 1. 参数校验
    assertThrows(
        NullPointerException.class,
        () -> ThreadActivityEvaluator.evaluate(null, idle, false, false, 0, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadActivityEvaluator.evaluate(
                ThreadExecutionControl.RUNNABLE, idle, false, false, -1, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadActivityEvaluator.isQuiescent(
                ThreadExecutionControl.RUNNABLE, idle, false, false, -1, false));

    // 2. 本地在途执行与 open compaction：始终 RUNNING
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, active, false, false, 0, false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, active, false, false, 0, false));
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, true, false, 0, false));

    // 3. input demand 与 executionControl 正交性
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, false, true, 0, false));
    assertEquals(
        ThreadRuntimeStatus.IDLE,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, idle, false, true, 0, false));
    assertEquals(
        ThreadRuntimeStatus.WAITING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, idle, false, true, 1, false));

    // 4. WAITING 与 IDLE
    assertEquals(
        ThreadRuntimeStatus.WAITING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 2, false));
    assertEquals(
        ThreadRuntimeStatus.IDLE,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 0, false));

    // 5. isQuiescent 收敛判断
    assertTrue(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 0, false));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, active, false, false, 0, false));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, true, false, 0, false));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, false, true, 0, false));
    // STOPPED 保留通知但不自启：输入需求不阻挡收敛，与上方 RUNNABLE 输入需求仍阻挡形成对照。
    assertTrue(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.STOPPED, idle, false, true, 0, false));
    // 未确认执行无论 STOPPED 与否都保持 RUNNING，且不得判定为收敛。
    assertEquals(
        ThreadRuntimeStatus.RUNNING,
        ThreadActivityEvaluator.evaluate(
            ThreadExecutionControl.STOPPED, idle, false, false, 0, true));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.STOPPED, idle, false, false, 0, true));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 1, false));
  }
}
