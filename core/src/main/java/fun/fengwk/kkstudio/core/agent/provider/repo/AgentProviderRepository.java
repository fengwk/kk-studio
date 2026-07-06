package fun.fengwk.kkstudio.core.agent.provider.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

/**
 * @author fengwk
 */
public interface AgentProviderRepository {

  Page<AgentProvider> page(PageQuery pageQuery);

  AgentProvider getById(long id);

  AgentProvider getByName(String name);

  boolean create(AgentProvider provider);

  boolean updateById(AgentProvider provider);

  boolean deleteById(long id);

  boolean hasModels(long providerId);

  boolean hasAgents(long providerId);
}
