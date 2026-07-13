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

import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentProviderRepository implements AgentProviderRepository {

  private final AgentProviderMapper agentProviderMapper;

  @Override
  public Page<AgentProvider> page(long workspaceId, PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentProviderDO> results = agentProviderMapper.pageByWorkspaceId(workspaceId, offset, limit);
    return Pages.page(pageQuery, results, agentProviderMapper.countByWorkspaceId(workspaceId))
        .map(this::convert);
  }

  @Override
  public AgentProvider getById(long id) {
    return convert(agentProviderMapper.getById(id));
  }

  @Override
  public AgentProvider getByWorkspaceIdAndId(long workspaceId, long id) {
    return convert(agentProviderMapper.getByWorkspaceIdAndId(workspaceId, id));
  }

  @Override
  public AgentProvider getByWorkspaceIdAndName(long workspaceId, String name) {
    return convert(agentProviderMapper.getByWorkspaceIdAndName(workspaceId, name));
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
  public boolean deleteByWorkspaceIdAndId(long workspaceId, long id) {
    return agentProviderMapper.deleteByWorkspaceIdAndId(workspaceId, id) == 1;
  }

  @Override
  public boolean hasModels(long workspaceId, long providerId) {
    return agentProviderMapper.countModelsByProviderId(workspaceId, providerId) > 0;
  }

  private AgentProviderDO convert(AgentProvider provider) {
    if (provider == null) {
      return null;
    }
    AgentProviderDO result = new AgentProviderDO();
    result.setId(provider.getId());
    result.setWorkspaceId(provider.getWorkspaceId());
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
    result.setId(provider.getId());
    result.setWorkspaceId(provider.getWorkspaceId());
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
