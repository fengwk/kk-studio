package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;

/** Resolves global Agent definition and model references. */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private static final String DEFINITION_RESOURCE = "agent_definition";
  private static final String MODEL_RESOURCE = "agent_model";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;

  AgentDefinition requireAgent(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByName(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(
          DEFINITION_RESOURCE, DEFINITION_RESOURCE + " not found: " + name);
    }
    return definition;
  }

  AgentDefinition requireAgentForUpdate(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByNameForUpdate(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(
          DEFINITION_RESOURCE, DEFINITION_RESOURCE + " not found: " + name);
    }
    return definition;
  }

  AgentModel requireModel(String providerName, String modelName) {
    AgentModel model = agentModelRepository.getByProviderNameAndName(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(
          MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + providerName + "/" + modelName);
    }
    return model;
  }

  AgentModel requireModelForUpdate(String providerName, String modelName) {
    AgentModel model =
        agentModelRepository.getByProviderNameAndNameForUpdate(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(
          MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + providerName + "/" + modelName);
    }
    return model;
  }

  void ensureNameAvailable(String name) {
    if (agentDefinitionRepository.getByName(name) != null) {
      throw new AiDuplicateException(
          DEFINITION_RESOURCE, DEFINITION_RESOURCE + " name already exists: " + name);
    }
  }
}
