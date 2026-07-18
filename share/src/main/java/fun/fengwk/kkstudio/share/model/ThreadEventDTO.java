package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Thread 事件 journal；eventId 即 SSE cursor。 */
@Data
public class ThreadEventDTO {
  private String eventId;
  private String threadId;
  private String subjectEntryId;
  private String eventType;
  private String payloadJson;
  private LocalDateTime createTime;
}
