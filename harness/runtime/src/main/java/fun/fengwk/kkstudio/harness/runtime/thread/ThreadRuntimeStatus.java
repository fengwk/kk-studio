package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;

import java.util.List;
import java.util.Objects;

/** Thread live context 的稳定对外状态投影。 */
public enum ThreadRuntimeStatus {
  IDLE,
  CONTINUATION_DUE,
  MODEL_READY,
  MODEL_DISPATCHING,
  MODEL_RUNNING,
  APPLYING,
  TOOL_WAITING_APPROVAL,
  TOOL_RUNNING,
  TOOL_DISPATCHING,
  TOOL_READY;

  /** 从已分类的 Thread context 派生状态。非法 active 形状属于不变量破坏，不做兼容降级。 */
  public static ThreadRuntimeStatus from(ThreadContext context) {
    Objects.requireNonNull(context, "context");
    return switch (context) {
      case ThreadContext.IdleOrHistorical ignored -> IDLE;
      case ThreadContext.ContinuationDue ignored -> CONTINUATION_DUE;
      case ThreadContext.ModelActive active -> fromModel(active.model().status());
      case ThreadContext.ModelTerminalPending ignored -> APPLYING;
      case ThreadContext.ToolActive active -> fromTools(active.siblings());
      case ThreadContext.ToolTerminalPending ignored -> APPLYING;
    };
  }

  /** 只有完全静止的 IDLE 不处于 processing 状态。 */
  public boolean isProcessing() {
    return this != IDLE;
  }

  private static ThreadRuntimeStatus fromModel(ModelInvocationStatus status) {
    return switch (status) {
      case READY -> MODEL_READY;
      case DISPATCHING -> MODEL_DISPATCHING;
      case RUNNING -> MODEL_RUNNING;
      default -> throw new IllegalStateException(
          "MODEL_ACTIVE context must contain a non-terminal model invocation");
    };
  }

  private static ThreadRuntimeStatus fromTools(List<ToolInvocation> siblings) {
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.WAITING_APPROVAL) {
        return TOOL_WAITING_APPROVAL;
      }
    }
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.RUNNING) {
        return TOOL_RUNNING;
      }
    }
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.DISPATCHING) {
        return TOOL_DISPATCHING;
      }
    }
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.READY) {
        return TOOL_READY;
      }
    }
    throw new IllegalStateException(
        "TOOL_ACTIVE context must contain at least one non-terminal tool invocation");
  }
}
