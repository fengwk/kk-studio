package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Legacy Thread event journal DTO.
 *
 * <p>History REST API has been removed; retained for frontend timeline projection helpers and
 * residual RootActivity-adjacent contracts until live projection fully leaves event shapes.
 */
@Data
public class ThreadEventDTO {
  private String eventId;
  private String threadId;
  private String subjectEntryId;
  private String eventType;
  private String payloadJson;
  private LocalDateTime createTime;
}
