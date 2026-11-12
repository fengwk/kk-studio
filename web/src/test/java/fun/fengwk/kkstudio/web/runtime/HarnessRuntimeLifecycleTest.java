package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionDrain;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcher;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** {@link HarnessRuntimeLifecycle} 的 dispatcher 启停、单一停机 deadline、失败回滚与 workers 开关语义。 */
class HarnessRuntimeLifecycleTest {

  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

  private HarnessRuntimeLifecycle defaultLifecycle(
      boolean workersEnabled,
      HarnessWorkDispatcher dispatcher,
      LocalExecutionLifecycle executionLifecycle,
      ToolProcessor toolProcessor,
      ObjectProvider<EnvironmentDaemonServer> daemonServerProvider,
      Duration drainTimeout) {
    when(executionLifecycle.drain(any())).thenReturn(new LocalExecutionDrain(List.of(), false));
    return new HarnessRuntimeLifecycle(
        workersEnabled,
        dispatcher,
        executionLifecycle,
        toolProcessor,
        daemonServerProvider,
        drainTimeout,
        () -> LEASE_DURATION);
  }

  @Test
  void startsAndStopsDispatcher() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);

    lifecycle.start();
    lifecycle.stop();

    verify(executionLifecycle).start();
    verify(dispatcher).start();
    verify(dispatcher).stop();
    verify(executionLifecycle, times(2)).drain(any());
    verify(executionLifecycle).close();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startFailureStopsDispatcherAndRethrows() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    doThrow(new IllegalStateException("dispatcher failed to start")).when(dispatcher).start();
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);

    assertThrows(IllegalStateException.class, lifecycle::start);

    verify(executionLifecycle).start();
    verify(dispatcher).start();
    verify(dispatcher).stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void startRollbackWithStopFailuresPreservesFirstExceptionAndRetryIsNotSkipped() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    IllegalStateException startFailure = new IllegalStateException("dispatcher failed to start");
    doThrow(startFailure).when(dispatcher).start();
    doThrow(new IllegalStateException("dispatcher stop failed")).when(dispatcher).stop();
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);

    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::start);
    assertSame(startFailure, thrown);
    verify(dispatcher).stop();
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals("dispatcher stop failed", thrown.getSuppressed()[0].getMessage());
    assertFalse(lifecycle.isRunning());

    doNothing().when(dispatcher).start();
    lifecycle.start();
    verify(dispatcher, times(2)).start();
    verify(executionLifecycle, times(1)).start();
    assertTrue(lifecycle.isRunning());
  }

  @Test
  void stopFailureKeepsRunningSoCleanupCanBeRetried() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    doThrow(new IllegalStateException("dispatcher stop failed"))
        .doNothing()
        .when(dispatcher)
        .stop();
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);

    lifecycle.start();
    IllegalStateException thrown = assertThrows(IllegalStateException.class, lifecycle::stop);

    assertEquals("dispatcher stop failed", thrown.getMessage());
    verify(dispatcher).stop();
    assertTrue(lifecycle.isRunning());
    verify(executionLifecycle, never()).drain(any());

    lifecycle.stop();
    verify(dispatcher, times(2)).stop();
    verify(executionLifecycle, times(2)).drain(any());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void stopCallbackRunsEvenWhenStopFailsAndKeepsRunning() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    doThrow(new IllegalStateException("dispatcher stop failed")).when(dispatcher).stop();
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);
    AtomicBoolean callbackRan = new AtomicBoolean();

    lifecycle.start();
    assertThrows(IllegalStateException.class, () -> lifecycle.stop(() -> callbackRan.set(true)));

    assertTrue(callbackRan.get(), "callback 必须无条件执行");
    assertTrue(lifecycle.isRunning(), "停止未确认时必须保持 running");
  }

  @Test
  void workersDisabledRecoversJournalWithoutStartingDispatcher() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            false,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);

    lifecycle.start();

    verify(executionLifecycle).start();
    verifyNoInteractions(dispatcher);
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void repeatedStartAndStopAreIdempotent() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);

    lifecycle.start();
    lifecycle.start();
    lifecycle.stop();
    lifecycle.stop();

    verify(executionLifecycle, times(1)).start();
    verify(dispatcher, times(1)).start();
    verify(dispatcher, times(1)).stop();
    verify(executionLifecycle, times(1)).close();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void stopCallbackRunsEvenWhenAlreadyStopped() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);
    AtomicBoolean callbackRan = new AtomicBoolean();

    lifecycle.stop(() -> callbackRan.set(true));

    assertTrue(callbackRan.get());
    verifyNoInteractions(dispatcher);
    verifyNoInteractions(executionLifecycle);
  }

  @Test
  void normalShutdownSequenceOrchestratesAllSteps() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider =
        provider(mock(EnvironmentDaemonServer.class));
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            toolProcessor,
            daemonServerProvider,
            DRAIN_TIMEOUT);

    lifecycle.start();
    lifecycle.stop();

    InOrder inOrder = inOrder(dispatcher, executionLifecycle, daemonServerProvider, toolProcessor);
    inOrder.verify(dispatcher).stop();
    inOrder.verify(executionLifecycle).drain(DRAIN_TIMEOUT);
    inOrder.verify(daemonServerProvider).ifAvailable(any());
    inOrder.verify(toolProcessor).drain(any());
    inOrder.verify(executionLifecycle).drain(any());
    inOrder.verify(executionLifecycle).close();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void blockingRemoteCleanupStaysBoundedAndStillClosesPhysicalLifecycle() throws Exception {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    EnvironmentDaemonServer daemonServer = mock(EnvironmentDaemonServer.class);
    when(executionLifecycle.drain(any())).thenReturn(new LocalExecutionDrain(List.of(), false));

    CountDownLatch daemonEntered = new CountDownLatch(1);
    CountDownLatch daemonRelease = new CountDownLatch(1);
    CountDownLatch toolEntered = new CountDownLatch(1);
    CountDownLatch toolRelease = new CountDownLatch(1);
    AtomicReference<Thread> cleanupThread = new AtomicReference<>();
    doAnswer(
            invocation -> {
              cleanupThread.compareAndSet(null, Thread.currentThread());
              daemonEntered.countDown();
              awaitIgnoringInterrupt(daemonRelease);
              return null;
            })
        .when(daemonServer)
        .drain();
    doAnswer(
            invocation -> {
              cleanupThread.compareAndSet(null, Thread.currentThread());
              toolEntered.countDown();
              awaitIgnoringInterrupt(toolRelease);
              return null;
            })
        .when(toolProcessor)
        .drain(any());

    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            toolProcessor,
            provider(daemonServer),
            Duration.ZERO,
            () -> Duration.ofMillis(200));
    lifecycle.start();

    Thread cleanup = null;
    try {
      long startedAt = System.nanoTime();
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () -> {
            lifecycle.stop();
          });
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
      assertTrue(
          elapsed.compareTo(Duration.ofSeconds(2)) < 0,
          "stop must return without waiting for blocking remote cleanup: " + elapsed);
      assertTrue(daemonEntered.await(5, TimeUnit.SECONDS), "daemon cleanup must start");
      assertEquals(
          1, toolEntered.getCount(), "daemon cleanup must still be blocking tool handover");
      verify(executionLifecycle, times(2)).drain(any());
      verify(executionLifecycle).close();

      daemonRelease.countDown();
      assertTrue(
          toolEntered.await(5, TimeUnit.SECONDS), "tool handover must start after daemon drain");
    } finally {
      daemonRelease.countDown();
      toolRelease.countDown();
      cleanup = cleanupThread.get();
      if (cleanup != null) {
        cleanup.join(5_000);
      }
    }

    assertNotNull(cleanup, "background cleanup must execute on its own thread");
    assertFalse(cleanup.isAlive(), "background cleanup must finish after the test releases it");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void localFailureNeverSkipsClosingThePhysicalLifecycle(int failingStage) {
    // 两次 drain 与 close 的任何本地故障都须上报，且不能绕过目录锁保留的 close。
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle physical = mock(LocalExecutionLifecycle.class);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true, dispatcher, physical, mock(ToolProcessor.class), provider(null), Duration.ZERO);
    IllegalStateException failure = new IllegalStateException("local lifecycle failure");
    AtomicInteger drains = new AtomicInteger();
    when(physical.drain(any()))
        .thenAnswer(
            invocation -> {
              if (drains.incrementAndGet() == failingStage) {
                throw failure;
              }
              return new LocalExecutionDrain(List.of(), false);
            });
    if (failingStage == 3) {
      doThrow(failure).when(physical).close();
    }
    lifecycle.start();

    assertSame(failure, assertThrows(IllegalStateException.class, lifecycle::stop));
    verify(physical, times(2)).drain(any());
    verify(physical).close();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void remoteCleanupFailureIsReportedAfterPhysicalLifecycleClose() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    EnvironmentDaemonServer daemonServer = mock(EnvironmentDaemonServer.class);
    when(executionLifecycle.drain(any())).thenReturn(new LocalExecutionDrain(List.of(), false));
    IllegalStateException cleanupFailure = new IllegalStateException("daemon drain failed");
    doThrow(cleanupFailure).when(daemonServer).drain();
    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            toolProcessor,
            provider(daemonServer),
            DRAIN_TIMEOUT,
            () -> LEASE_DURATION);

    lifecycle.start();

    assertSame(cleanupFailure, assertThrows(IllegalStateException.class, lifecycle::stop));
    verify(executionLifecycle).close();
    verify(toolProcessor, never()).drain(any());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void singleDeadlineCapsRemainingBudgetToTotal() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true, dispatcher, executionLifecycle, toolProcessor, provider(null), DRAIN_TIMEOUT);

    lifecycle.start();
    lifecycle.stop();

    ArgumentCaptor<Duration> lifecycleBudgets = ArgumentCaptor.forClass(Duration.class);
    verify(executionLifecycle, times(2)).drain(lifecycleBudgets.capture());
    assertEquals(DRAIN_TIMEOUT, lifecycleBudgets.getAllValues().get(0));

    Duration totalBudget = DRAIN_TIMEOUT.plus(LEASE_DURATION);
    Duration remainingBudget = lifecycleBudgets.getAllValues().get(1);
    assertTrue(
        remainingBudget.compareTo(totalBudget) <= 0,
        "remaining lifecycle drain must stay inside the total budget: " + remainingBudget);

    ArgumentCaptor<Duration> toolBudget = ArgumentCaptor.forClass(Duration.class);
    verify(toolProcessor).drain(toolBudget.capture());
    assertTrue(
        toolBudget.getValue().compareTo(totalBudget) <= 0,
        "tool handover must stay inside the same total budget: " + toolBudget.getValue());
  }

  @Test
  void unconfiguredEnvironmentDaemonServerSkipsServerDrain() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = provider(null);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            toolProcessor,
            daemonServerProvider,
            DRAIN_TIMEOUT);

    lifecycle.start();
    lifecycle.stop();

    verify(dispatcher).stop();
    verify(toolProcessor).drain(any());
    verify(daemonServerProvider).ifAvailable(any());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void drainTimeoutZeroDoesNotWaitAndStillUsesOneTotalBudget() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = provider(null);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            toolProcessor,
            daemonServerProvider,
            Duration.ZERO);

    lifecycle.start();
    lifecycle.stop();

    verify(dispatcher).stop();
    ArgumentCaptor<Duration> lifecycleBudgets = ArgumentCaptor.forClass(Duration.class);
    verify(executionLifecycle, times(2)).drain(lifecycleBudgets.capture());
    assertEquals(Duration.ZERO, lifecycleBudgets.getAllValues().get(0));
    assertTrue(lifecycleBudgets.getAllValues().get(1).compareTo(LEASE_DURATION) <= 0);
    verify(toolProcessor).drain(any());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void negativeDrainTimeoutAndCurrentLeaseDurationThrow() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    LocalExecutionLifecycle executionLifecycle = mock(LocalExecutionLifecycle.class);
    ToolProcessor toolProcessor = mock(ToolProcessor.class);
    ObjectProvider<EnvironmentDaemonServer> daemonServerProvider = provider(null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessRuntimeLifecycle(
                true,
                dispatcher,
                executionLifecycle,
                toolProcessor,
                daemonServerProvider,
                Duration.ofSeconds(-1),
                () -> LEASE_DURATION));
    HarnessRuntimeLifecycle lifecycle =
        new HarnessRuntimeLifecycle(
            true,
            dispatcher,
            executionLifecycle,
            toolProcessor,
            daemonServerProvider,
            DRAIN_TIMEOUT,
            () -> Duration.ofSeconds(-1));
    lifecycle.start();
    assertThrows(IllegalArgumentException.class, lifecycle::stop);
  }

  @Test
  void lifecyclePhaseIsMaxInt() {
    HarnessWorkDispatcher dispatcher = mock(HarnessWorkDispatcher.class);
    HarnessRuntimeLifecycle lifecycle =
        defaultLifecycle(
            true,
            dispatcher,
            mock(LocalExecutionLifecycle.class),
            mock(ToolProcessor.class),
            provider(null),
            DRAIN_TIMEOUT);
    assertEquals(Integer.MAX_VALUE, lifecycle.getPhase());
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<EnvironmentDaemonServer> provider(EnvironmentDaemonServer server) {
    ObjectProvider<EnvironmentDaemonServer> provider = mock(ObjectProvider.class);
    doAnswer(
            invocation -> {
              Consumer<EnvironmentDaemonServer> consumer = invocation.getArgument(0);
              if (server != null) {
                consumer.accept(server);
              }
              return null;
            })
        .when(provider)
        .ifAvailable(any());
    return provider;
  }

  /** 模拟忽略中断的阻塞 JDBC 清理；只有测试显式 release 才继续。 */
  private static void awaitIgnoringInterrupt(CountDownLatch latch) {
    while (true) {
      try {
        latch.await();
        return;
      } catch (InterruptedException ignored) {
        // 故意保留阻塞，验证 stop 不依赖后台清理响应中断。
      }
    }
  }
}
