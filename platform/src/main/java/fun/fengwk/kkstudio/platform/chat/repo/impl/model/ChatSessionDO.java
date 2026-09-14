package fun.fengwk.kkstudio.platform.chat.repo.impl.model;

import lombok.Data;

import java.util.UUID;

/** {@code session_owner} 的 Chat 归属行映射。 */
@Data
public class ChatSessionDO {
  private UUID sessionId;
  private UUID chatId;
}
