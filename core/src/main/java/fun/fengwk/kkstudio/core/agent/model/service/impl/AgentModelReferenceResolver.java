package fun.fengwk.kkstudio.core.agent.model.service.impl;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/** 统一处理 agent model 服务依赖的实体解析、唯一性校验与删除前校验。 */
@AllArgsConstructor
@Component
final class AgentModelReferenceResolver {

  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;

  AgentModel requireModel(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent model id must be positive");
    }
    AgentModel model = agentModelRepository.getById(id);
    if (model == null) {
      throw new IllegalArgumentException("agent model not found: " + id);
    }
    return model;
  }

  AgentProvider resolveProvider(long providerId) {
    return agentProviderRepository.getById(providerId);
  }

  AgentProvider requireProvider(long providerId) {
    AgentProvider provider = resolveProvider(providerId);
    if (provider == null) {
      throw new IllegalStateException("agent provider not found: " + providerId);
    }
    return provider;
  }

  AgentProvider requireProviderByName(String providerName) {
    AgentProvider provider = agentProviderRepository.getByName(providerName);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + providerName);
    }
    return provider;
  }

  void ensureNameAvailable(long providerId, String name) {
    if (agentModelRepository.getByProviderIdAndName(providerId, name) != null) {
      throw new IllegalArgumentException("agent model name already exists: " + name);
    }
  }

  void ensureNameAvailable(long providerId, String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(providerId, nextName);
    }
  }

  void ensureDeletable(long id) {
    if (agentModelRepository.hasAgents(id)) {
      throw new IllegalStateException("agent model in use by agents: " + id);
    }
  }
}
