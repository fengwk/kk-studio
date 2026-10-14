package fun.fengwk.kkstudio.platform.environment.repo.impl.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code environment} 行映射：稳定 Environment Card。 */
@Data
public class EnvironmentDO {
  private UUID id;
  private String name;
  private String registrationToken;
  private String installConfigJson;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
