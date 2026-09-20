package fun.fengwk.kkstudio.platform.catalog.definition.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;

/** 全局 Agent definition 仓库。 */
public interface AgentDefinitionRepository {

  Page<AgentDefinition> page(PageQuery pageQuery);

  AgentDefinition getByName(String name);

  AgentDefinition getByNameForUpdate(String name);

  boolean existsReferencingSubagent(String name);

  /** 是否有任何 Agent 的 {@code config.skills} 引用该全局 Skill。 */
  boolean existsReferencingSkill(String packageName, String skillName);

  boolean create(AgentDefinition agentDefinition);

  boolean updateByName(AgentDefinition agentDefinition, long expectedVersion);

  boolean deleteByName(String name, long expectedVersion);
}
