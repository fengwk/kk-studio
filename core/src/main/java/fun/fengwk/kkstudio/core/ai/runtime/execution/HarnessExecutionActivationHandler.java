package fun.fengwk.kkstudio.core.ai.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelWorker;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconciler;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorker;

import java.util.Objects;

/** 将一条持久化激活路由到进程内 Runtime 入口。 */
public final class HarnessExecutionActivationHandler implements ExecutionActivationHandler {

  private final ThreadReconciler threadReconciler;
  private final ModelWorker modelWorker;
  private final ToolWorker toolWorker;

  public HarnessExecutionActivationHandler(
      ThreadReconciler threadReconciler, ModelWorker modelWorker, ToolWorker toolWorker) {
    this.threadReconciler = Objects.requireNonNull(threadReconciler, "threadReconciler");
    this.modelWorker = Objects.requireNonNull(modelWorker, "modelWorker");
    this.toolWorker = Objects.requireNonNull(toolWorker, "toolWorker");
  }

  @Override
  public boolean handle(ExecutionActivation activation) {
    Objects.requireNonNull(activation, "activation");
    return switch (activation.targetKind()) {
      case THREAD -> {
        threadReconciler.activate(activation.targetId());
        yield true;
      }
      case MODEL_INVOCATION -> modelWorker.activate(activation.targetId());
      case TOOL_INVOCATION -> toolWorker.dispatch(activation.targetId());
    };
  }
}
