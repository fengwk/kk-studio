package fun.fengwk.kkstudio.core.ai.catalog.provider.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;

/** Provider lookup, global uniqueness and deletion checks. */
@AllArgsConstructor
@Component
final class AgentProviderGuard {

  private static final String RESOURCE = "agent_provider";

  private final AgentProviderRepository agentProviderRepository;

  AgentProvider requireProvider(long id) {
    AgentProvider provider = agentProviderRepository.getById(id);
    if (provider == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
    }
    return provider;
  }

  void ensureNameAvailable(String name) {
    if (agentProviderRepository.getByName(name) != null) {
      throw new AiDuplicateException(RESOURCE, RESOURCE + " name already exists: " + name);
    }
  }

  void ensureNameAvailable(String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(nextName);
    }
  }

  void ensureDeletable(long id) {
    if (agentProviderRepository.hasModels(id)) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by models: " + id);
    }
  }
}
