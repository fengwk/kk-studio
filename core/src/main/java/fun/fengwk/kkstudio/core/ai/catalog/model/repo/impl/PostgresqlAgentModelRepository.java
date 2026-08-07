package fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;

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
  public AgentModel getByProviderNameAndName(String providerName, String name) {
    return convert(agentModelMapper.getByProviderNameAndName(providerName, name));
  }

  @Override
  public AgentModel getByProviderNameAndNameForUpdate(String providerName, String name) {
    return convert(agentModelMapper.getByProviderNameAndNameForUpdate(providerName, name));
  }

  @Override
  public boolean create(AgentModel model) {
    return agentModelMapper.insert(convert(model)) == 1;
  }

  @Override
  public boolean updateByName(AgentModel model, long expectedVersion) {
    return agentModelMapper.updateByName(convert(model), expectedVersion) == 1;
  }

  @Override
  public boolean deleteByName(String providerName, String name, long expectedVersion) {
    return agentModelMapper.deleteByName(providerName, name, expectedVersion) == 1;
  }

  @Override
  public boolean hasAgents(String providerName, String name) {
    return agentModelMapper.countAgentsByModelName(providerName, name) > 0;
  }

  private AgentModelDO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDO result = new AgentModelDO();
    result.setProviderName(model.getProviderName());
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
    result.setProviderName(model.getProviderName());
    result.setName(model.getName());
    result.setDescription(model.getDescription());
    result.setConfigJson(model.getConfigJson());
    result.setVersion(model.getVersion());
    result.setCreateTime(model.getCreateTime());
    result.setUpdateTime(model.getUpdateTime());
    return result;
  }
}
