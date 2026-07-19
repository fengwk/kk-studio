package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Public provider representation without credentials. */
@Data
public class AgentProviderDTO {

  private String id;
  private String name;
  private String description;
  private String providerType;
  private String baseUrl;
  private boolean configured;
  private Long modelCallTimeoutMillis;
  private Long modelCallIdleTimeoutMillis;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
