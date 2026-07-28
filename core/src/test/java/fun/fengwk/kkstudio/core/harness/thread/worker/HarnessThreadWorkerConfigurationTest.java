package fun.fengwk.kkstudio.core.harness.thread.worker;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.thread.reconcile.ThreadReconcileMapper;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadActivationDispatcher;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class HarnessThreadWorkerConfigurationTest {

  private final HarnessThreadWorkerConfiguration configuration =
      new HarnessThreadWorkerConfiguration();

  @Test
  void createsBoundedDaemonExecutorsAndFinalDispatcher() throws Exception {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setThreadWorkerConcurrency(2);
    ExecutorService executor = configuration.threadReconcileExecutor(properties);
    ScheduledExecutorService scheduler = configuration.harnessWorkerScheduler();
    try {
      ThreadFacts reconcileThread =
          executor.submit(() -> new ThreadFacts(Thread.currentThread())).get(2, TimeUnit.SECONDS);
      ThreadFacts schedulerThread =
          scheduler
              .schedule(() -> new ThreadFacts(Thread.currentThread()), 0L, TimeUnit.MILLISECONDS)
              .get(2, TimeUnit.SECONDS);
      assertTrue(reconcileThread.daemon());
      assertTrue(reconcileThread.name().startsWith("thread-reconcile"));
      assertTrue(schedulerThread.daemon());
      assertTrue(schedulerThread.name().startsWith("harness-worker"));

      ThreadReconciler reconciler =
          configuration.threadReconciler(mock(ThreadReconcileTransactions.class), properties);
      assertInstanceOf(
          ThreadActivationDispatcher.class,
          configuration.threadKick(reconciler, executor, ignored -> {}));
    } finally {
      executor.shutdownNow();
      scheduler.shutdownNow();
    }
  }

  @Test
  void rejectsInvalidWorkerAndRecoveryConfiguration() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setThreadWorkerConcurrency(0);
    assertThrows(
        IllegalArgumentException.class, () -> configuration.threadReconcileExecutor(properties));

    properties.setThreadReconcilerMaxSteps(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> configuration.threadReconciler(mock(ThreadReconcileTransactions.class), properties));

    properties.setThreadRecoveryInterval(Duration.ZERO);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            configuration.threadRecoveryLifecycle(
                properties,
                mock(ThreadReconcileMapper.class),
                ignored -> {},
                mock(ScheduledExecutorService.class)));
    properties.setThreadRecoveryInterval(Duration.ofSeconds(1));
    properties.setThreadRecoveryBatchSize(0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            configuration.threadRecoveryLifecycle(
                properties,
                mock(ThreadReconcileMapper.class),
                ignored -> {},
                mock(ScheduledExecutorService.class)));
  }

  private record ThreadFacts(String name, boolean daemon) {
    private ThreadFacts(Thread thread) {
      this(thread.getName(), thread.isDaemon());
    }
  }
}
