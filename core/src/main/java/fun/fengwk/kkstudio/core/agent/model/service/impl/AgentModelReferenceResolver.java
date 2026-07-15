package fun.fengwk.kkstudio.core.agent.model.service.impl;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves global model and provider references. */
@AllArgsConstructor
@Component
final class AgentModelReferenceResolver {

  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;

  AgentModel requireModel(long id) {
    AgentModel model = agentModelRepository.getById(id);
    if (model == null) {
      throw new IllegalArgumentException("agent model not found: " + id);
    }
    return model;
  }

  AgentProvider requireProvider(long id) {
    AgentProvider provider = agentProviderRepository.getById(id);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + id);
    }
    return provider;
  }

  void ensureNameAvailable(String name) {
    if (agentModelRepository.getByName(name) != null) {
      throw new IllegalArgumentException("agent model name already exists: " + name);
    }
  }

  void ensureNameAvailable(String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(nextName);
    }
  }

  void ensureDeletable(long id) {
    if (agentModelRepository.hasAgents(id)) {
      throw new IllegalStateException("agent model in use by agents: " + id);
    }
  }
}
