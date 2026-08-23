package fun.fengwk.kkstudio.platform.ai.catalog.model.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

/** 全局 model 应用服务。 */
public interface AgentModelService {

  Page<AgentModelDTO> pageModels(PageQuery pageQuery);

  AgentModelDTO createModel(AgentModelCreateDTO createDTO);

  AgentModelDTO updateModel(String providerName, String modelName, AgentModelUpdateDTO updateDTO);

  void deleteModel(String providerName, String modelName, String expectedVersion);
}
