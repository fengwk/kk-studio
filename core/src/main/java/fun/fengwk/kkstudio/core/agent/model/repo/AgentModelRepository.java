package fun.fengwk.kkstudio.core.agent.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;

/**
 * @author fengwk
 */
public interface AgentModelRepository {

  Page<AgentModel> page(long workspaceId, PageQuery pageQuery);

  AgentModel getById(long id);

  AgentModel getByWorkspaceIdAndId(long workspaceId, long id);

  AgentModel getByWorkspaceIdAndName(long workspaceId, String name);

  boolean create(AgentModel model);

  boolean updateById(AgentModel model);

  boolean deleteByWorkspaceIdAndId(long workspaceId, long id);

  boolean hasAgents(long workspaceId, long modelId);
}
