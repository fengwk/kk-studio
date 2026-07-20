package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Public Agent model representation. The structured {@link #config} is the single source of truth;
 * no {@code capabilitiesJson} or {@code configJson} is ever exposed by the API.
 */
@Data
public class AgentModelDTO {

  private String id;
  private String providerId;
  private String name;
  private String description;
  private AgentModelConfigDTO config;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
