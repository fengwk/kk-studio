package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;

import java.util.concurrent.atomic.AtomicBoolean;

/** {@link HarnessRuntimeLifecycle} 的 dispatcher 启停、失败回滚、workers 开关与幂等语义。 */
class HarnessRuntimeLifecycleTest {

  @Test
  void startsAndStopsDispatcher() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher);

    lifecycle.start();
    lifecycle.stop();

    verify(dispatcher).start();
    verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startFailureStopsDispatcherAndRethrows() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    doThrow(new IllegalStateException("dispatcher failed to start")).when(dispatcher).start();
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher);

    assertThrows(IllegalStateException.class, lifecycle::start);

    verify(dispatcher).start();
    verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startRollbackWithStopFailuresPreservesFirstExceptionAndRetryIsNotSkipped() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    IllegalStateException startFailure = new IllegalStateException("dispatcher failed to start");
    doThrow(startFailure).when(dispatcher).start();
    doThrow(new IllegalStateException("dispatcher stop failed")).when(dispatcher).stop();
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher);

    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);
    // 启动失败后的 stop 回滚异常被抑制到首个异常，running 同时复位。
    assertSame(startFailure, thrown);
    verify(dispatcher).stop();
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("dispatcher stop failed", thrown.getSuppressed()[0].getMessage());
    assertFalse(lifecycle.isRunning());

    // running 已正确复位：start 重试不会被跳过。
    doNothing().when(dispatcher).start();
    lifecycle.start();
    verify(dispatcher, times(2)).start();
    assertTrue(lifecycle.isRunning());
  }

  /**
   * 测试意图：底层 {@code HarnessWorkDispatcher.stop()} 当前在置位后不会抛出；这里注入异常以固定契约—— 一旦 stop
   * 抛异常（未观察到成功关闭），绝不能假装已停止，必须保留 running 以允许重试，且重试会真正再次调用 stop。
   */
  @Test
  void stopFailureKeepsRunningSoCleanupCanBeRetried() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    doThrow(new IllegalStateException("dispatcher stop failed"))
        .doNothing()
        .when(dispatcher)
        .stop();
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher);

    lifecycle.start();
    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::stop);

    assertEquals("dispatcher stop failed", thrown.getMessage());
    verify(dispatcher).stop();
    // 未确认关闭：状态如实保持 running，绝不写 running=false 谎报已停止。
    assertTrue(lifecycle.isRunning());

    // 重试必须再次真实调用 stop，成功后状态才转为 stopped。
    lifecycle.stop();
    verify(dispatcher, times(2)).stop();
    assertFalse(lifecycle.isRunning());
  }

  /** 测试意图：stop(Runnable) 在 stop 失败时仍必须在 finally 中回调，且状态保持可重试。 */
  @Test
  void stopCallbackRunsEvenWhenStopFailsAndKeepsRunning() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    doThrow(new IllegalStateException("dispatcher stop failed")).when(dispatcher).stop();
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher);
    AtomicBoolean callbackRan = new AtomicBoolean();

    lifecycle.start();
    assertThrows(IllegalStateException.class, () -> lifecycle.stop(() -> callbackRan.set(true)));

    assertTrue(callbackRan.get(), "callback 必须无条件执行");
    assertTrue(lifecycle.isRunning(), "停止未确认时必须保持 running");
  }

  @Test
  void workersDisabledNeverStartsAnything() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(false, dispatcher);

    lifecycle.start();

    verifyNoInteractions(dispatcher);
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void repeatedStartAndStopAreIdempotent() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher);

    lifecycle.start();
    lifecycle.start();
    lifecycle.stop();
    lifecycle.stop();

    verify(dispatcher, times(1)).start();
    verify(dispatcher, times(1)).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void stopCallbackRunsEvenWhenAlreadyStopped() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = new HarnessRuntimeLifecycle(true, dispatcher);
    AtomicBoolean callbackRan = new AtomicBoolean();

    lifecycle.stop(() -> callbackRan.set(true));

    assertTrue(callbackRan.get());
    verifyNoInteractions(dispatcher);
  }
}
