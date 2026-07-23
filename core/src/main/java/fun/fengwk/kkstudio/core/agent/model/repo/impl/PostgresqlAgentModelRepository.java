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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** PostgreSQL-backed global model repository. */
@AllArgsConstructor
@Repository
public class PostgresqlAgentModelRepository implements AgentModelRepository {

  private final AgentModelMapper agentModelMapper;

  @Override
  public Page<AgentModel> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentModelDO> results = agentModelMapper.page(offset, limit);
    return Pages.page(pageQuery, results, agentModelMapper.count()).map(this::convert);
  }

  @Override
  public AgentModel getById(long id) {
    return convert(agentModelMapper.getById(id));
  }

  @Override
  public AgentModel getByProviderIdAndName(long providerId, String name) {
    return convert(agentModelMapper.getByProviderIdAndName(providerId, name));
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
  public boolean deleteById(long id) {
    return agentModelMapper.deleteById(id) == 1;
  }

  @Override
  public boolean hasAgents(long modelId) {
    return agentModelMapper.countAgentsByModelId(modelId) > 0;
  }

  private AgentModelDO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDO result = new AgentModelDO();
    result.setId(model.getId());
    result.setProviderId(model.getProviderId());
    result.setName(model.getName());
    result.setDescription(model.getDescription());
    result.setConfigJson(model.getConfigJson());
    return result;
  }

  private AgentModel convert(AgentModelDO model) {
    if (model == null) {
      return null;
    }
    AgentModel result = new AgentModel();
    result.setId(model.getId());
    result.setProviderId(model.getProviderId());
    result.setName(model.getName());
    result.setDescription(model.getDescription());
    result.setConfigJson(model.getConfigJson());
    result.setVersion(model.getVersion());
    result.setCreateTime(toLocalDateTime(model.getCreateTime()));
    result.setUpdateTime(toLocalDateTime(model.getUpdateTime()));
    return result;
  }

  private static LocalDateTime toLocalDateTime(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
