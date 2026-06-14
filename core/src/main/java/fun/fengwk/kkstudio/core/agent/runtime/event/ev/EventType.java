package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

/**
 * @author fengwk
 */
public enum EventType {

    /** 用户提交事件。当前主链路使用 MessageStartEvent 表达用户消息，该类型保留给审计事件。 */
    user_submit,
//    user_abort,

    /** 配置变更事件。 */
    config_change,

    // agent 表示一轮任务执行
    agent_start,
    agent_end,

    error,
    abort,

    // turn 表示一次模型调用到模型结果返回；如果包含 tool call，则到工具调用全部执行完成为止。
    turn_start,
    turn_end,

    message_start,
    message_delta,
    message_tool_call_end,
    message_end,

    tool_call_start,
    tool_call_delta,
    tool_call_end,

    ;

}
