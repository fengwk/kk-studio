package fun.fengwk.kkstudio.harness.runtime.thread.command;

/**
 * 不可变 Thread mailbox command payload 的 sealed 类型族。
 *
 * <p>每个 payload 仅承载其 command type 自身拥有的字段；此边界不允许出现通用 settings map。
 */
public sealed interface ThreadCommandPayload
    permits UserMessageCommandPayload,
        CustomMessageCommandPayload,
        SetAgentCommandPayload,
        SetModelCommandPayload,
        SetActiveToolsCommandPayload,
        SetEnvironmentCommandPayload {

  /** 返回该 payload 所代表的 command type。 */
  ThreadCommandType type();
}
