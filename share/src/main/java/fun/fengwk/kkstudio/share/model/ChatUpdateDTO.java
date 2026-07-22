package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/**
 * Update request body for {@code /api/chats/{id}}.
 *
 * <p>Partial update: {@code null} preserves the current value. Title is required when supplied;
 * blank {@code defaultAgentId} clears that optional field.
 */
@Data
public class ChatUpdateDTO {

  private String title;
  private String defaultAgentId;
}
