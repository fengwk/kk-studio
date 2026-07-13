package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

/** Provider scoped lookup, uniqueness and deletion checks. */
@AllArgsConstructor
@Component
final class AgentProviderGuard {

  private final AgentProviderRepository agentProviderRepository;

  AgentProvider requireProvider(long workspaceId, long id) {
    AgentProvider provider = agentProviderRepository.getByWorkspaceIdAndId(workspaceId, id);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found in workspace: " + id);
    }
    return provider;
  }

  void ensureNameAvailable(long workspaceId, String name) {
    if (agentProviderRepository.getByWorkspaceIdAndName(workspaceId, name) != null) {
      throw new IllegalArgumentException("agent provider name already exists in workspace: " + name);
    }
  }

  void ensureNameAvailable(long workspaceId, String currentName, String nextName) {
    if (!currentName.equals(nextName)) {
      ensureNameAvailable(workspaceId, nextName);
    }
  }

  void ensureDeletable(long workspaceId, long id) {
    if (agentProviderRepository.hasModels(workspaceId, id)) {
      throw new IllegalStateException("agent provider in use by models: " + id);
    }
  }
}
