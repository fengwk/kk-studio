package fun.fengwk.kkstudio.platform.catalog.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;

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
   * 将模型由 oldName 重命名为 model.name，并在同一事务内将引用该模型的 agent_definition 更新到新名称。
   * 任一步骤影响行数不符合预期均抛出运行时异常以触发外层事务回滚。
   */
  void rename(String oldName, AgentModel model, long expectedVersion);

  /**
   * 基于 (providerName, name, expectedVersion) 的硬删除 CAS：行物理删除后同名立即可重建；返回 false 表示 identity 缺失或
   * version 不匹配。
   */
  boolean deleteByName(String providerName, String name, long expectedVersion);

  boolean hasAgents(String providerName, String name);
}
