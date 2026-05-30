package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

/**
 * @author fengwk
 */
public enum EventType {

//    user_submit,
//    user_abort,

//    config_change,

    // agent 表示一轮任务执行
//    agent_start,
//    agent_end,

    error,
    abort,

    // turn 表示一轮模型请求到所有工具调用执行完成
    turn_start,
    turn_end,

    message_start,
    message_delta,
    message_end,

    tool_call_start,
    tool_call_delta,
    tool_call_end,

    ;

}
