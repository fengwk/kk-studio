package fun.fengwk.kkstudio.platform.environment;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;

import java.time.Instant;
import java.util.UUID;

/** 稳定 Environment 卡片领域模型：名称不可变，备注与完整 Daemon 配置共用 CAS 版本。 */
@Data
public class Environment {
  private UUID id;
  private String name;
  private @Nullable String note;
  private String registrationTokenHash;
  private DaemonConfigDTO daemonConfig;
  private Long version;
  private Instant createdAt;
  private Instant updatedAt;
}
