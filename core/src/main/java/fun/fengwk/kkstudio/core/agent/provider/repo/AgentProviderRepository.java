package fun.fengwk.kkstudio.core.agent.provider.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

/** Global provider repository. */
public interface AgentProviderRepository {

  Page<AgentProvider> page(PageQuery pageQuery);

  AgentProvider getById(long id);

  AgentProvider getByName(String name);

  boolean create(AgentProvider provider);

  /**
   * Atomic CAS update on (id, expectedVersion). Returns true when the row was updated and version
   * incremented by exactly one; false when the id is missing or the version no longer matches.
   */
  boolean updateById(AgentProvider provider, long expectedVersion);

  /** Atomic CAS delete on (id, expectedVersion). */
  boolean deleteById(long id, long expectedVersion);

  boolean hasModels(long providerId);
}
