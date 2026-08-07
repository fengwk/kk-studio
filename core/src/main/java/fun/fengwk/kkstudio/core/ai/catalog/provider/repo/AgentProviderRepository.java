package fun.fengwk.kkstudio.core.ai.catalog.provider.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;

/** Global provider repository. */
public interface AgentProviderRepository {

  Page<AgentProvider> page(PageQuery pageQuery);

  AgentProvider getByName(String name);

  /**
   * Returns the active provider while holding its row lock until the surrounding transaction ends.
   */
  AgentProvider getByNameForUpdate(String name);

  boolean create(AgentProvider provider);

  /**
   * Atomic CAS update on (name, expectedVersion). Returns true when the row was updated and version
   * incremented by exactly one; false when the name is missing or the version no longer matches.
   */
  boolean updateByName(AgentProvider provider, long expectedVersion);

  /** 硬删除 CAS on (name, expectedVersion)：行物理删除后同名立即可重建；返回 false 表示 name 缺失或 version 不匹配。 */
  boolean deleteByName(String name, long expectedVersion);

  boolean hasModels(String providerName);
}
