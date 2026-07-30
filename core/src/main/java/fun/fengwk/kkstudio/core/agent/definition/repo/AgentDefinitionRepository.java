package fun.fengwk.kkstudio.core.agent.definition.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;

/** Global Agent definition repository. */
public interface AgentDefinitionRepository {

  Page<AgentDefinition> page(PageQuery pageQuery);

  AgentDefinition getById(long id);

  AgentDefinition getByName(String name);

  boolean create(AgentDefinition agentDefinition);

  /** Atomic CAS update on (id, expectedVersion). */
  boolean updateById(AgentDefinition agentDefinition, long expectedVersion);

  /** Atomic CAS delete on (id, expectedVersion). */
  boolean deleteById(long id, long expectedVersion);
}
