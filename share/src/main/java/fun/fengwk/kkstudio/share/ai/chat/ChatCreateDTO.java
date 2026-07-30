package fun.fengwk.kkstudio.share.ai.chat;

import lombok.Data;

/** Create request body for {@code /api/ai/chat}. Title and defaultAgentId are optional. */
@Data
public class ChatCreateDTO {

  private String title;
  private String defaultAgentId;
}
