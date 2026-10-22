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
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

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
  public AgentModel getByName(String name) {
    return convert(agentModelJdbcStore.getByName(name));
  }

  @Override
  public AgentModel getByNameForUpdate(String name) {
    return convert(agentModelJdbcStore.getByNameForUpdate(name));
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
    int updated = agentModelJdbcStore.rename(oldName, convert(model), expectedVersion);
    if (updated != 1) {
      throw new IllegalStateException("rename model failed: " + oldName + " -> " + model.getName());
    }
  }

  @Override
  public boolean deleteByName(String name, long expectedVersion) {
    return agentModelJdbcStore.deleteByName(name, expectedVersion) == 1;
  }

  @Override
  public boolean hasAgents(String name) {
    return agentModelJdbcStore.countAgentsByModelName(name) > 0;
  }

  @Override
  public boolean hasGroupsReferencing(String name) {
    return agentModelJdbcStore.countGroupsReferencingModel(name) > 0;
  }

  @Override
  public boolean hasVariantReferences(String name, String variant) {
    return agentModelJdbcStore.countVariantReferences(name, variant) > 0;
  }

  private AgentModelDO convert(AgentModel model) {
    if (model == null) {
      return null;
    }
    AgentModelDO result = new AgentModelDO();
    result.setName(model.getName());
    result.setKind(model.getKind() == null ? null : model.getKind().name());
    result.setProviderName(model.getProviderName());
    result.setModelId(model.getModelId());
    result.setDescription(model.getDescription());
    result.setConfigJson(model.getConfigJson());
    result.setGroupConfigJson(model.getGroupConfigJson());
    return result;
  }

  private AgentModel convert(AgentModelDO model) {
    if (model == null) {
      return null;
    }
    AgentModel result = new AgentModel();
    result.setName(model.getName());
    result.setKind(model.getKind() == null ? null : AgentModelKind.valueOf(model.getKind()));
    result.setProviderName(model.getProviderName());
    result.setModelId(model.getModelId());
    result.setDescription(model.getDescription());
    result.setConfigJson(model.getConfigJson());
    result.setGroupConfigJson(model.getGroupConfigJson());
    result.setVersion(model.getVersion());
    result.setCreateTime(model.getCreateTime());
    result.setUpdateTime(model.getUpdateTime());
    return result;
  }
}
