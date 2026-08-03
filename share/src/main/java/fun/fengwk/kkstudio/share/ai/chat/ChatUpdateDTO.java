package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * Update request body for {@code /api/ai/chat/{id}}.
 *
 * <p>Partial update: omitted fields preserve the current value. Title is required when supplied; a
 * supplied {@code agentName} must be a non-blank existing Agent definition name. {@link
 * #expectedVersion} is required on every update.
 */
@Data
public class ChatUpdateDTO {

  private String title;
  private String agentName;
  private Boolean yoloEnabled;
  @JsonIgnore private boolean yoloEnabledProvided;

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
