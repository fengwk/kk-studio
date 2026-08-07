package fun.fengwk.kkstudio.core.ai.catalog.definition.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;

/** 全局 Agent definition 仓库。 */
public interface AgentDefinitionRepository {

  Page<AgentDefinition> page(PageQuery pageQuery);

  AgentDefinition getByName(String name);

  /** 返回有效的 Agent 并持有其行锁，直到外层事务结束。 */
  AgentDefinition getByNameForUpdate(String name);

  boolean create(AgentDefinition agentDefinition);

  /** 基于 (name, expectedVersion) 的原子 CAS 更新。 */
  boolean updateByName(AgentDefinition agentDefinition, long expectedVersion);

  /** 基于 (name, expectedVersion) 的硬删除 CAS：行物理删除后同名立即可重建；返回 false 表示 name 缺失或 version 不匹配。 */
  boolean deleteByName(String name, long expectedVersion);
}
