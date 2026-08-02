package fun.fengwk.kkstudio.core.ai.catalog.provider.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.time.Instant;

/** Immutable provider connection snapshot used by runtime dispatch. */
@Data
public class AgentProviderRevision {

  private String providerName;
  private Long providerVersion;
  private AgentProviderType providerType;
  private String baseUrl;
  private String credential;
  private String configJson;
  private Instant createTime;
}
