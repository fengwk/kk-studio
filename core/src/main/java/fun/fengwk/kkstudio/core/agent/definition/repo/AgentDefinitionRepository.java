package fun.fengwk.kkstudio.core.agent.definition.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;

/**
 * @author fengwk
 */
public interface AgentDefinitionRepository {

  Page<AgentDefinition> page(long workspaceId, PageQuery pageQuery);

  AgentDefinition getById(long id);

  AgentDefinition getByWorkspaceIdAndId(long workspaceId, long id);

  AgentDefinition getByWorkspaceIdAndName(long workspaceId, String name);

  AgentDefinition getByName(String name);

  boolean create(AgentDefinition agentDefinition);

  boolean updateById(AgentDefinition agentDefinition);

  boolean deleteByWorkspaceIdAndId(long workspaceId, long id);
}
