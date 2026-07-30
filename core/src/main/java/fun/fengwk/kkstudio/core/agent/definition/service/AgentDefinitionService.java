package fun.fengwk.kkstudio.core.agent.definition.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

/** Global Agent definition application service. */
public interface AgentDefinitionService {

  Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery);

  AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO);

  AgentDefinitionDTO updateAgent(long id, AgentDefinitionUpdateDTO updateDTO);

  void deleteAgent(long id, String expectedVersion);
}
