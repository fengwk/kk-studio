package fun.fengwk.kkstudio.core.ai.catalog.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;

/** Global model repository. */
public interface AgentModelRepository {

  Page<AgentModel> page(PageQuery pageQuery);

  /** Model names are unique within a provider, not globally. */
  AgentModel getByProviderNameAndName(String providerName, String name);

  boolean create(AgentModel model);

  /** Atomic CAS update on (providerName, name, expectedVersion). */
  boolean updateByName(AgentModel model, long expectedVersion);

  /** Atomic CAS delete on (providerName, name, expectedVersion). */
  boolean deleteByName(String providerName, String name, long expectedVersion);

  boolean hasAgents(String providerName, String name);
}
