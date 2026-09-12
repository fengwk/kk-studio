package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * Environment Skill 来源操作创建请求 DTO。
 *
 * <p>调用方必须显式提供执行超时毫秒数 {@code timeoutMillis}，由服务端严格校验为正数且不得超过该操作对应 Management Capability Descriptor
 * 的上限；绝不静默截断。
 */
@Data
public class EnvironmentOperationCreateDTO {

  /** 本次操作执行超时的毫秒数（必填正数）。 */
  private Long timeoutMillis;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment operation field: " + fieldName);
  }
}
