package fun.fengwk.kkstudio.platform.ai.catalog.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.ai.catalog.model.service.model.AgentModel;

/** 全局 model 仓库。 */
public interface AgentModelRepository {

  Page<AgentModel> page(PageQuery pageQuery);

  /** Model 名在同一个 provider 内唯一，而非全局唯一。 */
  AgentModel getByProviderNameAndName(String providerName, String name);

  /** 返回有效的 model 并持有其行锁，直到外层事务结束。 */
  AgentModel getByProviderNameAndNameForUpdate(String providerName, String name);

  boolean create(AgentModel model);

  /** 基于 (providerName, name, expectedVersion) 的原子 CAS 更新。 */
  boolean updateByName(AgentModel model, long expectedVersion);

  /**
   * 基于 (providerName, name, expectedVersion) 的硬删除 CAS：行物理删除后同名立即可重建；返回 false 表示 identity 缺失或
   * version 不匹配。
   */
  boolean deleteByName(String providerName, String name, long expectedVersion);

  boolean hasAgents(String providerName, String name);
}
