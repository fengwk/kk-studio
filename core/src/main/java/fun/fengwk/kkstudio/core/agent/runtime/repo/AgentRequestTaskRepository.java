package fun.fengwk.kkstudio.core.agent.runtime.repo;

import fun.fengwk.kkstudio.core.agent.runtime.engine.AgentRequestTask;

import java.util.List;

/**
 * @author fengwk
 */
public interface AgentRequestTaskRepository {

    String generateTaskId();

    void add(AgentRequestTask agentRequestTask);

    List<AgentRequestTask> listBySessionId(String sessionId);

    boolean consumeAll(List<String> taskIdList);

}
