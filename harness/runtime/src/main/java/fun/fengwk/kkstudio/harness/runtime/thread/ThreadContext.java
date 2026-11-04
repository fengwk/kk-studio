package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState;

import java.util.List;
import java.util.Objects;

/**
 * 纯分类的 Thread live/historical applicability 结果（非持久化，不接触 Store）。
 *
 * <p>Driver 只暴露 {@link LockedDriverState} 语义视图；Runtime 不读取任何 Driver 私有类型。Tool siblings 的归属始终按公共
 * Thread/TurnStart/Assistant 字段校验。
 */
public sealed interface ThreadContext
    permits ThreadContext.IdleOrHistorical,
        ThreadContext.ContinuationDue,
        ThreadContext.GenerationActive,
        ThreadContext.GenerationTerminalPending,
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
  record GenerationActive(LockedDriverState driverState) implements ThreadContext {

    public GenerationActive {
      driverState = Objects.requireNonNull(driverState, "driverState");
    }
  }

  /** Driver generation terminal、未挂结果且 head == basis：需由 Driver 原子物化结果。 */
  record GenerationTerminalPending(LockedDriverState driverState) implements ThreadContext {

    public GenerationTerminalPending {
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

  /** 统一将纯上下文分类映射为公共 {@link ThreadLiveState}：Runtime 的活动分类不外泄为 API 类型。 */
  static ThreadLiveState liveStateOf(ThreadContext context) {
    return switch (context) {
      case IdleOrHistorical ignored -> ThreadLiveState.IDLE_OR_HISTORICAL;
      case ContinuationDue ignored -> ThreadLiveState.CONTINUATION_DUE;
      case GenerationActive ignored -> ThreadLiveState.DRIVER_ACTIVE;
      case GenerationTerminalPending ignored -> ThreadLiveState.DRIVER_RESPONSE_PENDING;
      case ToolActive ignored -> ThreadLiveState.TOOL_ACTIVE;
      case ToolTerminalPending ignored -> ThreadLiveState.TOOL_TERMINAL_PENDING;
    };
  }
}
