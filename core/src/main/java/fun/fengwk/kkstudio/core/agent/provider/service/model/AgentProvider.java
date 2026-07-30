package fun.fengwk.kkstudio.core.agent.provider.service.model;

import lombok.Data;

import fun.fengwk.kkstudio.share.model.AgentProviderType;

import java.time.Instant;

/** Global Agent provider resource. */
@Data
public class AgentProvider {

  private Long id;
  private String name;
  private String description;
  private AgentProviderType providerType;
  private String baseUrl;
  private String credential;
  private String configJson;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
