package fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRevisionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl.mapper.AgentProviderRevisionMapper;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl.model.AgentProviderRevisionDO;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProviderRevision;

/** PostgreSQL-backed append-only provider revision repository. */
@AllArgsConstructor
@Repository
public class PostgresqlAgentProviderRevisionRepository implements AgentProviderRevisionRepository {

  private final AgentProviderRevisionMapper agentProviderRevisionMapper;

  @Override
  public boolean create(AgentProviderRevision revision) {
    return agentProviderRevisionMapper.insert(convert(revision)) == 1;
  }

  @Override
  public AgentProviderRevision getByProviderNameAndVersion(
      String providerName, long providerVersion) {
    return convert(
        agentProviderRevisionMapper.getByProviderNameAndVersion(providerName, providerVersion));
  }

  private AgentProviderRevisionDO convert(AgentProviderRevision revision) {
    if (revision == null) {
      return null;
    }
    AgentProviderRevisionDO result = new AgentProviderRevisionDO();
    result.setProviderName(revision.getProviderName());
    result.setProviderVersion(revision.getProviderVersion());
    result.setProviderType(revision.getProviderType());
    result.setBaseUrl(revision.getBaseUrl());
    result.setCredential(revision.getCredential());
    result.setConfigJson(revision.getConfigJson());
    return result;
  }

  private AgentProviderRevision convert(AgentProviderRevisionDO revision) {
    if (revision == null) {
      return null;
    }
    AgentProviderRevision result = new AgentProviderRevision();
    result.setProviderName(revision.getProviderName());
    result.setProviderVersion(revision.getProviderVersion());
    result.setProviderType(revision.getProviderType());
    result.setBaseUrl(revision.getBaseUrl());
    result.setCredential(revision.getCredential());
    result.setConfigJson(revision.getConfigJson());
    result.setCreateTime(revision.getCreateTime());
    return result;
  }
}
