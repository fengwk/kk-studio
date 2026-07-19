package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Attach request body for {@code /api/chats/{id}/sessions}. */
@Data
public class ChatSessionAttachDTO {

  private String sessionId;
}
