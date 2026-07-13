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

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentDefinitionRepository implements AgentDefinitionRepository {

  private final AgentDefinitionMapper agentDefinitionMapper;

  @Override
  public Page<AgentDefinition> page(long workspaceId, PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentDefinitionDO> results = agentDefinitionMapper.pageByWorkspaceId(workspaceId, offset, limit);
    return Pages.page(pageQuery, results, agentDefinitionMapper.countByWorkspaceId(workspaceId))
        .map(this::convert);
  }

  @Override
  public AgentDefinition getById(long id) {
    return convert(agentDefinitionMapper.getById(id));
  }

  @Override
  public AgentDefinition getByWorkspaceIdAndId(long workspaceId, long id) {
    return convert(agentDefinitionMapper.getByWorkspaceIdAndId(workspaceId, id));
  }

  @Override
  public AgentDefinition getByWorkspaceIdAndName(long workspaceId, String name) {
    return convert(agentDefinitionMapper.getByWorkspaceIdAndName(workspaceId, name));
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
  public boolean updateById(AgentDefinition agentDefinition) {
    return agentDefinitionMapper.updateById(convert(agentDefinition)) == 1;
  }

  @Override
  public boolean deleteByWorkspaceIdAndId(long workspaceId, long id) {
    return agentDefinitionMapper.deleteByWorkspaceIdAndId(workspaceId, id) == 1;
  }

  private AgentDefinitionDO convert(AgentDefinition definition) {
    if (definition == null) {
      return null;
    }
    AgentDefinitionDO result = new AgentDefinitionDO();
    result.setId(definition.getId());
    result.setWorkspaceId(definition.getWorkspaceId());
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
    result.setWorkspaceId(definition.getWorkspaceId());
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
