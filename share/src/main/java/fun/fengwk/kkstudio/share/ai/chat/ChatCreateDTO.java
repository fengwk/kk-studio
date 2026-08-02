package fun.fengwk.kkstudio.share.ai.chat;

import lombok.Data;

/** Create request body for {@code /api/ai/chat}. */
@Data
public class ChatCreateDTO {

  private String title;

  /** Required existing Agent definition name. */
  private String agentName;

  /** Optional sending permission mode; omission uses the configured product default. */
  private Boolean yoloEnabled;
}
