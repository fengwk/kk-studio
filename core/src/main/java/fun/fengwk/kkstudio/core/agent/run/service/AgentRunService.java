package fun.fengwk.kkstudio.core.agent.run.service;

import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import java.util.List;

import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import java.util.List;

/**
 * @author fengwk
 */
public interface AgentRunService {

  AgentRunDTO createQueuedRun(String runId, String sessionId, String triggerEventId);

  AgentRunDTO getRun(String runId);

  boolean markRunning(String runId);

  boolean markSucceeded(String runId);

  boolean markFailed(String runId);

  List<AgentRunDTO> listRuns(String sessionId);

  boolean hasActiveRun(String sessionId);
}
