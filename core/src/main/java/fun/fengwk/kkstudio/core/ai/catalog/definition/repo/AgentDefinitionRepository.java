package fun.fengwk.kkstudio.core.ai.catalog.definition.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;

/** Global Agent definition repository. */
public interface AgentDefinitionRepository {

  Page<AgentDefinition> page(PageQuery pageQuery);

  AgentDefinition getByName(String name);

  /** Returns the active Agent while holding its row lock until the surrounding transaction ends. */
  AgentDefinition getByNameForUpdate(String name);

  boolean create(AgentDefinition agentDefinition);

  /** Atomic CAS update on (name, expectedVersion). */
  boolean updateByName(AgentDefinition agentDefinition, long expectedVersion);

  /** 硬删除 CAS on (name, expectedVersion)：行物理删除后同名立即可重建；返回 false 表示 name 缺失或 version 不匹配。 */
  boolean deleteByName(String name, long expectedVersion);
}
