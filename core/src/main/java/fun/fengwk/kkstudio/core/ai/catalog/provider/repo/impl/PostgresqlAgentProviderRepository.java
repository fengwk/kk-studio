package fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl.mapper.AgentProviderMapper;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl.model.AgentProviderDO;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;

import java.util.List;

/** PostgreSQL-backed global provider repository. */
@AllArgsConstructor
@Repository
public class PostgresqlAgentProviderRepository implements AgentProviderRepository {

  private final AgentProviderMapper agentProviderMapper;

  @Override
  public Page<AgentProvider> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentProviderDO> results = agentProviderMapper.page(offset, limit);
    return Pages.page(pageQuery, results, agentProviderMapper.count()).map(this::convert);
  }

  @Override
  public AgentProvider getByName(String name) {
    return convert(agentProviderMapper.getByName(name));
  }

  @Override
  public AgentProvider getByNameForUpdate(String name) {
    return convert(agentProviderMapper.getByNameForUpdate(name));
  }

  @Override
  public boolean create(AgentProvider provider) {
    return agentProviderMapper.insert(convert(provider)) == 1;
  }

  @Override
  public boolean updateByName(AgentProvider provider, long expectedVersion) {
    return agentProviderMapper.updateByName(convert(provider), expectedVersion) == 1;
  }

  @Override
  public boolean deleteByName(String name, long expectedVersion) {
    return agentProviderMapper.deleteByName(name, expectedVersion) == 1;
  }

  @Override
  public boolean hasModels(String providerName) {
    return agentProviderMapper.countModelsByProviderName(providerName) > 0;
  }

  private AgentProviderDO convert(AgentProvider provider) {
    if (provider == null) {
      return null;
    }
    AgentProviderDO result = new AgentProviderDO();
    result.setName(provider.getName());
    result.setDescription(provider.getDescription());
    result.setProviderType(provider.getProviderType());
    result.setBaseUrl(provider.getBaseUrl());
    result.setCredential(provider.getCredential());
    result.setConfigJson(provider.getConfigJson());
    return result;
  }

  private AgentProvider convert(AgentProviderDO provider) {
    if (provider == null) {
      return null;
    }
    AgentProvider result = new AgentProvider();
    result.setName(provider.getName());
    result.setDescription(provider.getDescription());
    result.setProviderType(provider.getProviderType());
    result.setBaseUrl(provider.getBaseUrl());
    result.setCredential(provider.getCredential());
    result.setConfigJson(provider.getConfigJson());
    result.setVersion(provider.getVersion());
    result.setCreateTime(provider.getCreateTime());
    result.setUpdateTime(provider.getUpdateTime());
    return result;
  }
}
