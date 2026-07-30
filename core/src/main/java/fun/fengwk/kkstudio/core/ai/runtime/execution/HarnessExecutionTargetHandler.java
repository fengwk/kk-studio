package fun.fengwk.kkstudio.core.ai.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.Objects;

/**
 * Routes one PostgreSQL durable target snapshot to its process-local Runtime entry point.
 *
 * <p>Thread reconciliation is submitted asynchronously so the single execution-target drain never
 * waits for a multi-step reconcile pass. Model and Tool dispatch synchronously claim their durable
 * row, then their callback-driven workers own all external I/O. Every target-specific transaction
 * re-locks and validates the target row, so a stale snapshot is harmless.
 */
public final class HarnessExecutionTargetHandler implements ExecutionTargetHandler {

  private final ThreadReconciler threadReconciler;
  private final ModelWorker modelWorker;
  private final ToolWorker toolWorker;

  public HarnessExecutionTargetHandler(
      ThreadReconciler threadReconciler, ModelWorker modelWorker, ToolWorker toolWorker) {
    this.threadReconciler = Objects.requireNonNull(threadReconciler, "threadReconciler");
    this.modelWorker = Objects.requireNonNull(modelWorker, "modelWorker");
    this.toolWorker = Objects.requireNonNull(toolWorker, "toolWorker");
  }

  @Override
  public boolean handle(ExecutionTargetRow row) {
    Objects.requireNonNull(row, "row");
    return switch (row.targetKind()) {
      case THREAD -> {
        threadReconciler.activate(row.targetId());
        yield true;
      }
      case MODEL_INVOCATION -> modelWorker.activate(row.targetId());
      case TOOL_INVOCATION -> toolWorker.dispatch(row.targetId());
    };
  }
}
