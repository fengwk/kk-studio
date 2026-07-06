package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;

/** 统一处理 embedded runtime 装载前的 session、agent、provider、model 解引用。 */
@AllArgsConstructor
@Component
final class EmbeddedAgentRuntimeReferenceResolver {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentSessionRepository agentSessionRepository;

  RuntimeReferences resolve(String sessionId) {
    AgentSession session = requireSession(sessionId);
    AgentDefinition agentDefinition = requireAgentDefinition(session.getAgentId());
    AgentProvider provider = requireProvider(agentDefinition.getDefaultProviderId());
    AgentModel model = requireModel(agentDefinition.getDefaultModelId());
    return new RuntimeReferences(session, agentDefinition, provider, model);
  }

  private AgentSession requireSession(String sessionId) {
    AgentSession session = agentSessionRepository.getBySessionId(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return session;
  }

  private AgentDefinition requireAgentDefinition(long agentId) {
    AgentDefinition agentDefinition = agentDefinitionRepository.getById(agentId);
    if (agentDefinition == null) {
      throw new IllegalArgumentException("agent not found: " + agentId);
    }
    return agentDefinition;
  }

  private AgentProvider requireProvider(long providerId) {
    AgentProvider provider = agentProviderRepository.getById(providerId);
    if (provider == null) {
      throw new IllegalArgumentException("provider config not found: " + providerId);
    }
    return provider;
  }

  private AgentModel requireModel(long modelId) {
    AgentModel model = agentModelRepository.getById(modelId);
    if (model == null) {
      throw new IllegalArgumentException("model config not found: " + modelId);
    }
    return model;
  }

  record RuntimeReferences(
      AgentSession session,
      AgentDefinition agentDefinition,
      AgentProvider provider,
      AgentModel model) {}
}
