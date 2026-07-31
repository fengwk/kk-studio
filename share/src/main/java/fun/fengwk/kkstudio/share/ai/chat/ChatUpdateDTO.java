package fun.fengwk.kkstudio.share.ai.chat;

import lombok.Data;

/**
 * Update request body for {@code /api/ai/chat/{id}}.
 *
 * <p>Partial update: {@code null} preserves the current value. Title is required when supplied; a
 * supplied {@code defaultAgentId} must be a non-blank existing Agent definition id. {@link
 * #expectedVersion} is required on every update.
 */
@Data
public class ChatUpdateDTO {

  private String title;
  private String defaultAgentId;

  /** Required non-negative decimal string; must match the current chat version. */
  private String expectedVersion;
}
