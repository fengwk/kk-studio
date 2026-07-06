package fun.fengwk.kkstudio.core.agent.definition.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentDefinitionRepository implements AgentDefinitionRepository {

  private final AgentDefinitionMapper agentDefinitionMapper;

  @Override
  public Page<AgentDefinition> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentDefinitionDO> result = agentDefinitionMapper.pageAll(offset, limit);
    long totalCount = agentDefinitionMapper.countAll();
    return Pages.page(pageQuery, result, totalCount).map(this::convert);
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
  public boolean updateById(AgentDefinition agentDefinition) {
    return agentDefinitionMapper.updateById(convert(agentDefinition)) == 1;
  }

  @Override
  public boolean deleteById(long id) {
    return agentDefinitionMapper.deleteById(id) == 1;
  }

  private AgentDefinitionDO convert(AgentDefinition agentDefinition) {
    if (agentDefinition == null) {
      return null;
    }
    AgentDefinitionDO agentDefinitionDO = new AgentDefinitionDO();
    agentDefinitionDO.setId(agentDefinition.getId());
    agentDefinitionDO.setName(agentDefinition.getName());
    agentDefinitionDO.setDescription(agentDefinition.getDescription());
    agentDefinitionDO.setSystemPrompt(agentDefinition.getSystemPrompt());
    agentDefinitionDO.setDefaultProviderId(agentDefinition.getDefaultProviderId());
    agentDefinitionDO.setDefaultModelId(agentDefinition.getDefaultModelId());
    agentDefinitionDO.setDefaultVariant(agentDefinition.getDefaultVariant());
    agentDefinitionDO.setToolsJson(agentDefinition.getToolsJson());
    agentDefinitionDO.setSubagentsJson(agentDefinition.getSubagentsJson());
    agentDefinitionDO.setSkillsJson(agentDefinition.getSkillsJson());
    return agentDefinitionDO;
  }

  private AgentDefinition convert(AgentDefinitionDO agentDefinitionDO) {
    if (agentDefinitionDO == null) {
      return null;
    }
    AgentDefinition agentDefinition = new AgentDefinition();
    agentDefinition.setId(agentDefinitionDO.getId());
    agentDefinition.setName(agentDefinitionDO.getName());
    agentDefinition.setDescription(agentDefinitionDO.getDescription());
    agentDefinition.setSystemPrompt(agentDefinitionDO.getSystemPrompt());
    agentDefinition.setDefaultProviderId(agentDefinitionDO.getDefaultProviderId());
    agentDefinition.setDefaultModelId(agentDefinitionDO.getDefaultModelId());
    agentDefinition.setDefaultVariant(agentDefinitionDO.getDefaultVariant());
    agentDefinition.setToolsJson(agentDefinitionDO.getToolsJson());
    agentDefinition.setSubagentsJson(agentDefinitionDO.getSubagentsJson());
    agentDefinition.setSkillsJson(agentDefinitionDO.getSkillsJson());
    agentDefinition.setCreateTime(agentDefinitionDO.getCreateTime());
    agentDefinition.setUpdateTime(agentDefinitionDO.getUpdateTime());
    return agentDefinition;
  }
}
