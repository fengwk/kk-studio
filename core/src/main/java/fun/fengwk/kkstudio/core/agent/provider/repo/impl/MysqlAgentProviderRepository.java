package fun.fengwk.kkstudio.core.agent.provider.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.repo.impl.mapper.AgentProviderMapper;
import fun.fengwk.kkstudio.core.agent.provider.repo.impl.model.AgentProviderDO;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

import java.time.Duration;
import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentProviderRepository implements AgentProviderRepository {

  private final AgentProviderMapper agentProviderMapper;

  @Override
  public Page<AgentProvider> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentProviderDO> result = agentProviderMapper.pageAll(offset, limit);
    long totalCount = agentProviderMapper.countAll();
    return Pages.page(pageQuery, result, totalCount).map(this::convert);
  }

  @Override
  public AgentProvider getById(long id) {
    return convert(agentProviderMapper.getById(id));
  }

  @Override
  public AgentProvider getByName(String name) {
    return convert(agentProviderMapper.getByName(name));
  }

  @Override
  public boolean create(AgentProvider provider) {
    return agentProviderMapper.insert(convert(provider)) == 1;
  }

  @Override
  public boolean updateById(AgentProvider provider) {
    return agentProviderMapper.updateById(convert(provider)) == 1;
  }

  @Override
  public boolean deleteById(long id) {
    return agentProviderMapper.deleteById(id) == 1;
  }

  @Override
  public boolean hasModels(long providerId) {
    return agentProviderMapper.countModelsByProviderId(providerId) > 0;
  }

  @Override
  public boolean hasAgents(long providerId) {
    return agentProviderMapper.countAgentsByProviderId(providerId) > 0;
  }

  private AgentProviderDO convert(AgentProvider provider) {
    if (provider == null) {
      return null;
    }
    AgentProviderDO providerDO = new AgentProviderDO();
    providerDO.setId(provider.getId());
    providerDO.setName(provider.getName());
    providerDO.setDescription(provider.getDescription());
    providerDO.setProviderType(provider.getProviderType());
    providerDO.setBaseUrl(provider.getBaseUrl());
    providerDO.setApiKey(provider.getApiKey());
    providerDO.setTimeoutMillis(toMillis(provider.getTimeout()));
    return providerDO;
  }

  private AgentProvider convert(AgentProviderDO providerDO) {
    if (providerDO == null) {
      return null;
    }
    AgentProvider provider = new AgentProvider();
    provider.setId(providerDO.getId());
    provider.setName(providerDO.getName());
    provider.setDescription(providerDO.getDescription());
    provider.setProviderType(providerDO.getProviderType());
    provider.setBaseUrl(providerDO.getBaseUrl());
    provider.setApiKey(providerDO.getApiKey());
    provider.setTimeout(toDuration(providerDO.getTimeoutMillis()));
    provider.setCreateTime(providerDO.getCreateTime());
    provider.setUpdateTime(providerDO.getUpdateTime());
    return provider;
  }

  private Long toMillis(Duration duration) {
    return duration == null ? null : duration.toMillis();
  }

  private Duration toDuration(Long millis) {
    return millis == null ? null : Duration.ofMillis(millis);
  }
}
