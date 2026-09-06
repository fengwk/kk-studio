package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 轮换 Environment registrationToken 请求 DTO。 */
@Data
public class EnvironmentRotateTokenDTO {

  /** 期望的乐观锁版本。 */
  private String expectedVersion;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment field: " + fieldName);
  }
}
