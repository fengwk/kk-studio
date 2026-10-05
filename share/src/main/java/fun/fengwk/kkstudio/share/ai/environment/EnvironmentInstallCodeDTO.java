package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.time.Instant;

/** 五分钟有效的安装 code；不持久化，轮换 registrationToken 后自然失效。 */
@Data
public class EnvironmentInstallCodeDTO {
  private String code;
  private Instant expiresAt;
}
