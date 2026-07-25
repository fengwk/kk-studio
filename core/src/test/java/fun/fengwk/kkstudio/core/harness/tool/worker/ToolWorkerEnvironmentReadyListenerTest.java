package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production READY bridge must leave the WebSocket stack via the tool-worker executor and still
 * dispatch when that executor rejects work.
 */
class ToolWorkerEnvironmentReadyListenerTest {

  @Test
  void schedulesDispatchOffCallerStack() {
    ToolWorker worker = mock(ToolWorker.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ToolWorker> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(worker);
    AtomicReference<Runnable> scheduled = new AtomicReference<>();
    Executor executor = scheduled::set;

    new ToolWorkerEnvironmentReadyListener(provider, executor).onEnvironmentReady("env-a");

    verify(worker, never()).dispatchNext(eq(ToolExecutionLocation.ENVIRONMENT), eq("env-a"));
    scheduled.get().run();
    verify(worker).dispatchNext(ToolExecutionLocation.ENVIRONMENT, "env-a");
  }

  @Test
  void fallsBackToDirectDispatchWhenExecutorRejects() {
    ToolWorker worker = mock(ToolWorker.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ToolWorker> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(worker);
    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("closed");
        };

    new ToolWorkerEnvironmentReadyListener(provider, rejecting).onEnvironmentReady("env-b");

    verify(worker).dispatchNext(ToolExecutionLocation.ENVIRONMENT, "env-b");
  }

  @Test
  void ignoresMissingWorkerAndDispatchFailures() {
    @SuppressWarnings("unchecked")
    ObjectProvider<ToolWorker> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);

    new ToolWorkerEnvironmentReadyListener(provider, Runnable::run).onEnvironmentReady("env-c");

    ToolWorker failing = mock(ToolWorker.class);
    when(provider.getIfAvailable()).thenReturn(failing);
    doThrow(new IllegalStateException("busy"))
        .when(failing)
        .dispatchNext(ToolExecutionLocation.ENVIRONMENT, "env-d");

    new ToolWorkerEnvironmentReadyListener(provider, Runnable::run).onEnvironmentReady("env-d");
  }
}
