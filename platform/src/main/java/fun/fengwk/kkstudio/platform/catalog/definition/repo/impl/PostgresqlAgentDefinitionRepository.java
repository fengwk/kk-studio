package fun.fengwk.kkstudio.platform.catalog.definition.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;

import java.util.List;

/** 基于 PostgreSQL 的全局 Agent definition 仓库。 */
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
  public List<AgentDefinition> listAll() {
    return agentDefinitionMapper.listAll().stream().map(this::convert).toList();
  }

  @Override
  public AgentDefinition getByName(String name) {
    return convert(agentDefinitionMapper.getByName(name));
  }

  @Override
  public AgentDefinition getByNameForUpdate(String name) {
    return convert(agentDefinitionMapper.getByNameForUpdate(name));
  }

  @Override
  public boolean existsReferencingSubagent(String name) {
    return agentDefinitionMapper.existsReferencingSubagent(name);
  }

  @Override
  public boolean existsReferencingSkill(String packageName, String skillName) {
    return agentDefinitionMapper.existsReferencingSkill(packageName, skillName);
  }

  @Override
  public boolean create(AgentDefinition agentDefinition) {
    return agentDefinitionMapper.insert(convert(agentDefinition)) == 1;
  }

  @Override
  public boolean updateByName(AgentDefinition agentDefinition, long expectedVersion) {
    return agentDefinitionMapper.updateByName(convert(agentDefinition), expectedVersion) == 1;
  }

  @Override
  public boolean deleteByName(String name, long expectedVersion) {
    return agentDefinitionMapper.deleteByName(name, expectedVersion) == 1;
  }

  private AgentDefinitionDO convert(AgentDefinition definition) {
    if (definition == null) {
      return null;
    }
    AgentDefinitionDO result = new AgentDefinitionDO();
    result.setName(definition.getName());
    result.setType(definition.getType() == null ? null : definition.getType().name());
    result.setDescription(definition.getDescription());
    result.setSystemPrompt(definition.getSystemPrompt());
    result.setModelProviderName(definition.getModelProviderName());
    result.setModelName(definition.getModelName());
    result.setVariant(definition.getVariant());
    result.setConfigJson(definition.getConfigJson());
    return result;
  }

  private AgentDefinition convert(AgentDefinitionDO definition) {
    if (definition == null) {
      return null;
    }
    AgentDefinition result = new AgentDefinition();
    result.setName(definition.getName());
    result.setType(
        definition.getType() == null ? null : AgentDefinitionType.valueOf(definition.getType()));
    result.setDescription(definition.getDescription());
    result.setSystemPrompt(definition.getSystemPrompt());
    result.setModelProviderName(definition.getModelProviderName());
    result.setModelName(definition.getModelName());
    result.setVariant(definition.getVariant());
    result.setConfigJson(definition.getConfigJson());
    result.setVersion(definition.getVersion());
    result.setCreateTime(definition.getCreateTime());
    result.setUpdateTime(definition.getUpdateTime());
    return result;
  }
}
