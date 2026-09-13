package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationDispatcher;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 意图：验证 EnvironmentOperationDispatcherLifecycle 的 SmartLifecycle 契约： 自动启动、Phase、幂等
 * start/stop、回调执行、以及 start 失败时的原子回滚与状态恢复。
 */
class EnvironmentOperationDispatcherLifecycleTest {

  private EnvironmentOperationDispatcher dispatcher;
  private EnvironmentOperationDispatcherLifecycle lifecycle;

  @BeforeEach
  void setUp() {
    dispatcher = mock(EnvironmentOperationDispatcher.class);
    lifecycle = new EnvironmentOperationDispatcherLifecycle(dispatcher);
  }

  @Test
  void autoStartupAndPhaseContract() {
    assertTrue(lifecycle.isAutoStartup());
    assertEquals(Integer.MAX_VALUE - 1, lifecycle.getPhase());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startAndStopAreIdempotent() {
    lifecycle.start();
    assertTrue(lifecycle.isRunning());
    lifecycle.start(); // second call should be no-op
    verify(dispatcher, times(1)).start();

    lifecycle.stop();
    assertFalse(lifecycle.isRunning());
    lifecycle.stop(); // second call should be no-op
    verify(dispatcher, times(1)).stop();
  }

  @Test
  void stopWithCallbackExecutesCallback() {
    lifecycle.start();
    AtomicBoolean callbackRan = new AtomicBoolean(false);
    lifecycle.stop(() -> callbackRan.set(true));
    assertTrue(callbackRan.get());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void stopWithCallbackExecutesEvenIfStopFails() {
    lifecycle.start();
    doThrow(new IllegalStateException("stop failed")).when(dispatcher).stop();
    AtomicBoolean callbackRan = new AtomicBoolean(false);
    assertThrows(IllegalStateException.class, () -> lifecycle.stop(() -> callbackRan.set(true)));
    assertTrue(callbackRan.get());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startFailureRollsBackAndResetsRunning() {
    IllegalStateException startError = new IllegalStateException("start failed");
    doThrow(startError).when(dispatcher).start();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);
    assertSame(startError, thrown);
    assertFalse(lifecycle.isRunning());
    verify(dispatcher).stop(); // rollback attempted
  }

  @Test
  void startFailureWithRollbackFailureSuppressesRollbackError() {
    IllegalStateException startError = new IllegalStateException("start failed");
    IllegalStateException stopError = new IllegalStateException("rollback failed");
    doThrow(startError).when(dispatcher).start();
    doThrow(stopError).when(dispatcher).stop();

    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);
    assertSame(startError, thrown);
    assertFalse(lifecycle.isRunning());
    assertEquals(1, thrown.getSuppressed().length);
    assertSame(stopError, thrown.getSuppressed()[0]);
  }
}
