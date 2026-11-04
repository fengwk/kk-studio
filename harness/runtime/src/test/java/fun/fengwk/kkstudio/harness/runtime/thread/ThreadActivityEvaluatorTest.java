package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;

/**
 * 普通委派收敛静止判据（{@link ThreadActivityEvaluator#isQuiescent}）的共享事实测试。
 *
 * <p>对外三态运行状态的投影由公共 ThreadSnapshot 的纯值算法负责，本测试只锁定「何时可以向父结算」。
 */
class ThreadActivityEvaluatorTest {

  @Test
  void quiescenceRequiresIdleLocalContextAndNoBlockingInput() {
    // 测试意图：本地空闲、无 open compaction、无未确认执行且无未完成直接子 Join 才可结算；
    // RUNNABLE 的待启动输入阻挡结算，STOPPED 保留通知但不自启，因此不阻挡。
    boolean idle = false;
    boolean active = true;

    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadActivityEvaluator.isQuiescent(
                ThreadExecutionControl.RUNNABLE, idle, false, false, -1, false));

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
    // 未确认执行在 STOPPED 下同样不得判定为收敛。
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.STOPPED, idle, false, false, 0, true));
    assertFalse(
        ThreadActivityEvaluator.isQuiescent(
            ThreadExecutionControl.RUNNABLE, idle, false, false, 1, false));
  }
}
