package fun.fengwk.kkstudio.core.ai.catalog.provider.repo.impl.model;

import lombok.Data;

import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.time.Instant;

/** {@code agent_provider_revision} row mapping for immutable provider dispatch facts. */
@Data
public class AgentProviderRevisionDO {

  private String providerName;
  private Long providerVersion;
  private AgentProviderType providerType;
  private String baseUrl;
  private String credential;
  private String configJson;
  private Instant createTime;
}
