package fun.fengwk.kkstudio.platform.catalog.model.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.platform.catalog.model.repo.impl.store.AgentModelJdbcStore;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;

import java.util.List;

/** 基于 PostgreSQL 的全局 model 仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlAgentModelRepository implements AgentModelRepository {

  private final AgentModelJdbcStore agentModelJdbcStore;

  @Override
  public Page<AgentModel> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentModelDO> results = agentModelJdbcStore.page(offset, limit);
    return Pages.page(pageQuery, results, agentModelJdbcStore.count()).map(this::convert);
  }

  @Override
  public List<AgentModel> listAll() {
    return agentModelJdbcStore.listAll().stream().map(this::convert).toList();
  }

  @Override
  public AgentModel getByProviderNameAndName(String providerName, String name) {
    return convert(agentModelJdbcStore.getByProviderNameAndName(providerName, name));
  }

  @Override
  public AgentModel getByProviderNameAndNameForUpdate(String providerName, String name) {
    return convert(agentModelJdbcStore.getByProviderNameAndNameForUpdate(providerName, name));
  }

  @Override
  public boolean create(AgentModel model) {
    return agentModelJdbcStore.insert(convert(model)) == 1;
  }

  @Override
  public boolean updateByName(AgentModel model, long expectedVersion) {
    return agentModelJdbcStore.updateByName(convert(model), expectedVersion) == 1;
  }

  @Override
  public void rename(String oldName, AgentModel model, long expectedVersion) {
    AgentModelDO modelDO = convert(model);
    int inserted = agentModelJdbcStore.insertRenamed(oldName, modelDO, expectedVersion);
    if (inserted != 1) {
      throw new IllegalStateException(
          "insert target model failed during rename: "
              + oldName
              + " -> "
              + model.getName()
              + ", expectedVersion="
              + expectedVersion);
    }
    agentModelJdbcStore.updateReferencingAgents(model.getProviderName(), oldName, model.getName());
    int deleted =
        agentModelJdbcStore.deleteByName(model.getProviderName(), oldName, expectedVersion);
    if (deleted != 1) {
      throw new IllegalStateException(
          "delete old model failed during rename: "
              + oldName
              + ", expectedVersion="
              + expectedVersion);
    }
  }

  @Override
  public boolean deleteByName(String providerName, String name, long expectedVersion) {
    return agentModelJdbcStore.deleteByName(providerName, name, expectedVersion) == 1;
  }

  @Override
  public boolean hasAgents(String providerName, String name) {
    return agentModelJdbcStore.countAgentsByModelName(providerName, name) > 0;
  }

  private AgentModelDO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDO result = new AgentModelDO();
    result.setProviderName(model.getProviderName());
    result.setName(model.getName());
    result.setModelId(model.getModelId());
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
    result.setModelId(model.getModelId());
    result.setDescription(model.getDescription());
    result.setConfigJson(model.getConfigJson());
    result.setVersion(model.getVersion());
    result.setCreateTime(model.getCreateTime());
    result.setUpdateTime(model.getUpdateTime());
    return result;
  }
}
