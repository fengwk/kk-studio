package fun.fengwk.kkstudio.core.agent.model.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

/** Resolves scoped model and provider references. */
@AllArgsConstructor
@Component
final class AgentModelReferenceResolver {

  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;

  AgentModel requireModel(long workspaceId, long id) {
    AgentModel model = agentModelRepository.getByWorkspaceIdAndId(workspaceId, id);
    if (model == null) {
      throw new IllegalArgumentException("agent model not found in workspace: " + id);
    }
    return model;
  }

  AgentProvider requireProvider(long workspaceId, long id) {
    AgentProvider provider = agentProviderRepository.getByWorkspaceIdAndId(workspaceId, id);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found in workspace: " + id);
    }
    return provider;
  }

  void ensureNameAvailable(long workspaceId, String name) {
    if (agentModelRepository.getByWorkspaceIdAndName(workspaceId, name) != null) {
      throw new IllegalArgumentException("agent model name already exists in workspace: " + name);
    }
  }

  void ensureNameAvailable(long workspaceId, String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(workspaceId, nextName);
    }
  }

  void ensureDeletable(long workspaceId, long id) {
    if (agentModelRepository.hasAgents(workspaceId, id)) {
      throw new IllegalStateException("agent model in use by agents: " + id);
    }
  }
}
