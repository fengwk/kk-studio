package fun.fengwk.kkstudio.core.agent.provider.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

/**
 * @author fengwk
 */
public interface AgentProviderRepository {

  Page<AgentProvider> page(long workspaceId, PageQuery pageQuery);

  AgentProvider getById(long id);

  AgentProvider getByWorkspaceIdAndId(long workspaceId, long id);

  AgentProvider getByWorkspaceIdAndName(long workspaceId, String name);

  boolean create(AgentProvider provider);

  boolean updateById(AgentProvider provider);

  boolean deleteByWorkspaceIdAndId(long workspaceId, long id);

  boolean hasModels(long workspaceId, long providerId);
}
