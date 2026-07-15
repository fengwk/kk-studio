package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves legacy sessions through the configured Agent, model and provider graph. */
@AllArgsConstructor
@Component
final class EmbeddedAgentRuntimeReferenceResolver {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentSessionRepository agentSessionRepository;

  RuntimeReferences resolve(String sessionId) {
    AgentSession session = requireSession(sessionId);
    AgentDefinition definition = requireAgentDefinition(session.getAgentId());
    AgentModel model = requireModel(definition.getModelId());
    AgentProvider provider = requireProvider(model.getProviderId());
    return new RuntimeReferences(session, definition, provider, model);
  }

  private AgentSession requireSession(String sessionId) {
    AgentSession session = agentSessionRepository.getBySessionId(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return session;
  }

  private AgentDefinition requireAgentDefinition(long agentId) {
    AgentDefinition definition = agentDefinitionRepository.getById(agentId);
    if (definition == null) {
      throw new IllegalArgumentException("agent not found: " + agentId);
    }
    return definition;
  }

  private AgentModel requireModel(long modelId) {
    AgentModel model = agentModelRepository.getById(modelId);
    if (model == null) {
      throw new IllegalArgumentException("model config not found: " + modelId);
    }
    return model;
  }

  private AgentProvider requireProvider(long providerId) {
    AgentProvider provider = agentProviderRepository.getById(providerId);
    if (provider == null) {
      throw new IllegalArgumentException("provider config not found: " + providerId);
    }
    return provider;
  }

  record RuntimeReferences(
      AgentSession session,
      AgentDefinition agentDefinition,
      AgentProvider provider,
      AgentModel model) {}
}
