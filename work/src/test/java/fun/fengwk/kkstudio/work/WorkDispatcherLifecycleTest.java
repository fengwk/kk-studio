package fun.fengwk.kkstudio.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/**
 * {@link WorkDispatcherLifecycle} 的启停契约。
 *
 * <p>验证 disabled 不启动、start/stop 幂等、start 失败时关闭 dispatcher 并复位 running，以及 phase 与自动启动契约。
 */
class WorkDispatcherLifecycleTest {

  /** disabled 时 start 与 stop 都不触碰 dispatcher。 */
  @Test
  void disabledDoesNotStartDispatcher() {
    BoundedWorkDispatcher<String> dispatcher = dispatcher();
    WorkDispatcherLifecycle lifecycle = new WorkDispatcherLifecycle(false, dispatcher);

    lifecycle.start();
    lifecycle.stop();

    assertFalse(lifecycle.isRunning());
    verify(dispatcher, never()).start();
    verify(dispatcher, never()).stop();
  }

  /** enabled 时 start/stop 只委托一次，重复调用保持幂等。 */
  @Test
  void enabledStartsAndStopsIdempotently() {
    BoundedWorkDispatcher<String> dispatcher = dispatcher();
    WorkDispatcherLifecycle lifecycle = new WorkDispatcherLifecycle(true, dispatcher);

    lifecycle.start();
    assertTrue(lifecycle.isRunning());
    lifecycle.start();
    lifecycle.stop();
    lifecycle.stop();

    assertFalse(lifecycle.isRunning());
    verify(dispatcher, times(1)).start();
    verify(dispatcher, times(1)).stop();
  }

  /** start 失败时关闭 dispatcher，并把 running 复位为 false。 */
  @Test
  void startFailureRollsBackAndResetsRunning() {
    BoundedWorkDispatcher<String> dispatcher = dispatcher();
    doThrow(new RuntimeException("startup error")).when(dispatcher).start();
    WorkDispatcherLifecycle lifecycle = new WorkDispatcherLifecycle(true, dispatcher);

    RuntimeException error = assertThrows(RuntimeException.class, lifecycle::start);

    assertEquals("startup error", error.getMessage());
    verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning());
  }

  /** phase 保持在通知循环之前启动、之后停止，并允许容器自动启动。 */
  @Test
  void phaseAndAutoStartupMatchTheDispatcherContract() {
    WorkDispatcherLifecycle lifecycle = new WorkDispatcherLifecycle(true, dispatcher());

    assertEquals(Integer.MAX_VALUE - 1, lifecycle.getPhase());
    assertTrue(lifecycle.isAutoStartup());
  }

  @SuppressWarnings("unchecked")
  private static BoundedWorkDispatcher<String> dispatcher() {
    return mock(BoundedWorkDispatcher.class);
  }
}
