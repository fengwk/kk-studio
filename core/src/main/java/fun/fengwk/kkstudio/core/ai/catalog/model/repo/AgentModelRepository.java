package fun.fengwk.kkstudio.core.ai.catalog.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;

/** Global model repository. */
public interface AgentModelRepository {

  Page<AgentModel> page(PageQuery pageQuery);

  /** Model names are unique within a provider, not globally. */
  AgentModel getByProviderNameAndName(String providerName, String name);

  /** Returns the active model while holding its row lock until the surrounding transaction ends. */
  AgentModel getByProviderNameAndNameForUpdate(String providerName, String name);

  boolean create(AgentModel model);

  /** Atomic CAS update on (providerName, name, expectedVersion). */
  boolean updateByName(AgentModel model, long expectedVersion);

  /**
   * 硬删除 CAS on (providerName, name, expectedVersion)：行物理删除后同名立即可重建；返回 false 表示 identity 缺失或 version
   * 不匹配。
   */
  boolean deleteByName(String providerName, String name, long expectedVersion);

  boolean hasAgents(String providerName, String name);
}
