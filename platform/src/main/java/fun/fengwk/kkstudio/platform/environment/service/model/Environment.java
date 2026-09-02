package fun.fengwk.kkstudio.platform.environment.service.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** 稳定 Environment Card 领域模型。 */
@Data
public class Environment {
  private UUID id;
  private String name;
  private String registrationToken;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
