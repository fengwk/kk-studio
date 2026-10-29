package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.List;
import java.util.Objects;

/**
 * 纯分类的 Thread live/historical applicability 结果（非持久化，不接触 Store）。
 *
 * <p>Driver 只暴露 {@link LockedDriverState} 语义视图；Runtime 不读取 ModelInvocation 或任何 Driver 私有类型。 Tool
 * siblings 的归属始终按公共 Thread/TurnStart/Assistant 字段校验。
 */
public sealed interface ThreadContext
    permits ThreadContext.IdleOrHistorical,
        ThreadContext.ContinuationDue,
        ThreadContext.ModelActive,
        ThreadContext.ModelTerminalPending,
        ThreadContext.ToolActive,
        ThreadContext.ToolTerminalPending {

  /** 无 open Turn（且无需 continuation），或 open Turn 对本 Thread 没有可推进的 live Driver/Tool。 */
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

  /** 本 Thread 当前 open Turn 的 Driver generation 非 terminal 且 head == basis：等待执行完成。 */
  record ModelActive(LockedDriverState driverState) implements ThreadContext {

    public ModelActive {
      driverState = Objects.requireNonNull(driverState, "driverState");
    }
  }

  /** Driver generation terminal、未挂结果且 head == basis：需由 Driver 原子物化结果。 */
  record ModelTerminalPending(LockedDriverState driverState) implements ThreadContext {

    public ModelTerminalPending {
      driverState = Objects.requireNonNull(driverState, "driverState");
    }
  }

  /** Tool siblings 全部 terminal 且 outcome 尚未物化：需锁 siblings 并按 callIndex 原子 apply；不要求 Driver 状态。 */
  record ToolTerminalPending(
      Entry assistant, List<ToolCallMessageContent> calls, List<ToolInvocation> siblings)
      implements ThreadContext {

    public ToolTerminalPending {
      assistant = Objects.requireNonNull(assistant, "assistant");
      calls = List.copyOf(calls);
      siblings = List.copyOf(siblings);
    }
  }

  /** Tool siblings 存在即 outcome 尚未物化，且至少一个非 terminal：等待全部 Tool 完成；不要求 Driver 状态。 */
  record ToolActive(
      Entry assistant, List<ToolCallMessageContent> calls, List<ToolInvocation> siblings)
      implements ThreadContext {

    public ToolActive {
      assistant = Objects.requireNonNull(assistant, "assistant");
      calls = List.copyOf(calls);
      siblings = List.copyOf(siblings);
    }
  }
}
