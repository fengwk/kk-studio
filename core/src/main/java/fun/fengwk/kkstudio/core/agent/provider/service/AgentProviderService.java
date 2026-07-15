package fun.fengwk.kkstudio.core.agent.provider.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/** Global provider application service. */
public interface AgentProviderService {

  Page<AgentProviderDTO> pageProviders(PageQuery pageQuery);

  AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO);

  AgentProviderDTO updateProvider(long id, AgentProviderUpdateDTO updateDTO);

  void deleteProvider(long id);
}
