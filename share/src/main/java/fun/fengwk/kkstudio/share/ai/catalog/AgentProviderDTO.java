package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;

/** Public provider representation without credentials. */
@Data
public class AgentProviderDTO {

  private String name;
  private String description;
  private String providerType;
  private String baseUrl;
  private boolean configured;
  private Long modelCallTimeoutMillis;
  private Long modelCallIdleTimeoutMillis;

  /** Non-negative decimal string version; clients must echo on every update. */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
