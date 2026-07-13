package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;

/** Resolves workspace-scoped definition and model references. */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;

  AgentDefinition requireAgent(long workspaceId, long id) {
    AgentDefinition definition = agentDefinitionRepository.getByWorkspaceIdAndId(workspaceId, id);
    if (definition == null) {
      throw new IllegalArgumentException("agent definition not found in workspace: " + id);
    }
    return definition;
  }

  AgentModel requireModel(long workspaceId, long id) {
    AgentModel model = agentModelRepository.getByWorkspaceIdAndId(workspaceId, id);
    if (model == null) {
      throw new IllegalArgumentException("agent model not found in workspace: " + id);
    }
    return model;
  }

  void ensureNameAvailable(long workspaceId, String name) {
    if (agentDefinitionRepository.getByWorkspaceIdAndName(workspaceId, name) != null) {
      throw new IllegalArgumentException("agent definition name already exists in workspace: " + name);
    }
  }

  void ensureNameAvailable(long workspaceId, String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(workspaceId, nextName);
    }
  }
}
