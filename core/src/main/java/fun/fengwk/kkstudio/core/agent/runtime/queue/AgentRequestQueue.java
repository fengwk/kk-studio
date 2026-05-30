package fun.fengwk.kkstudio.core.agent.runtime.queue;

import fun.fengwk.kkstudio.core.agent.runtime.engine.AgentRequest;

import java.util.List;

/**
 * @author fengwk
 */
public interface AgentRequestQueue {

    void submit(AgentRequest request);

    List<AgentRequest> pollAll(String sessionId);

}
