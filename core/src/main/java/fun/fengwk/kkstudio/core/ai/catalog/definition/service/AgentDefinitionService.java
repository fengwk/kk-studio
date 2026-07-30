package fun.fengwk.kkstudio.core.ai.catalog.definition.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;

/** Global Agent definition application service. */
public interface AgentDefinitionService {

  Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery);

  AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO);

  AgentDefinitionDTO updateAgent(long id, AgentDefinitionUpdateDTO updateDTO);

  void deleteAgent(long id, String expectedVersion);
}
