package fun.fengwk.kkstudio.platform.environment;

import lombok.Data;

import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallConfigDTO;

import java.time.Instant;
import java.util.UUID;

/** 稳定 Environment 领域模型。 */
@Data
public class Environment {
  private UUID id;
  private String name;
  private String registrationTokenHash;
  private EnvironmentInstallConfigDTO installConfig;
  private Long version;
  private Instant createTime;
  private Instant updateTime;
}
