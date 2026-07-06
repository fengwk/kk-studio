package fun.fengwk.kkstudio.core.agent.run.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;

/** 统一处理 agent run 服务的入参校验与会话存在性校验。 */
@AllArgsConstructor
@Component
final class AgentRunGuard {

  private final AgentSessionRepository agentSessionRepository;

  String requireRunId(String runId) {
    return requireNonBlank(runId, "runId must not be blank");
  }

  String requireSessionId(String sessionId) {
    String normalizedSessionId = requireNonBlank(sessionId, "sessionId must not be blank");
    if (agentSessionRepository.getBySessionId(normalizedSessionId) == null) {
      throw new IllegalArgumentException("session not found: " + normalizedSessionId);
    }
    return normalizedSessionId;
  }

  String requireTriggerEventId(String triggerEventId) {
    return requireNonBlank(triggerEventId, "triggerEventId must not be blank");
  }

  private String requireNonBlank(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }
}
