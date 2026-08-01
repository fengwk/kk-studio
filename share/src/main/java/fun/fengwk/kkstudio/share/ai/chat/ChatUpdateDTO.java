package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * Update request body for {@code /api/ai/chat/{id}}.
 *
 * <p>Partial update: omitted fields preserve the current value. A JSON {@code null} for {@code
 * environmentName} explicitly clears it; a supplied non-null value is validated only as a canonical
 * name. Title is required when supplied; a supplied {@code agentName} must be a non-blank existing
 * Agent definition name. {@link #expectedVersion} is required on every update.
 */
@Data
public class ChatUpdateDTO {

  private String title;
  private String agentName;
  private String environmentName;
  @JsonIgnore private boolean environmentNameProvided;
  private Boolean yoloEnabled;
  @JsonIgnore private boolean yoloEnabledProvided;

  /**
   * Tracks JSON field presence so PUT can distinguish omitted from an explicit {@code null}.
   *
   * <p>The same setter is used by programmatic callers, which makes {@code setX(null)} the explicit
   * clear operation as well.
   */
  public void setEnvironmentName(String environmentName) {
    this.environmentName = environmentName;
    this.environmentNameProvided = true;
  }

  @JsonIgnore
  public boolean isEnvironmentNameProvided() {
    return environmentNameProvided;
  }

  public void setYoloEnabled(Boolean yoloEnabled) {
    this.yoloEnabled = yoloEnabled;
    this.yoloEnabledProvided = true;
  }

  @JsonIgnore
  public boolean isYoloEnabledProvided() {
    return yoloEnabledProvided;
  }

  /** Required non-negative decimal string; must match the current chat version. */
  private String expectedVersion;
}
