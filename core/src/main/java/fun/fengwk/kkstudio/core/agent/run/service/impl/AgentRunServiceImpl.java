package fun.fengwk.kkstudio.core.agent.run.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextRunId;

import fun.fengwk.kkstudio.core.agent.run.repo.AgentRunRepository;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.run.service.converter.AgentRunConverter;
import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.run.repo.AgentRunRepository;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.run.service.converter.AgentRunConverter;
import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

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

  private final AgentSessionRepository agentSessionRepository;
  private final AgentRunRepository agentRunRepository;
  private final AgentRunConverter agentRunConverter;

  @Override
  public AgentRunDTO createQueuedRun(String runId, String sessionId, String triggerEventId) {
    validateCreateQueuedRunArgs(runId, sessionId, triggerEventId);
    requireSession(sessionId);

    LocalDateTime now = LocalDateTime.now();
    AgentRun run = new AgentRun();
    run.setId(nextRunId());
    run.setRunId(runId);
    run.setSessionId(sessionId);
    run.setTriggerEventId(triggerEventId);
    run.setStatus(STATUS_QUEUED);
    run.setCreateTime(now);
    run.setUpdateTime(now);
    if (!agentRunRepository.add(run)) {
      throw new IllegalStateException("create queued run failed");
    }

    return agentRunConverter.convert(run);
  }

  @Override
  public AgentRunDTO getRun(String runId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    return agentRunConverter.convert(agentRunRepository.getByRunId(runId));
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
    return updateStatus(runId, STATUS_RUNNING, STATUS_FAILED);
  }

  @Override
  public List<AgentRunDTO> listRuns(String sessionId) {
    requireSession(sessionId);
    return agentRunRepository.listBySessionId(sessionId).stream()
        .map(agentRunConverter::convert)
        .collect(Collectors.toList());
  }

  @Override
  public boolean hasActiveRun(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    return agentRunRepository.existsActiveBySessionId(sessionId);
  }

  private void validateCreateQueuedRunArgs(String runId, String sessionId, String triggerEventId) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (triggerEventId == null || triggerEventId.isBlank()) {
      throw new IllegalArgumentException("triggerEventId must not be blank");
    }
  }

  private void requireSession(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (agentSessionRepository.getBySessionId(sessionId) == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
  }

  private boolean updateStatus(String runId, String expectedStatus, String status) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    return agentRunRepository.updateStatus(runId, expectedStatus, status, LocalDateTime.now());
  }
}
