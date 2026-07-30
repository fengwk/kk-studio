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

  AgentModel requireModel(long id) {
    AgentModel model = agentModelRepository.getById(id);
    if (model == null) {
      throw new AiResourceNotFoundException(MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + id);
    }
    return model;
  }

  AgentProvider requireProvider(long id) {
    AgentProvider provider = agentProviderRepository.getById(id);
    if (provider == null) {
      throw new AiResourceNotFoundException(
          PROVIDER_RESOURCE, PROVIDER_RESOURCE + " not found: " + id);
    }
    return provider;
  }

  void ensureNameAvailable(long providerId, String name) {
    if (agentModelRepository.getByProviderIdAndName(providerId, name) != null) {
      throw new AiDuplicateException(
          MODEL_RESOURCE, MODEL_RESOURCE + " name already exists under this provider: " + name);
    }
  }

  void ensureNameAvailable(long providerId, String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(providerId, nextName);
    }
  }

  void ensureDeletable(long id) {
    if (agentModelRepository.hasAgents(id)) {
      throw new AiInUseException(MODEL_RESOURCE, MODEL_RESOURCE + " in use by agents: " + id);
    }
  }
}
