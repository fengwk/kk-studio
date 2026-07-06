package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/** 统一处理 agent 定义服务依赖的实体解析与唯一性校验。 */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentModelRepository agentModelRepository;

  AgentDefinition requireAgent(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent id must be positive");
    }
    AgentDefinition agent = agentDefinitionRepository.getById(id);
    if (agent == null) {
      throw new IllegalArgumentException("agent definition not found: " + id);
    }
    return agent;
  }

  void ensureNameAvailable(String name) {
    if (agentDefinitionRepository.getByName(name) != null) {
      throw new IllegalArgumentException("agent definition name already exists: " + name);
    }
  }

  void ensureNameAvailable(String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(nextName);
    }
  }

  AgentProvider resolveProvider(long providerId) {
    return agentProviderRepository.getById(providerId);
  }

  AgentModel resolveModel(long modelId) {
    return agentModelRepository.getById(modelId);
  }

  Defaults resolveDefaults(String providerName, String modelName) {
    AgentProvider provider = agentProviderRepository.getByName(providerName);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + providerName);
    }
    AgentModel model = agentModelRepository.getByProviderIdAndName(provider.getId(), modelName);
    if (model == null) {
      throw new IllegalArgumentException(
          "agent model not found: " + providerName + "/" + modelName);
    }
    return new Defaults(provider, model);
  }

  record Defaults(AgentProvider provider, AgentModel model) {}
}
