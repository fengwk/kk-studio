package fun.fengwk.kkstudio.core.agent.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;

/**
 * @author fengwk
 */
public interface AgentModelRepository {

  Page<AgentModel> page(PageQuery pageQuery);

  AgentModel getById(long id);

  AgentModel getByProviderIdAndName(long providerId, String name);

  boolean create(AgentModel model);

  boolean updateById(AgentModel model);

  boolean deleteById(long id);

  boolean hasAgents(long modelId);
}
