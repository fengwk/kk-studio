package fun.fengwk.kkstudio.core.agent.session.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionUpdateDTO;

/** 统一处理会话服务的入参规范化与实体解析。 */
@AllArgsConstructor
@Component
final class AgentSessionRequestSupport {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentSessionRepository agentSessionRepository;

  AgentDefinition requireAgent(AgentSessionCreateDTO createDTO) {
    String agentName = requireCreateAgentName(createDTO);
    AgentDefinition agent = agentDefinitionRepository.getByName(agentName);
    if (agent == null) {
      throw new IllegalArgumentException("agent not found: " + agentName);
    }
    return agent;
  }

  AgentSession requireSession(String sessionId) {
    String normalizedSessionId = requireNonBlank(sessionId, "sessionId must not be blank");
    AgentSession session = agentSessionRepository.getBySessionId(normalizedSessionId);
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + normalizedSessionId);
    }
    return session;
  }

  String normalizeTitle(AgentSessionUpdateDTO updateDTO) {
    if (updateDTO == null) {
      throw new IllegalArgumentException("updateDTO must not be null");
    }
    return normalizeBlankToNull(updateDTO.getTitle());
  }

  String requireMessageContent(AgentSessionMessageCreateDTO createDTO) {
    if (createDTO == null || createDTO.getContent() == null || createDTO.getContent().isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    return createDTO.getContent();
  }

  String resolveHeadEventId(AgentSession session, String headEventId) {
    String normalizedHeadEventId = normalizeBlankToNull(headEventId);
    return normalizedHeadEventId != null ? normalizedHeadEventId : session.getCurrentHeadEventId();
  }

  private String requireCreateAgentName(AgentSessionCreateDTO createDTO) {
    if (createDTO == null) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
    return requireNonBlank(createDTO.getAgentName(), "agentName must not be blank");
  }

  private String requireNonBlank(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private String normalizeBlankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
