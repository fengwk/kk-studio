package fun.fengwk.kkstudio.core.ai.catalog.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;

/** Global model repository. */
public interface AgentModelRepository {

  Page<AgentModel> page(PageQuery pageQuery);

  AgentModel getById(long id);

  /** Model names are unique within a provider, not globally. */
  AgentModel getByProviderIdAndName(long providerId, String name);

  boolean create(AgentModel model);

  /** Atomic CAS update on (id, expectedVersion). */
  boolean updateById(AgentModel model, long expectedVersion);

  /** Atomic CAS delete on (id, expectedVersion). */
  boolean deleteById(long id, long expectedVersion);

  boolean hasAgents(long modelId);
}
