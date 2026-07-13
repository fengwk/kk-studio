package fun.fengwk.kkstudio.core.agent.model.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

/**
 * @author fengwk
 */
public interface AgentModelService {

  Page<AgentModelDTO> pageModels(long workspaceId, PageQuery pageQuery);

  AgentModelDTO createModel(long workspaceId, AgentModelCreateDTO createDTO);

  AgentModelDTO updateModel(long workspaceId, long id, AgentModelUpdateDTO updateDTO);

  void deleteModel(long workspaceId, long id);
}
