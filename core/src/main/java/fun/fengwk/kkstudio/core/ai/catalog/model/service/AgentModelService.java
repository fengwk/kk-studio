package fun.fengwk.kkstudio.core.ai.catalog.model.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

/** Global model application service. */
public interface AgentModelService {

  Page<AgentModelDTO> pageModels(PageQuery pageQuery);

  AgentModelDTO createModel(AgentModelCreateDTO createDTO);

  AgentModelDTO updateModel(long id, AgentModelUpdateDTO updateDTO);

  void deleteModel(long id, String expectedVersion);
}
