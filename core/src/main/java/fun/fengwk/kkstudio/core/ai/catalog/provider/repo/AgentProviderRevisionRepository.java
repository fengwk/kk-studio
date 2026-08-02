package fun.fengwk.kkstudio.core.ai.catalog.provider.repo;

import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProviderRevision;

/** Append-only provider revision repository. */
public interface AgentProviderRevisionRepository {

  boolean create(AgentProviderRevision revision);

  AgentProviderRevision getByProviderNameAndVersion(String providerName, long providerVersion);
}
