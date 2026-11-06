package fun.fengwk.kkstudio.platform.environment.dto;

import lombok.Data;

/** 轮换 Environment registrationToken 请求 DTO。 */
@Data
public class EnvironmentRotateTokenDTO {

  /** 期望的乐观锁版本。 */
  private Long expectedVersion;
}
