package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/** Resolves global Agent definition and model references. */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;

  AgentDefinition requireAgent(long id) {
    AgentDefinition definition = agentDefinitionRepository.getById(id);
    if (definition == null) {
      throw new IllegalArgumentException("agent definition not found: " + id);
    }
    return definition;
  }

  AgentModel requireModel(long id) {
    AgentModel model = agentModelRepository.getById(id);
    if (model == null) {
      throw new IllegalArgumentException("agent model not found: " + id);
    }
    return model;
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
}
