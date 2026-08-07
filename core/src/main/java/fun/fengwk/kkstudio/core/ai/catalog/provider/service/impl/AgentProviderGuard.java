package fun.fengwk.kkstudio.core.ai.catalog.provider.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;

/** Provider lookup and deletion checks. */
@AllArgsConstructor
@Component
final class AgentProviderGuard {

  private static final String RESOURCE = "agent_provider";

  private final AgentProviderRepository agentProviderRepository;

  AgentProvider requireProvider(String name) {
    AgentProvider provider = agentProviderRepository.getByName(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + name);
    }
    return provider;
  }

  AgentProvider requireProviderForUpdate(String name) {
    AgentProvider provider = agentProviderRepository.getByNameForUpdate(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + name);
    }
    return provider;
  }

  void ensureDeletable(String name) {
    if (agentProviderRepository.hasModels(name)) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by models: " + name);
    }
  }
}
