package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.workspace.repo.WorkspaceRepository;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentProviderServiceImpl implements AgentProviderService {

  private final AgentProviderRepository agentProviderRepository;
  private final WorkspaceRepository workspaceRepository;
  private final AgentProviderConverter agentProviderConverter;
  private final AgentProviderMutationFactory providerMutationFactory;
  private final AgentProviderGuard providerGuard;

  @Override
  public Page<AgentProviderDTO> pageProviders(long workspaceId, PageQuery pageQuery) {
    requireWorkspace(workspaceId);
    return agentProviderRepository.page(workspaceId, pageQuery).map(agentProviderConverter::convert);
  }

  @Override
  public AgentProviderDTO createProvider(long workspaceId, AgentProviderCreateDTO createDTO) {
    requireWorkspace(workspaceId);
    AgentProvider provider = providerMutationFactory.newProvider(workspaceId, createDTO);
    providerGuard.ensureNameAvailable(workspaceId, provider.getName());
    if (!agentProviderRepository.create(provider)) {
      throw new IllegalStateException("create agent provider failed");
    }
    return agentProviderConverter.convert(
        agentProviderRepository.getByWorkspaceIdAndId(workspaceId, provider.getId()));
  }

  @Override
  public AgentProviderDTO updateProvider(long workspaceId, long id, AgentProviderUpdateDTO updateDTO) {
    requireWorkspace(workspaceId);
    AgentProvider provider = providerGuard.requireProvider(workspaceId, id);
    String currentName = provider.getName();
    providerMutationFactory.update(provider, updateDTO);
    providerGuard.ensureNameAvailable(workspaceId, currentName, provider.getName());
    if (!agentProviderRepository.updateById(provider)) {
      throw new IllegalStateException("update agent provider failed: " + id);
    }
    return agentProviderConverter.convert(agentProviderRepository.getByWorkspaceIdAndId(workspaceId, id));
  }

  @Override
  public void deleteProvider(long workspaceId, long id) {
    requireWorkspace(workspaceId);
    providerGuard.requireProvider(workspaceId, id);
    providerGuard.ensureDeletable(workspaceId, id);
    if (!agentProviderRepository.deleteByWorkspaceIdAndId(workspaceId, id)) {
      throw new IllegalStateException("delete agent provider failed: " + id);
    }
  }

  private void requireWorkspace(long workspaceId) {
    if (workspaceId <= 0 || workspaceRepository.getById(workspaceId) == null) {
      throw new IllegalArgumentException("workspace not found: " + workspaceId);
    }
  }
}
