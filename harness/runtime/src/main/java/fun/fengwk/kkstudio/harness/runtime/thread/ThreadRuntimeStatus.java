package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;

import java.util.List;
import java.util.Objects;

/**
 * Thread live context 的稳定对外状态投影。
 *
 * <p>{@link #QUEUED} 与 {@link #STOPPED} 是结合持久控制事实的状态扩展；本地上下文分类器投影 {@link #from(ThreadContext)}
 * 仅反映当前线程本地执行上下文，绝不返回这两种状态。
 */
public enum ThreadRuntimeStatus {
  /** 当前 Thread 没有活跃调用，也没有模型 continuation obligation。 */
  IDLE,

  /** 本地处于空闲上下文，但存在已排队命令等待启动新 turn（控制增强状态，local-only 的 {@code from} 绝不返回）。 */
  QUEUED,

  /** Thread 已被显式停止（{@code ThreadExecutionControl.STOPPED}），不再启动模型执行（控制增强状态，local-only 的 {@code from} 绝不返回）。 */
  STOPPED,

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

  /** 无待审批 sibling，且至少一个 sibling 等待用户回答冻结问卷（{@code ask_user}）。 */
  TOOL_WAITING_INPUT,

  /** 无待审批或待回答 sibling，且至少一个 sibling 正在运行。 */
  TOOL_RUNNING,

  /** 无待审批、待回答或运行 sibling，且至少一个 sibling 处于分派围栏。 */
  TOOL_DISPATCHING,

  /** 无待审批、待回答、运行或分派 sibling，且至少一个 sibling 已就绪。 */
  TOOL_READY;

  /**
   * 从已分类的 Thread context 派生状态。非法 active 形状属于不变量破坏，不做兼容降级。
   *
   * <p>注意：该方法仅作本地 context 投影，绝不返回 {@link #QUEUED} 或 {@link #STOPPED}。
   */
  public static ThreadRuntimeStatus from(ThreadContext context) {
    Objects.requireNonNull(context, "context");
    return switch (context) {
      case ThreadContext.IdleOrHistorical ignored -> IDLE;
      case ThreadContext.ContinuationDue ignored -> CONTINUATION_DUE;
      case ThreadContext.ModelActive active -> fromModelStatus(active.model().status());
      case ThreadContext.ModelTerminalPending ignored -> APPLYING;
      case ThreadContext.ToolActive active -> fromToolSiblings(active.siblings());
      case ThreadContext.ToolTerminalPending ignored -> APPLYING;
    };
  }

  /** 从模型调用状态派生运行时状态。非终态直接映射，终态或未知状态抛出 {@link IllegalStateException}。 */
  public static ThreadRuntimeStatus fromModelStatus(ModelInvocationStatus status) {
    Objects.requireNonNull(status, "status");
    return switch (status) {
      case READY -> MODEL_READY;
      case DISPATCHING -> MODEL_DISPATCHING;
      case RUNNING -> MODEL_RUNNING;
      default -> throw new IllegalStateException(
          "MODEL_ACTIVE context must contain a non-terminal model invocation");
    };
  }

  /**
   * 从工具兄弟调用列表派生运行时状态。
   *
   * <p>判定优先级依次为 WAITING_APPROVAL > RUNNING > DISPATCHING > READY。 若列表为空或全部为终态，抛出 {@link
   * IllegalStateException}。
   */
  public static ThreadRuntimeStatus fromToolSiblings(List<ToolInvocation> siblings) {
    Objects.requireNonNull(siblings, "siblings");
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.WAITING_APPROVAL) {
        return TOOL_WAITING_APPROVAL;
      }
    }
    for (ToolInvocation sibling : siblings) {
      if (sibling.status() == ToolInvocationStatus.WAITING_INPUT) {
        return TOOL_WAITING_INPUT;
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

  /** IDLE 与 STOPPED 不处于 processing 状态；其余按本地实际阶段投影。 */
  public boolean isProcessing() {
    return this != IDLE && this != STOPPED;
  }
}
