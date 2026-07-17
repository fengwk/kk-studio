package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

/** Provider lookup, global uniqueness and deletion checks. */
@AllArgsConstructor
@Component
final class AgentProviderGuard {

  private final AgentProviderRepository agentProviderRepository;

  AgentProvider requireProvider(long id) {
    AgentProvider provider = agentProviderRepository.getById(id);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + id);
    }
    return provider;
  }

  void ensureNameAvailable(String name) {
    if (agentProviderRepository.getByName(name) != null) {
      throw new IllegalArgumentException("agent provider name already exists: " + name);
    }
  }

  void ensureNameAvailable(String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(nextName);
    }
  }

  void ensureDeletable(long id) {
    if (agentProviderRepository.hasModels(id)) {
      throw new IllegalStateException("agent provider in use by models: " + id);
    }
  }
}
