package fun.fengwk.kkstudio.core.agent.definition.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;

import java.util.List;

/** PostgreSQL-backed global Agent definition repository. */
@AllArgsConstructor
@Repository
public class PostgresqlAgentDefinitionRepository implements AgentDefinitionRepository {

  private final AgentDefinitionMapper agentDefinitionMapper;

  @Override
  public Page<AgentDefinition> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentDefinitionDO> results = agentDefinitionMapper.page(offset, limit);
    return Pages.page(pageQuery, results, agentDefinitionMapper.count()).map(this::convert);
  }

  @Override
  public AgentDefinition getById(long id) {
    return convert(agentDefinitionMapper.getById(id));
  }

  @Override
  public AgentDefinition getByName(String name) {
    return convert(agentDefinitionMapper.getByName(name));
  }

  @Override
  public boolean create(AgentDefinition agentDefinition) {
    return agentDefinitionMapper.insert(convert(agentDefinition)) == 1;
  }

  @Override
  public boolean updateById(AgentDefinition agentDefinition, long expectedVersion) {
    return agentDefinitionMapper.updateById(convert(agentDefinition), expectedVersion) == 1;
  }

  @Override
  public boolean deleteById(long id, long expectedVersion) {
    return agentDefinitionMapper.deleteById(id, expectedVersion) == 1;
  }

  private AgentDefinitionDO convert(AgentDefinition definition) {
    if (definition == null) {
      return null;
    }
    AgentDefinitionDO result = new AgentDefinitionDO();
    result.setId(definition.getId());
    result.setName(definition.getName());
    result.setDescription(definition.getDescription());
    result.setSystemPrompt(definition.getSystemPrompt());
    result.setModelId(definition.getModelId());
    result.setVariant(definition.getVariant());
    result.setConfigJson(definition.getConfigJson());
    return result;
  }

  private AgentDefinition convert(AgentDefinitionDO definition) {
    if (definition == null) {
      return null;
    }
    AgentDefinition result = new AgentDefinition();
    result.setId(definition.getId());
    result.setName(definition.getName());
    result.setDescription(definition.getDescription());
    result.setSystemPrompt(definition.getSystemPrompt());
    result.setModelId(definition.getModelId());
    result.setVariant(definition.getVariant());
    result.setConfigJson(definition.getConfigJson());
    result.setVersion(definition.getVersion());
    result.setCreateTime(definition.getCreateTime());
    result.setUpdateTime(definition.getUpdateTime());
    return result;
  }
}
