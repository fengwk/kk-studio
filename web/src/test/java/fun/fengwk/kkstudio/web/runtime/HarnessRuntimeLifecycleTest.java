package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.builtin.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** {@link HarnessRuntimeLifecycle} 的 dispatcher 启停、排空编排、失败回滚与 workers 开关语义。 */
class HarnessRuntimeLifecycleTest {

  @SuppressWarnings("unchecked")
  private HarnessRuntimeLifecycle defaultLifecycle(
      boolean workersEnabled, HarnessWorkDispatcher dispatcher) {
    return new HarnessRuntimeLifecycle(
        workersEnabled,
        dispatcher,
        () -> false,
        mock(ToolProcessor.class),
        mock(ObjectProvider.class),
        Duration.ofSeconds(60));
  }

  @Test
  void startsAndStopsDispatcher() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);

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
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);

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
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);

    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);
    assertSame(startFailure, thrown);
    verify(dispatcher).stop();
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("dispatcher stop failed", thrown.getSuppressed()[0].getMessage());
    assertFalse(lifecycle.isRunning());

    doNothing().when(dispatcher).start();
    lifecycle.start();
    verify(dispatcher, times(2)).start();
    assertTrue(lifecycle.isRunning());
  }

  @Test
  void stopFailureKeepsRunningSoCleanupCanBeRetried() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    doThrow(new IllegalStateException("dispatcher stop failed"))
        .doNothing()
        .when(dispatcher)
        .stop();
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);

    lifecycle.start();
    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::stop);

    assertEquals("dispatcher stop failed", thrown.getMessage());
    verify(dispatcher).stop();
    assertTrue(lifecycle.isRunning());

    lifecycle.stop();
    verify(dispatcher, times(2)).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void stopCallbackRunsEvenWhenStopFailsAndKeepsRunning() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    doThrow(new IllegalStateException("dispatcher stop failed")).when(dispatcher).stop();
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);
    AtomicBoolean callbackRan = new AtomicBoolean();

    lifecycle.start();
    assertThrows(IllegalStateException.class, () -> lifecycle.stop(() -> callbackRan.set(true)));

    assertTrue(callbackRan.get(), "callback 必须无条件执行");
    assertTrue(lifecycle.isRunning(), "停止未确认时必须保持 running");
  }

  @Test
  void workersDisabledNeverStartsAnything() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(false, dispatcher);

    lifecycle.start();

    verifyNoInteractions(dispatcher);
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void repeatedStartAndStopAreIdempotent() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);

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
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);
    AtomicBoolean callbackRan = new AtomicBoolean();

    lifecycle.stop(() -> callbackRan.set(true));

    assertTrue(callbackRan.get());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void normalShutdownSequenceOrchestratesAllSteps() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    ModelProcessor modelProcessor = mock(ModelProcessor.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = mock(ObjectProvider.class);
    EnvironmentDaemonServer daemonServer = mock(EnvironmentDaemonServer.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentDaemonServer> consumer = invocation.getArgument(0);
              consumer.accept(daemonServer);
              return null;
            })
        .when(daemonServerProvider)
        .ifAvailable(any());

    when(modelProcessor.hasActiveExecution()).thenReturn(false);
    when(toolProcessor.hasActiveExecution()).thenReturn(false);

    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            modelProcessor::hasActiveExecution,
            toolProcessor,
            daemonServerProvider,
            Duration.ofSeconds(10));

    lifecycle.start();
    lifecycle.stop();

    InOrder inOrder = inOrder(dispatcher, daemonServer, toolProcessor);
    inOrder.verify(dispatcher).stop();
    inOrder.verify(daemonServer).drain();
    inOrder.verify(toolProcessor).drain();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void waitingTimeoutStillExecutesDrainAndHandover() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    ModelProcessor modelProcessor = mock(ModelProcessor.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = mock(ObjectProvider.class);
    EnvironmentDaemonServer daemonServer = mock(EnvironmentDaemonServer.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentDaemonServer> consumer = invocation.getArgument(0);
              consumer.accept(daemonServer);
              return null;
            })
        .when(daemonServerProvider)
        .ifAvailable(any());

    when(modelProcessor.hasActiveExecution()).thenReturn(true);
    when(toolProcessor.hasActiveExecution()).thenReturn(true);

    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            modelProcessor::hasActiveExecution,
            toolProcessor,
            daemonServerProvider,
            Duration.ofMillis(30));

    lifecycle.start();
    lifecycle.stop();

    InOrder inOrder = inOrder(dispatcher, daemonServer, toolProcessor);
    inOrder.verify(dispatcher).stop();
    inOrder.verify(daemonServer).drain();
    inOrder.verify(toolProcessor).drain();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void waitingInterruptedRestoresFlagAndStillExecutesDrainAndHandover() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    ModelProcessor modelProcessor = mock(ModelProcessor.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = mock(ObjectProvider.class);
    EnvironmentDaemonServer daemonServer = mock(EnvironmentDaemonServer.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentDaemonServer> consumer = invocation.getArgument(0);
              consumer.accept(daemonServer);
              return null;
            })
        .when(daemonServerProvider)
        .ifAvailable(any());

    when(modelProcessor.hasActiveExecution()).thenReturn(true);

    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            modelProcessor::hasActiveExecution,
            toolProcessor,
            daemonServerProvider,
            Duration.ofSeconds(10));

    lifecycle.start();
    Thread.currentThread().interrupt();
    try {
      lifecycle.stop();
      assertTrue(Thread.currentThread().isInterrupted(), "中断标志必须被保留");
    } finally {
      Thread.interrupted();
    }

    InOrder inOrder = inOrder(dispatcher, daemonServer, toolProcessor);
    inOrder.verify(dispatcher).stop();
    inOrder.verify(daemonServer).drain();
    inOrder.verify(toolProcessor).drain();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void unconfiguredEnvironmentDaemonServerSkipsServerDrain() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    ModelProcessor modelProcessor = mock(ModelProcessor.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = mock(ObjectProvider.class);

    when(modelProcessor.hasActiveExecution()).thenReturn(false);
    when(toolProcessor.hasActiveExecution()).thenReturn(false);

    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            modelProcessor::hasActiveExecution,
            toolProcessor,
            daemonServerProvider,
            Duration.ofSeconds(10));

    lifecycle.start();
    lifecycle.stop();

    verify(dispatcher).stop();
    verify(toolProcessor).drain();
    verify(daemonServerProvider).ifAvailable(any());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void drainTimeoutZeroDoesNotWait() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    ModelProcessor modelProcessor = mock(ModelProcessor.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = mock(ObjectProvider.class);

    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            modelProcessor::hasActiveExecution,
            toolProcessor,
            daemonServerProvider,
            Duration.ZERO);

    lifecycle.start();
    lifecycle.stop();

    verify(dispatcher).stop();
    verify(modelProcessor, never()).hasActiveExecution();
    verify(toolProcessor, never()).hasActiveExecution();
    verify(toolProcessor).drain();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void negativeDrainTimeoutThrows() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    ModelProcessor modelProcessor = mock(ModelProcessor.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = mock(ObjectProvider.class);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessRuntimeLifecycle(
                true,
                dispatcher,
                modelProcessor::hasActiveExecution,
                toolProcessor,
                daemonServerProvider,
                Duration.ofSeconds(-1)));
  }

  @Test
  void lifecyclePhaseIsMaxInt() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle = defaultLifecycle(true, dispatcher);
    assertEquals(Integer.MAX_VALUE, lifecycle.getPhase());
  }
}
