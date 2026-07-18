package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Cursorable root-session tree activity event. All Snowflake IDs are serialized as strings. */
@Data
public class RootActivityDTO {
  private String rootSessionId;
  private String sessionId;
  private String threadId;
  private String eventId;
  private String eventType;
  private String payloadJson;
  private LocalDateTime createTime;
}
