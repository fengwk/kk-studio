package fun.fengwk.kkstudio.core.agent.model.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentModelRepository implements AgentModelRepository {

  private final AgentModelMapper agentModelMapper;

  @Override
  public Page<AgentModel> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentModelDO> result = agentModelMapper.pageAll(offset, limit);
    long totalCount = agentModelMapper.countAll();
    return Pages.page(pageQuery, result, totalCount).map(this::convert);
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
    AgentModelDO modelDO = new AgentModelDO();
    modelDO.setId(model.getId());
    modelDO.setProviderId(model.getProviderId());
    modelDO.setName(model.getName());
    modelDO.setDescription(model.getDescription());
    modelDO.setCapabilitiesJson(model.getCapabilitiesJson());
    modelDO.setLimitJson(model.getLimitJson());
    modelDO.setPricingJson(model.getPricingJson());
    modelDO.setDefaultVariant(model.getDefaultVariant());
    modelDO.setVariantsJson(model.getVariantsJson());
    return modelDO;
  }

  private AgentModel convert(AgentModelDO modelDO) {
    if (modelDO == null) {
      return null;
    }
    AgentModel model = new AgentModel();
    model.setId(modelDO.getId());
    model.setProviderId(modelDO.getProviderId());
    model.setName(modelDO.getName());
    model.setDescription(modelDO.getDescription());
    model.setCapabilitiesJson(modelDO.getCapabilitiesJson());
    model.setLimitJson(modelDO.getLimitJson());
    model.setPricingJson(modelDO.getPricingJson());
    model.setDefaultVariant(modelDO.getDefaultVariant());
    model.setVariantsJson(modelDO.getVariantsJson());
    model.setCreateTime(modelDO.getCreateTime());
    model.setUpdateTime(modelDO.getUpdateTime());
    return model;
  }
}
