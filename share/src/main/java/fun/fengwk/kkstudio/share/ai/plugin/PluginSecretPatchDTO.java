package fun.fengwk.kkstudio.share.ai.plugin;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

/**
 * 单字段秘密补丁：{@code KEEP} / {@code SET} / {@code CLEAR} 三态。
 *
 * <p>{@code KEEP} 与 {@code CLEAR} 禁止携带 {@code value}；{@code SET} 必须给出非空、无
 * padding、有界的秘密值，且拒绝纯遮罩占位符。{@code value} 是只写字段：它绝不进入响应、{@link #toString()} 或任何日志。
 */
@Data
public class PluginSecretPatchDTO {

  /** 操作名：{@code KEEP}、{@code SET} 或 {@code CLEAR}。 */
  private String operation;

  /** {@code SET} 的新秘密值；KEEP/CLEAR 必须省略。 */
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @ToString.Exclude
  private String value;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown plugin secret patch field: " + fieldName);
  }
}
