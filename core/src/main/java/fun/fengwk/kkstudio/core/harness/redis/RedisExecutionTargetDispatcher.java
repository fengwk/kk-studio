package fun.fengwk.kkstudio.core.harness.redis;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.Objects;
import java.util.concurrent.Executor;

/** Routes one decoded activation target to its process-local execution entry point. */
public class RedisExecutionTargetDispatcher {

  private final ThreadKick threadKick;
  private final ModelWorker modelWorker;
  private final Executor modelWorkerScheduler;
  private final ToolWorker toolWorker;
  private final Executor toolWorkerScheduler;

  public RedisExecutionTargetDispatcher(
      ThreadKick threadKick,
      ModelWorker modelWorker,
      Executor modelWorkerScheduler,
      ToolWorker toolWorker,
      Executor toolWorkerScheduler) {
    this.threadKick = Objects.requireNonNull(threadKick, "threadKick");
    this.modelWorker = Objects.requireNonNull(modelWorker, "modelWorker");
    this.modelWorkerScheduler =
        Objects.requireNonNull(modelWorkerScheduler, "modelWorkerScheduler");
    this.toolWorker = Objects.requireNonNull(toolWorker, "toolWorker");
    this.toolWorkerScheduler = Objects.requireNonNull(toolWorkerScheduler, "toolWorkerScheduler");
  }

  /** Dispatches the target without performing Model or Tool external I/O on the caller's thread. */
  public void dispatch(ExecutionTarget target) {
    Objects.requireNonNull(target, "target");
    switch (target.kind()) {
      case THREAD -> {
        long threadId = target.id();
        threadKick.kick(threadId);
      }
      case MODEL_INVOCATION -> {
        long modelInvocationId = target.id();
        modelWorkerScheduler.execute(() -> modelWorker.dispatch(modelInvocationId));
      }
      case TOOL_INVOCATION -> {
        long toolInvocationId = target.id();
        toolWorkerScheduler.execute(() -> toolWorker.dispatch(toolInvocationId));
      }
    }
  }
}
