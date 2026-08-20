package fun.fengwk.kkstudio.core.ai.chat.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code chat_session} 行映射。 */
@Data
public class ChatSessionDO {
  private UUID sessionId;
  private UUID chatId;
}
