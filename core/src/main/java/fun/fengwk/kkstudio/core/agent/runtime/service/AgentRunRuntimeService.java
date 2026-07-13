package fun.fengwk.kkstudio.core.agent.runtime.service;

/**
 * @author fengwk
 */
public interface AgentRunRuntimeService {

  void scheduleQueuedRun(String runId, String sessionId, String content);
}
