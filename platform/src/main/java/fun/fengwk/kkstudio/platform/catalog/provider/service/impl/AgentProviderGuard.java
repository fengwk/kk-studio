package fun.fengwk.kkstudio.platform.catalog.provider.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;

/** Provider 查找与删除检查。 */
@AllArgsConstructor
@Component
final class AgentProviderGuard {

  private static final String RESOURCE = "agent_provider";

  private final AgentProviderRepository agentProviderRepository;

  AgentProvider requireProvider(String name) {
    AgentProvider provider = agentProviderRepository.getByName(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return provider;
  }

  AgentProvider requireProviderForUpdate(String name) {
    AgentProvider provider = agentProviderRepository.getByNameForUpdate(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return provider;
  }

  void ensureDeletable(String name) {
    if (agentProviderRepository.hasModels(name)) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by models: " + name);
    }
  }
}
