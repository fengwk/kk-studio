package fun.fengwk.kkstudio.agent.tool.execution;

import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import fun.fengwk.kkstudio.agent.tool.Tool;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolRegistration;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolCallExecutor 负责单个工具调用的受控异步执行。
 *
 * @author fengwk
 */
public class ToolCallExecutor {

  private final ExecutorService executorService;
  private final AgentScheduler agentScheduler;

  public ToolCallExecutor(ExecutorService executorService, AgentScheduler agentScheduler) {
    this.executorService = requireNonNull(executorService, "executorService");
    this.agentScheduler = requireNonNull(agentScheduler, "agentScheduler");
  }

  public ToolExecutionHandle execute(
      ToolRegistration registration, ToolCallRequest request, ToolExecutionListener listener) {
    requireNonNull(registration, "registration");
    requireNonNull(request, "request");
    requireNonNull(listener, "listener");
    Tool tool = requireNonNull(registration.getTool(), "registration.tool");
    long timeoutSeconds = tool.timeoutSeconds();
    if (timeoutSeconds < 0) {
      throw new IllegalArgumentException(
          "tool timeoutSeconds must not be negative: " + registration.getName());
    }

    ManagedToolExecutionHandle managedHandle = new ManagedToolExecutionHandle();
    AtomicReference<ScheduledTask> timeoutTaskRef = new AtomicReference<>();
    GuardedToolExecutionHandler guardedHandler =
        new GuardedToolExecutionHandler(
            request, listener, managedHandle, () -> cancelTimeout(timeoutTaskRef));
    managedHandle.setCancelHook(guardedHandler::onRuntimeCancel);
    if (timeoutSeconds > 0) {
      ScheduledTask timeoutTask =
          agentScheduler.schedule(
              Duration.ofSeconds(timeoutSeconds), () -> guardedHandler.onTimeout(timeoutSeconds));
      timeoutTaskRef.set(timeoutTask);
      if (managedHandle.isCancelled()) {
        timeoutTask.cancel();
      }
    }

    try {
      Future<?> future =
          executorService.submit(() -> executeTool(tool, request, guardedHandler, managedHandle));
      managedHandle.bindFuture(future);
    } catch (Throwable error) {
      guardedHandler.onError(error);
    }
    return managedHandle;
  }

  private void executeTool(
      Tool tool,
      ToolCallRequest request,
      GuardedToolExecutionHandler guardedHandler,
      ManagedToolExecutionHandle managedHandle) {
    try {
      ToolExecutionHandle delegate = tool.asyncExecute(request, guardedHandler);
      if (delegate == null) {
        guardedHandler.onError(new IllegalStateException("tool asyncExecute returned null handle"));
        return;
      }
      managedHandle.bindDelegate(delegate);
    } catch (Throwable error) {
      guardedHandler.onError(error);
    }
  }

  private void cancelTimeout(AtomicReference<ScheduledTask> timeoutTaskRef) {
    ScheduledTask timeoutTask = timeoutTaskRef.getAndSet(null);
    if (timeoutTask != null) {
      timeoutTask.cancel();
    }
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
