package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;

/** Public Agent model representation with one structured executable {@link #config}. */
@Data
public class AgentModelDTO {

  private String providerName;
  private String name;
  private String description;
  private AgentModelConfigDTO config;

  /** Non-negative decimal string version; clients must echo on every update. */
  private String version;

  private Instant createTime;
  private Instant updateTime;
}
