package fun.fengwk.kkstudio.core.agent.provider.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/**
 * @author fengwk
 */
public interface AgentProviderService {

  Page<AgentProviderDTO> pageProviders(long workspaceId, PageQuery pageQuery);

  AgentProviderDTO createProvider(long workspaceId, AgentProviderCreateDTO createDTO);

  AgentProviderDTO updateProvider(long workspaceId, long id, AgentProviderUpdateDTO updateDTO);

  void deleteProvider(long workspaceId, long id);
}
