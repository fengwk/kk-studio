package fun.fengwk.kkstudio.core.agent.runtime.engine;

/**
 * @author fengwk
 */
public interface AgentRuntimeEngine {

    String newSession();

    String fork(String headEventId);

    /**
     * 提交用户请求并尝试触发 agent 执行。
     *
     * @return taskId，可用于追踪提交是否被消费。
     */
    String submit(AgentRequest agentRequest);

    /**
     * 显式中止当前 busy turn。
     *
     * @return true 表示成功写入 abort 边界并释放 session，false 表示 session 当前不可中止。
     */
    boolean abort(String sessionId, String reason);

}
