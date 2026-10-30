package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ThreadExecutionControl} 两个持久状态与 {@code isRunnable}/{@code isStopped} 全分支。 */
class ThreadExecutionControlTest {

  @Test
  void runnableIsNeitherStoppedNorResumedByAccident() {
    assertTrue(ThreadExecutionControl.RUNNABLE.isRunnable());
    assertFalse(ThreadExecutionControl.RUNNABLE.isStopped());
  }

  @Test
  void stoppedIsPersistentlyNotRunnable() {
    assertFalse(ThreadExecutionControl.STOPPED.isRunnable());
    assertTrue(ThreadExecutionControl.STOPPED.isStopped());
  }

  @Test
  void exposesExactlyTwoStatesRoundTrippableByName() {
    // 持久状态只有 RUNNABLE/STOPPED 两个，顺序稳定且可按 name 往返（持久化/协议不引入第三个状态）。
    assertArrayEquals(
        new ThreadExecutionControl[] {
          ThreadExecutionControl.RUNNABLE, ThreadExecutionControl.STOPPED
        },
        ThreadExecutionControl.values());
    for (ThreadExecutionControl control : ThreadExecutionControl.values()) {
      assertEquals(control, ThreadExecutionControl.valueOf(control.name()));
    }
    assertThrows(IllegalArgumentException.class, () -> ThreadExecutionControl.valueOf("PAUSED"));
  }
}
