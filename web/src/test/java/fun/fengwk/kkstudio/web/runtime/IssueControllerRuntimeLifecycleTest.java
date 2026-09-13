package fun.fengwk.kkstudio.web.runtime;

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

import fun.fengwk.kkstudio.platform.project.controller.IssueControllerDispatcher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link IssueControllerRuntimeLifecycle} 单元测试：
 *
 * <p>验证 worker 开关控制（workersEnabled=false 时不启动）、start/stop 幂等性、 stop callback 触发、start 异常时的回滚与
 * suppressed 异常包装、以及生命周期 phase 顺序。
 */
class IssueControllerRuntimeLifecycleTest {

  /** 测试意图：验证 workersEnabled=false 时，调用 start 不会启动 dispatcher， running 状态保持 false。 */
  @Test
  void workersDisabledDoesNotStartDispatcher() {
    IssueControllerDispatcher dispatcher = mock(IssueControllerDispatcher.class);
    IssueControllerRuntimeLifecycle lifecycle =
        new IssueControllerRuntimeLifecycle(false, dispatcher);

    assertFalse(lifecycle.isRunning(), "lifecycle must not be running initially");
    lifecycle.start();
    assertFalse(lifecycle.isRunning(), "lifecycle must stay stopped when workers are disabled");
    verify(dispatcher, never()).start();

    lifecycle.stop();
    verify(dispatcher, never()).stop();
  }

  /** 测试意图：验证 workersEnabled=true 时，start 和 stop 正确委托给 dispatcher， 并且重复调用具备幂等性。 */
  @Test
  void workersEnabledStartsAndStopsDispatcherIdempotently() {
    IssueControllerDispatcher dispatcher = mock(IssueControllerDispatcher.class);
    IssueControllerRuntimeLifecycle lifecycle =
        new IssueControllerRuntimeLifecycle(true, dispatcher);

    lifecycle.start();
    lifecycle.start();
    assertTrue(lifecycle.isRunning(), "lifecycle must be running after start");
    verify(dispatcher, times(1)).start();

    lifecycle.stop();
    lifecycle.stop();
    assertFalse(lifecycle.isRunning(), "lifecycle must not be running after stop");
    verify(dispatcher, times(1)).stop();
  }

  /** 测试意图：验证 stop(Runnable callback) 始终在 finally 块中调用 callback。 */
  @Test
  void stopWithCallbackInvokesCallback() {
    IssueControllerDispatcher dispatcher = mock(IssueControllerDispatcher.class);
    IssueControllerRuntimeLifecycle lifecycle =
        new IssueControllerRuntimeLifecycle(true, dispatcher);

    lifecycle.start();
    AtomicBoolean callbackRun = new AtomicBoolean(false);
    lifecycle.stop(() -> callbackRun.set(true));

    assertTrue(callbackRun.get(), "callback must be invoked upon stop");
    assertFalse(lifecycle.isRunning());
  }

  /** 测试意图：验证 start() 抛出异常时，lifecycle 触发回滚 stop 并正确复位 running 状态。 */
  @Test
  void startFailureRollsBackDispatcherAndResetsRunning() {
    IssueControllerDispatcher dispatcher = mock(IssueControllerDispatcher.class);
    doThrow(new RuntimeException("startup error")).when(dispatcher).start();

    IssueControllerRuntimeLifecycle lifecycle =
        new IssueControllerRuntimeLifecycle(true, dispatcher);

    RuntimeException error = assertThrows(RuntimeException.class, lifecycle::start);
    assertEquals("startup error", error.getMessage());
    verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning(), "running must be reset to false on start failure");
  }

  /** 测试意图：验证 start() 失败且回滚 stop() 也失败时，回滚异常被作为 suppressed 异常附加在原异常上。 */
  @Test
  void startFailureWithRollbackFailureSuppressesRollbackException() {
    IssueControllerDispatcher dispatcher = mock(IssueControllerDispatcher.class);
    doThrow(new RuntimeException("startup error")).when(dispatcher).start();
    doThrow(new RuntimeException("rollback error")).when(dispatcher).stop();

    IssueControllerRuntimeLifecycle lifecycle =
        new IssueControllerRuntimeLifecycle(true, dispatcher);

    RuntimeException error = assertThrows(RuntimeException.class, lifecycle::start);
    assertEquals("startup error", error.getMessage());
    assertEquals(1, error.getSuppressed().length);
    assertEquals("rollback error", error.getSuppressed()[0].getMessage());
    assertFalse(lifecycle.isRunning());
  }

  /**
   * 测试意图：验证 phase 为 Integer.MAX_VALUE - 1（确保早于 PostgresqlNotificationLoop 启动，晚于其停止）， 且
   * isAutoStartup 为 true。
   */
  @Test
  void phaseIsMaxMinusOneAndAutoStartupIsTrue() {
    IssueControllerDispatcher dispatcher = mock(IssueControllerDispatcher.class);
    IssueControllerRuntimeLifecycle lifecycle =
        new IssueControllerRuntimeLifecycle(true, dispatcher);

    assertEquals(Integer.MAX_VALUE - 1, lifecycle.getPhase());
    assertTrue(lifecycle.isAutoStartup());
  }
}
