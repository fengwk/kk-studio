package fun.fengwk.kkstudio.core.agent.runtime.engine;

/**
 * @author fengwk
 */
public interface AgentRuntimeEngine {

    String newSession();

    String fork(String headEventId);

    void submit(AgentRequest agentRequest);

}
