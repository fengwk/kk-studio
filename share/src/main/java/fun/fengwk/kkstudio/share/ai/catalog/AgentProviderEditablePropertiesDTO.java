package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

import java.util.List;

/**
 * @author fengwk
 */
@Data
public class AgentProviderEditablePropertiesDTO {

  /** 可空描述（text，无长度上限）；null/空白视为清除。 */
  private String description;

  /**
   * 必填供应商类型，稳定 wire 值之一：{@code openai}、{@code openai_response}、{@code anthropic}、{@code google}。
   */
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

  /**
   * HTTP 重试白名单覆盖的三态编辑：省略字段保留既有覆盖，JSON null 清除覆盖（继承系统名单），数组完全替代系统名单；空数组表示该 Provider 不自动重试任何 HTTP 错误。
   *
   * <p>只允许 400–599 的整型状态，拒绝 null 元素、重复与非法值。
   */
  @Setter(AccessLevel.NONE)
  private List<Integer> modelHttpRetryStatusCodes;

  /** 请求体是否显式携带了 {@code modelHttpRetryStatusCodes} 字段（区分省略与显式 null）。 */
  @JsonIgnore
  @Setter(AccessLevel.NONE)
  private boolean modelHttpRetryStatusCodesProvided;

  /** 仅在请求体显式包含 {@code modelHttpRetryStatusCodes} 字段时被 Jackson 调用，因此可以区分省略与显式 null。 */
  @JsonSetter("modelHttpRetryStatusCodes")
  public void applyModelHttpRetryStatusCodes(List<Integer> statusCodes) {
    this.modelHttpRetryStatusCodesProvided = true;
    this.modelHttpRetryStatusCodes = statusCodes;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown Agent Provider field: " + name);
  }
}
