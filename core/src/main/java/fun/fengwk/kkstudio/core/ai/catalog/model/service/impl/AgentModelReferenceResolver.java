package fun.fengwk.kkstudio.core.ai.catalog.model.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;

/** Resolves global model and provider references. */
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
      throw new AiResourceNotFoundException(
          MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + providerName + "/" + name);
    }
    return model;
  }

  AgentProvider requireProvider(String name) {
    AgentProvider provider = agentProviderRepository.getByName(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(
          PROVIDER_RESOURCE, PROVIDER_RESOURCE + " not found: " + name);
    }
    return provider;
  }

  void ensureNameAvailable(String providerName, String name) {
    if (agentModelRepository.getByProviderNameAndName(providerName, name) != null) {
      throw new AiDuplicateException(
          MODEL_RESOURCE, MODEL_RESOURCE + " name already exists under this provider: " + name);
    }
  }

  void ensureNameAvailable(String providerName, String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(providerName, nextName);
    }
  }

  void ensureDeletable(String providerName, String name) {
    if (agentModelRepository.hasAgents(providerName, name)) {
      throw new AiInUseException(
          MODEL_RESOURCE, MODEL_RESOURCE + " in use by agents: " + providerName + "/" + name);
    }
  }
}
