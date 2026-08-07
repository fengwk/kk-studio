package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * {@code /api/ai/chat/{id}} 的更新请求体。
 *
 * <p>部分更新：省略的字段保留当前值。提供 {@code title} 时必填；提供的 {@code agentName} 必须是非空白且已存在的 Agent definition
 * 名。{@link #expectedVersion} 每次更新必填。
 */
@Data
public class ChatUpdateDTO {

  /** 部分更新：省略（null）保留当前值；提供时必须为非空白（trim 后）且 ≤256 字符。 */
  private String title;

  /** 部分更新：省略（null）保留当前值；提供时必须为已存在的 Agent definition 名（约束同创建）。 */
  private String agentName;

  /**
   * 部分更新：仅在 {@link #environmentNameProvided} 为 true 时生效；提供时必须为 canonical Environment 逻辑路由名称， 显式
   * null 表示清除默认环境。
   */
  private String environmentName;

  /**
   * 内部序列化控制标记（{@code @JsonIgnore}，不参与 HTTP 契约）：由 {@link #setEnvironmentName} 自动置位，用于区分 JSON 中缺省
   * environmentName 与显式 null。
   */
  @JsonIgnore private boolean environmentNameProvided;

  public void setEnvironmentName(String environmentName) {
    this.environmentName = environmentName;
    this.environmentNameProvided = true;
  }

  @JsonIgnore
  public boolean isEnvironmentNameProvided() {
    return environmentNameProvided;
  }

  /** 部分更新：仅在 {@link #yoloEnabledProvided} 为 true 时生效，且显式提供时不得为 null。 */
  private Boolean yoloEnabled;

  /**
   * 内部序列化控制标记（{@code @JsonIgnore}，不参与 HTTP 契约）：由 {@link #setYoloEnabled} 自动置位，用于区分 JSON 中缺省
   * yoloEnabled 与显式 null。
   */
  @JsonIgnore private boolean yoloEnabledProvided;

  public void setYoloEnabled(Boolean yoloEnabled) {
    this.yoloEnabled = yoloEnabled;
    this.yoloEnabledProvided = true;
  }

  @JsonIgnore
  public boolean isYoloEnabledProvided() {
    return yoloEnabledProvided;
  }

  /** 必填非负十进制字符串；必须与当前 Chat 版本一致。 */
  private String expectedVersion;
}
