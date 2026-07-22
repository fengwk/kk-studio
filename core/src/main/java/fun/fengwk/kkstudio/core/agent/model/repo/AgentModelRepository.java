package fun.fengwk.kkstudio.core.agent.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;

/** Global model repository. */
public interface AgentModelRepository {

  Page<AgentModel> page(PageQuery pageQuery);

  AgentModel getById(long id);

  /** Model names are unique within a provider, not globally. */
  AgentModel getByProviderIdAndName(long providerId, String name);

  boolean create(AgentModel model);

  boolean updateById(AgentModel model);

  boolean deleteById(long id);

  boolean hasAgents(long modelId);
}
