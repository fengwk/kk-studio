package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.List;
import java.util.Objects;

/**
 * 纯分类的 Thread live/historical applicability 结果（非持久化，不接触 Store）。
 *
 * <p>由 {@link ThreadContextClassifier} 基于当前 Thread head 路径、本 Thread 当前 open Turn 的 ModelInvocation
 * 与仅在该 Model 结果恰好位于当前 Assistant head 时加载的 Tool siblings 计算。每个 kind 只携带调用方 锁定 /
 * 应用所需的最小不可变事实（Entry/Model/Tool 均为不可变记录，List 构造时防御性拷贝），不重新发现身份；不变量 被破坏的形状由分类器以 {@link
 * IllegalStateException} 拒绝，绝不会降级为业务 kind。
 *
 * <p>ThreadProcessor 与未来的 root HarnessRuntime 控制共用本结果决定下一步：terminal apply（MODEL_TERMINAL_PENDING /
 * TOOL_TERMINAL_PENDING）、Work-only 挂起（MODEL_ACTIVE / TOOL_ACTIVE）、continuation（CONTINUATION_DUE）或
 * 输入 / 静止（IDLE_OR_HISTORICAL）。
 */
public sealed interface ThreadContext
    permits ThreadContext.IdleOrHistorical,
        ThreadContext.ContinuationDue,
        ThreadContext.ModelActive,
        ThreadContext.ModelTerminalPending,
        ThreadContext.ToolActive,
        ThreadContext.ToolTerminalPending {

  /** 无 open Turn（且无需 continuation），或 open Turn 对本 Thread 没有可推进的 live Model/Tool。 */
  record IdleOrHistorical() implements ThreadContext {}

  /** 无 open Turn 且当前 head 为 {@code continueModel=true} 的 TURN_END：应立即启动 continuation。 */
  record ContinuationDue(Entry turnEnd) implements ThreadContext {

    public ContinuationDue {
      if (turnEnd == null
          || !(turnEnd.payload() instanceof TurnEndPayload end)
          || !end.continueModel()) {
        throw new IllegalArgumentException(
            "continuation due requires a continueModel=true TURN_END head entry");
      }
    }
  }

  /** 本 Thread 当前 open Turn 的 Model 非 terminal 且 head == basis：Work-only 挂起等待 Model 完成。 */
  record ModelActive(ModelInvocation model) implements ThreadContext {

    public ModelActive {
      model = Objects.requireNonNull(model, "model");
    }
  }

  /** 本 Thread 当前 open Turn 的 Model terminal、未挂结果且 head == basis：需锁 Model 并原子 apply 结果。 */
  record ModelTerminalPending(ModelInvocation model) implements ThreadContext {

    public ModelTerminalPending {
      model = Objects.requireNonNull(model, "model");
    }
  }

  /** Tool siblings 全部 terminal 且全部未挂结果：需锁 Model + siblings 并按 ordinal 原子 apply。 */
  record ToolTerminalPending(
      ModelInvocation model,
      Entry assistant,
      List<ToolCallMessageContent> calls,
      List<ToolInvocation> siblings)
      implements ThreadContext {

    public ToolTerminalPending {
      model = Objects.requireNonNull(model, "model");
      assistant = Objects.requireNonNull(assistant, "assistant");
      calls = List.copyOf(calls);
      siblings = List.copyOf(siblings);
    }
  }

  /** Tool siblings 全部未挂结果且至少一个非 terminal：Work-only 挂起等待全部 Tool 完成。 */
  record ToolActive(
      ModelInvocation model,
      Entry assistant,
      List<ToolCallMessageContent> calls,
      List<ToolInvocation> siblings)
      implements ThreadContext {

    public ToolActive {
      model = Objects.requireNonNull(model, "model");
      assistant = Objects.requireNonNull(assistant, "assistant");
      calls = List.copyOf(calls);
      siblings = List.copyOf(siblings);
    }
  }
}
