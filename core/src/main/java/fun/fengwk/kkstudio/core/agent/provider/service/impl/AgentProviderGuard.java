package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

/** 统一处理 agent provider 服务依赖的实体校验、唯一性校验与删除前校验。 */
@AllArgsConstructor
@Component
final class AgentProviderGuard {

  private final AgentProviderRepository agentProviderRepository;

  AgentProvider requireProvider(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent provider id must be positive");
    }
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
    if (agentProviderRepository.hasAgents(id)) {
      throw new IllegalStateException("agent provider in use by agents: " + id);
    }
  }
}
