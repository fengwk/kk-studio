package fun.fengwk.kkstudio.share.ai.chat;

import lombok.Data;

/** Create request body for {@code /api/ai/chat}. */
@Data
public class ChatCreateDTO {

  private String title;

  /** Required existing Agent definition id. */
  private String defaultAgentId;
}
