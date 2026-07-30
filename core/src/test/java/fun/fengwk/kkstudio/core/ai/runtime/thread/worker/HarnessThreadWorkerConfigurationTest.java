package fun.fengwk.kkstudio.core.ai.runtime.thread.worker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class HarnessThreadWorkerConfigurationTest {

  private final HarnessThreadWorkerConfiguration configuration =
      new HarnessThreadWorkerConfiguration();

  @Test
  void createsBoundedDaemonExecutorAndFinalReconciler() throws Exception {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setThreadWorkerConcurrency(2);
    ExecutorService executor = configuration.threadReconcileExecutor(properties);
    try {
      ThreadFacts reconcileThread =
          executor.submit(() -> new ThreadFacts(Thread.currentThread())).get(2, TimeUnit.SECONDS);
      assertTrue(reconcileThread.daemon());
      assertTrue(reconcileThread.name().startsWith("thread-reconcile"));

      ThreadReconciler reconciler =
          configuration.threadReconciler(
              mock(ThreadReconcileTransactions.class), properties, executor);
      assertNotNull(reconciler);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsInvalidWorkerConfiguration() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setThreadWorkerConcurrency(0);
    assertThrows(
        IllegalArgumentException.class, () -> configuration.threadReconcileExecutor(properties));

    properties.setThreadReconcilerMaxSteps(0);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              configuration.threadReconciler(
                  mock(ThreadReconcileTransactions.class), properties, executor));
    } finally {
      executor.shutdownNow();
    }
  }

  private record ThreadFacts(String name, boolean daemon) {
    private ThreadFacts(Thread thread) {
      this(thread.getName(), thread.isDaemon());
    }
  }
}
