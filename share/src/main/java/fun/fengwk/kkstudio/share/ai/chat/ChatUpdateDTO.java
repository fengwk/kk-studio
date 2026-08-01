package fun.fengwk.kkstudio.share.ai.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * Update request body for {@code /api/ai/chat/{id}}.
 *
 * <p>Partial update: omitted fields preserve the current value. A JSON {@code null} for {@code
 * defaultEnvironmentName} explicitly clears it; a supplied non-null value is validated only as a
 * canonical name. Title is required when supplied; a supplied {@code defaultAgentId} must be a
 * non-blank existing Agent definition id. {@link #expectedVersion} is required on every update.
 */
@Data
public class ChatUpdateDTO {

  private String title;
  private String defaultAgentId;
  private String defaultEnvironmentName;
  @JsonIgnore private boolean defaultEnvironmentNameProvided;

  /**
   * Tracks JSON field presence so PUT can distinguish omitted from an explicit {@code null}.
   *
   * <p>The same setter is used by programmatic callers, which makes {@code setX(null)} the explicit
   * clear operation as well.
   */
  public void setDefaultEnvironmentName(String defaultEnvironmentName) {
    this.defaultEnvironmentName = defaultEnvironmentName;
    this.defaultEnvironmentNameProvided = true;
  }

  @JsonIgnore
  public boolean isDefaultEnvironmentNameProvided() {
    return defaultEnvironmentNameProvided;
  }

  /** Required non-negative decimal string; must match the current chat version. */
  private String expectedVersion;
}
