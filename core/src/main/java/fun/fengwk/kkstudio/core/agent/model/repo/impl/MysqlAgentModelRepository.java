package fun.fengwk.kkstudio.core.agent.model.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;

import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentModelRepository implements AgentModelRepository {

  private final AgentModelMapper agentModelMapper;

  @Override
  public Page<AgentModel> page(long workspaceId, PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentModelDO> results = agentModelMapper.pageByWorkspaceId(workspaceId, offset, limit);
    return Pages.page(pageQuery, results, agentModelMapper.countByWorkspaceId(workspaceId))
        .map(this::convert);
  }

  @Override
  public AgentModel getById(long id) {
    return convert(agentModelMapper.getById(id));
  }

  @Override
  public AgentModel getByWorkspaceIdAndId(long workspaceId, long id) {
    return convert(agentModelMapper.getByWorkspaceIdAndId(workspaceId, id));
  }

  @Override
  public AgentModel getByWorkspaceIdAndName(long workspaceId, String name) {
    return convert(agentModelMapper.getByWorkspaceIdAndName(workspaceId, name));
  }

  @Override
  public boolean create(AgentModel model) {
    return agentModelMapper.insert(convert(model)) == 1;
  }

  @Override
  public boolean updateById(AgentModel model) {
    return agentModelMapper.updateById(convert(model)) == 1;
  }

  @Override
  public boolean deleteByWorkspaceIdAndId(long workspaceId, long id) {
    return agentModelMapper.deleteByWorkspaceIdAndId(workspaceId, id) == 1;
  }

  @Override
  public boolean hasAgents(long workspaceId, long modelId) {
    return agentModelMapper.countAgentsByModelId(workspaceId, modelId) > 0;
  }

  private AgentModelDO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDO result = new AgentModelDO();
    result.setId(model.getId());
    result.setWorkspaceId(model.getWorkspaceId());
    result.setProviderId(model.getProviderId());
    result.setName(model.getName());
    result.setDescription(model.getDescription());
    result.setCapabilitiesJson(model.getCapabilitiesJson());
    result.setConfigJson(model.getConfigJson());
    return result;
  }

  private AgentModel convert(AgentModelDO model) {
    if (model == null) {
      return null;
    }
    AgentModel result = new AgentModel();
    result.setId(model.getId());
    result.setWorkspaceId(model.getWorkspaceId());
    result.setProviderId(model.getProviderId());
    result.setName(model.getName());
    result.setDescription(model.getDescription());
    result.setCapabilitiesJson(model.getCapabilitiesJson());
    result.setConfigJson(model.getConfigJson());
    result.setVersion(model.getVersion());
    result.setCreateTime(model.getCreateTime());
    result.setUpdateTime(model.getUpdateTime());
    return result;
  }
}
