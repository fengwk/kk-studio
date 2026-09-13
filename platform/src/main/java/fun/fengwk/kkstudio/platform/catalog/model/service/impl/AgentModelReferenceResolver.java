package fun.fengwk.kkstudio.platform.catalog.model.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;

/** 解析全局 model 与 provider 引用。 */
@AllArgsConstructor
@Component
final class AgentModelReferenceResolver {

  private static final String MODEL_RESOURCE = "agent_model";
  private static final String PROVIDER_RESOURCE = "agent_provider";

  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;

  AgentModel requireModel(String providerName, String name) {
    AgentModel model = agentModelRepository.getByProviderNameAndName(providerName, name);
    if (model == null) {
      throw new AiResourceNotFoundException(MODEL_RESOURCE);
    }
    return model;
  }

  AgentModel requireModelForUpdate(String providerName, String name) {
    AgentModel model = agentModelRepository.getByProviderNameAndNameForUpdate(providerName, name);
    if (model == null) {
      throw new AiResourceNotFoundException(MODEL_RESOURCE);
    }
    return model;
  }

  AgentProvider requireProvider(String name) {
    AgentProvider provider = agentProviderRepository.getByName(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
    }
    return provider;
  }

  AgentProvider requireProviderForUpdate(String name) {
    AgentProvider provider = agentProviderRepository.getByNameForUpdate(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
    }
    return provider;
  }

  void ensureDeletable(String providerName, String name) {
    if (agentModelRepository.hasAgents(providerName, name)) {
      throw new AiInUseException(
          MODEL_RESOURCE, MODEL_RESOURCE + " in use by agents: " + providerName + "/" + name);
    }
  }
}
