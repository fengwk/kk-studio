package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.time.Instant;

/** 五分钟有效的安装 code；明文只返回一次，数据库仅保存摘要与失效时间。 */
@Data
public class EnvironmentInstallCodeDTO {
  private String code;
  private Instant expiresAt;
}
