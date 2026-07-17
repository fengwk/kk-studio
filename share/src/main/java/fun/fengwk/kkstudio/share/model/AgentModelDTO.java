package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Public global model representation. */
@Data
public class AgentModelDTO {

  private String id;
  private String providerId;
  private String name;
  private String description;
  private String capabilitiesJson;
  private String configJson;
  private Long version;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
