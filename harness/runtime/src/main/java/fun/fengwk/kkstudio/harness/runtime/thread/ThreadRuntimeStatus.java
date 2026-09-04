package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;

import java.util.List;
import java.util.Objects;

/** Thread live context 的稳定对外状态投影。 */
public enum ThreadRuntimeStatus {
  /** 当前 Thread 没有活跃调用，也没有模型 continuation obligation。 */
  IDLE,

  /** 线程存在待推进的继续义务，等待调度触发新的执行。 */
  CONTINUATION_DUE,

  /** 已有 READY 模型调用，等待分派执行。 */
  MODEL_READY,

  /** 模型调用已进入持久化分派围栏，Gateway 接受结果尚未确认。 */
  MODEL_DISPATCHING,

  /** Gateway 已接受模型调用，Runtime 正处于受租约保护的执行阶段。 */
  MODEL_RUNNING,

  /** 当前调用已达终态，其结果尚待 ThreadProcessor 物化并推进上下文。 */
  APPLYING,

  /** 至少一个 sibling 等待审批，该状态优先于同批其他工具状态。 */
  TOOL_WAITING_APPROVAL,

  /** 无待审批 sibling，且至少一个 sibling 正在运行。 */
  TOOL_RUNNING,

  /** 无待审批或运行 sibling，且至少一个 sibling 处于分派围栏。 */
  TOOL_DISPATCHING,

  /** 无待审批、运行或分派 sibling，且至少一个 sibling 已就绪。 */
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
