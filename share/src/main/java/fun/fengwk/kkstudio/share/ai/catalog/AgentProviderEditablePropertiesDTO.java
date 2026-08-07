package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class AgentProviderEditablePropertiesDTO {

  /** 可空描述（≤512 字符）；null/空白视为清除。 */
  private String description;

  /** 必填供应商类型，取 {@link AgentProviderType} 枚举名（openai/openai_response/anthropic/google）。 */
  private String providerType;

  /** 可空模型 API base URL（≤512 字符）。 */
  private String baseUrl;

  /** 凭证（API key 等）：创建时必填；更新时 null 表示保留原值；永不通过公开 DTO 回读（{@code WRITE_ONLY}）。 */
  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  private String credential;

  /** 可空模型调用总超时（毫秒，正数）；null 保留当前值（创建时为产品默认）。 */
  private Long modelCallTimeoutMillis;

  /** 可空模型调用空闲超时（毫秒，正数）；null 保留当前值（创建时为产品默认）。 */
  private Long modelCallIdleTimeoutMillis;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Provider field: " + name);
  }
}
