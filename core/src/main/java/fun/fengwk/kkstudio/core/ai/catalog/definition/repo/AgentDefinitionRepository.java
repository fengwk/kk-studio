package fun.fengwk.kkstudio.core.ai.catalog.definition.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;

/** Global Agent definition repository. */
public interface AgentDefinitionRepository {

  Page<AgentDefinition> page(PageQuery pageQuery);

  AgentDefinition getByName(String name);

  boolean create(AgentDefinition agentDefinition);

  /** Atomic CAS update on (name, expectedVersion). */
  boolean updateByName(AgentDefinition agentDefinition, long expectedVersion);

  /** Atomic CAS delete on (name, expectedVersion). */
  boolean deleteByName(String name, long expectedVersion);
}
