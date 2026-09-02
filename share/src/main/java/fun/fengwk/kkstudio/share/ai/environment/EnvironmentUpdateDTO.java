package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** 更新 Environment 请求 DTO。 */
@Data
public class EnvironmentUpdateDTO {

  /** 必填新环境名称（<= 64 字符，无空白或斜杠）。 */
  private String name;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment field: " + fieldName);
  }
}
