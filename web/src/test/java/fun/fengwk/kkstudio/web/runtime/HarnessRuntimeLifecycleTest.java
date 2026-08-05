package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlWorkListener;

import java.util.concurrent.atomic.AtomicBoolean;

/** {@link HarnessRuntimeLifecycle} 的启动/停止顺序、失败回滚、workers 开关与幂等语义。 */
class HarnessRuntimeLifecycleTest {

  @Test
  void startsDispatcherBeforeListener() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher, listener);

    lifecycle.start();

    InOrder order = inOrder(dispatcher, listener);
    order.verify(dispatcher).start();
    order.verify(listener).start();
    assertTrue(lifecycle.isRunning());
  }

  @Test
  void stopsListenerBeforeDispatcher() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher, listener);

    lifecycle.start();
    lifecycle.stop();

    InOrder order = inOrder(dispatcher, listener);
    order.verify(listener).stop();
    order.verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startFailureStopsBothAndRethrows() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    doThrow(new IllegalStateException("listener failed to start")).when(listener).start();
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher, listener);

    assertThrows(IllegalStateException.class, lifecycle::start);

    verify(dispatcher).start();
    verify(listener).stop();
    verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startRollbackWithStopFailuresPreservesFirstExceptionAndRetryIsNotSkipped() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    IllegalStateException startFailure = new IllegalStateException("listener failed to start");
    doThrow(startFailure).when(listener).start();
    doThrow(new IllegalStateException("listener stop failed")).when(listener).stop();
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher, listener);

    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);
    // 回滚总是尝试 listener 后 dispatcher stop：listener stop 失败被抑制到首个异常。
    assertSame(startFailure, thrown);
    verify(listener).stop();
    verify(dispatcher).stop();
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("listener stop failed", thrown.getSuppressed()[0].getMessage());
    assertFalse(lifecycle.isRunning());

    // running 已正确复位：start 重试不会被跳过。
    doNothing().when(listener).start();
    lifecycle.start();
    verify(dispatcher, times(2)).start();
    verify(listener, times(2)).start();
    assertTrue(lifecycle.isRunning());
  }

  @Test
  void stopWithDualFailuresAlwaysAttemptsBothAndPreservesFirstException() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    doThrow(new IllegalStateException("listener stop failed")).when(listener).stop();
    doThrow(new IllegalStateException("dispatcher stop failed")).when(dispatcher).stop();
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher, listener);

    lifecycle.start();
    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::stop);

    // listener 先停、dispatcher 后停：两边都被尝试，首个异常保留、后续异常抑制。
    assertEquals("listener stop failed", thrown.getMessage());
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("dispatcher stop failed", thrown.getSuppressed()[0].getMessage());
    InOrder order = inOrder(dispatcher, listener);
    order.verify(listener).stop();
    order.verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void workersDisabledNeverStartsAnything() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(false, dispatcher, listener);

    lifecycle.start();

    verifyNoInteractions(dispatcher, listener);
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void repeatedStartAndStopAreIdempotent() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher, listener);

    lifecycle.start();
    lifecycle.start();
    lifecycle.stop();
    lifecycle.stop();

    verify(dispatcher, times(1)).start();
    verify(listener, times(1)).start();
    verify(listener, times(1)).stop();
    verify(dispatcher, times(1)).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void stopCallbackRunsEvenWhenAlreadyStopped() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    PostgresqlWorkListener listener = mock(PostgresqlWorkListener.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher, listener);
    AtomicBoolean callbackRan = new AtomicBoolean();

    lifecycle.stop(() -> callbackRan.set(true));

    assertTrue(callbackRan.get());
    verifyNoInteractions(dispatcher, listener);
  }
}
