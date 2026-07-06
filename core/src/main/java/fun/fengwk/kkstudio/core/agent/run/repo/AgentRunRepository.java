package fun.fengwk.kkstudio.core.agent.run.repo;

import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author fengwk
 */
public interface AgentRunRepository {

  boolean add(AgentRun run);

  AgentRun getByRunId(String runId);

  List<AgentRun> listBySessionId(String sessionId);

  boolean existsActiveBySessionId(String sessionId);

  List<AgentRun> listStaleRuns(LocalDateTime threshold);

  boolean updateStatus(
      String runId, String expectedStatus, String status, LocalDateTime updateTime);

  boolean markFailedFromActive(String runId, String status, LocalDateTime updateTime);
}
