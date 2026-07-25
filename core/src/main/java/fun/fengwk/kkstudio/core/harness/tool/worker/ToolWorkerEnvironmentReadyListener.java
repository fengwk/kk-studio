package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Production READY bridge: schedules {@link ToolWorker#dispatchNext} off the WebSocket receive
 * stack using the tool-worker executor, with direct fallback when the executor rejects work.
 *
 * <p>{@link ToolWorker} is resolved lazily so Gateway construction does not create a bean cycle.
 */
final class ToolWorkerEnvironmentReadyListener implements EnvironmentReadyListener {

  private final ObjectProvider<ToolWorker> toolWorker;
  private final Executor readyDispatchExecutor;

  ToolWorkerEnvironmentReadyListener(
      ObjectProvider<ToolWorker> toolWorker, Executor readyDispatchExecutor) {
    this.toolWorker = Objects.requireNonNull(toolWorker, "toolWorker");
    this.readyDispatchExecutor =
        Objects.requireNonNull(readyDispatchExecutor, "readyDispatchExecutor");
  }

  @Override
  public void onEnvironmentReady(String environmentName) {
    try {
      readyDispatchExecutor.execute(() -> dispatchSafely(environmentName));
    } catch (RuntimeException rejected) {
      dispatchSafely(environmentName);
    }
  }

  private void dispatchSafely(String environmentName) {
    try {
      ToolWorker worker = toolWorker.getIfAvailable();
      if (worker != null) {
        worker.dispatchNext(ToolExecutionLocation.ENVIRONMENT, environmentName);
      }
    } catch (RuntimeException ignored) {
      // Durable polling remains authoritative when the routing hint fails.
    }
  }
}
