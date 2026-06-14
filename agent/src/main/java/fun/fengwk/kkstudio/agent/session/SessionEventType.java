package fun.fengwk.kkstudio.agent.session;

/**
 * SessionEventType 定义持久化事件的语义类型。
 *
 * 语义说明：
 * - 本枚举描述 session tree 中的持久化事件语义。
 * - 每个类型都对应一类可重放的会话事件。
 *
 * @author fengwk
 */
public enum SessionEventType {

    /**
     * agent 信息设置事件。
     *
     * 语义：表示后续运行所依赖的 agent 信息事实。
     */
    set_agent_info,

    /**
     * model 信息设置事件。
     *
     * 语义：表示后续运行所依赖的模型选择事实。
     */
    set_model_info,

    /**
     * assistant 调用开始事件。
     *
     * 语义：表示一次 assistant 调用开始。
     */
    assistant_start,

    /**
     * assistant 增量输出事件。
     *
     * 语义：表示一次 assistant 调用过程中的增量输出。
     */
    assistant_delta,

    /**
     * assistant 调用结束事件。
     *
     * 语义：表示一次 assistant 调用结束。
     */
    assistant_end,

    /**
     * 工具调用开始事件。
     *
     * 语义：表示一个 tool call 开始执行。
     */
    tool_start,

    /**
     * 工具增量输出事件。
     *
     * 语义：表示一个 tool call 过程中的增量输出。
     */
    tool_delta,

    /**
     * 工具调用结束事件。
     *
     * 语义：表示一个 tool call 正常结束。
     */
    tool_end,

    /**
     * 运行失败事件。
     *
     * 语义：表示一次可感知、可恢复的失败事实。
     */
    error,

    /**
     * 用户取消事件。
     *
     * 语义：表示用户主动中断当前工作链路。
     */
    abort,

    ;


}
