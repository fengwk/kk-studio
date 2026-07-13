package fun.fengwk.kkstudio.core.agent.definition.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

/**
 * @author fengwk
 */
public interface AgentDefinitionService {

  Page<AgentDefinitionDTO> pageAgents(long workspaceId, PageQuery pageQuery);

  AgentDefinitionDTO createAgent(long workspaceId, AgentDefinitionCreateDTO createDTO);

  AgentDefinitionDTO updateAgent(long workspaceId, long id, AgentDefinitionUpdateDTO updateDTO);

  void deleteAgent(long workspaceId, long id);
}
