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

  /** Atomic soft-delete CAS on (name, expectedVersion). */
  boolean deleteByName(String name, long expectedVersion);
}
