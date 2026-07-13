package fun.fengwk.kkstudio.core.agent.run.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.run.repo.AgentRunRepository;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.run.service.converter.AgentRunConverter;
import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentRunServiceImpl implements AgentRunService {

  private static final String STATUS_QUEUED = "queued";
  private static final String STATUS_RUNNING = "running";
  private static final String STATUS_SUCCEEDED = "succeeded";
  private static final String STATUS_FAILED = "failed";

  private final AgentRunRepository agentRunRepository;
  private final AgentRunConverter agentRunConverter;
  private final AgentRunMutationFactory runMutationFactory;
  private final AgentRunGuard runGuard;

  @Override
  public AgentRunDTO createQueuedRun(String runId, String sessionId, String triggerEventId) {
    AgentRun run =
        runMutationFactory.newQueuedRun(
            runGuard.requireRunId(runId),
            runGuard.requireSessionId(sessionId),
            runGuard.requireTriggerEventId(triggerEventId),
            LocalDateTime.now());
    if (!agentRunRepository.add(run)) {
      throw new IllegalStateException("create queued run failed");
    }

    return agentRunConverter.convert(run);
  }

  @Override
  public AgentRunDTO getRun(String runId) {
    return agentRunConverter.convert(agentRunRepository.getByRunId(runGuard.requireRunId(runId)));
  }

  @Override
  public boolean markRunning(String runId) {
    return updateStatus(runId, STATUS_QUEUED, STATUS_RUNNING);
  }

  @Override
  public boolean markSucceeded(String runId) {
    return updateStatus(runId, STATUS_RUNNING, STATUS_SUCCEEDED);
  }

  @Override
  public boolean markFailed(String runId) {
    return agentRunRepository.markFailedFromActive(
        runGuard.requireRunId(runId), STATUS_FAILED, LocalDateTime.now());
  }

  @Override
  public List<AgentRunDTO> listRuns(String sessionId) {
    return agentRunRepository.listBySessionId(runGuard.requireSessionId(sessionId)).stream()
        .map(agentRunConverter::convert)
        .toList();
  }

  @Override
  public boolean hasActiveRun(String sessionId) {
    return agentRunRepository.existsActiveBySessionId(runGuard.requireSessionId(sessionId));
  }

  @Override
  public void deleteRunsBySessionId(String sessionId) {
    String normalizedSessionId = runGuard.requireSessionId(sessionId);
    if (agentRunRepository.existsActiveBySessionId(normalizedSessionId)) {
      throw new IllegalStateException("session has active run: " + normalizedSessionId);
    }
    agentRunRepository.deleteBySessionId(normalizedSessionId);
  }

  private boolean updateStatus(String runId, String expectedStatus, String status) {
    return agentRunRepository.updateStatus(
        runGuard.requireRunId(runId), expectedStatus, status, LocalDateTime.now());
  }
}
