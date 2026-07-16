package fun.fengwk.kkstudio.share.model;

import java.time.Instant;
import lombok.Data;

/**
 * T15 Run event query projection. The SSE event id must equal the decimal string of {@code sequence},
 * not the Snowflake event id, so clients can replay using the same cursor.
 */
@Data
public class RunEventDTO {
  private String eventId;
  private String runId;
  private Long sequence;
  private String type;
  private String payloadJson;
  private Instant createTime;
}