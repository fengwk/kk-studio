package fun.fengwk.kkstudio.share.model;

import java.time.Instant;
import lombok.Data;

/** Cursorable root-session tree activity event. All Snowflake IDs are serialized as strings. */
@Data
public class RootActivityDTO {
  private String workspaceId;
  private String rootSessionId;
  private String sessionId;
  private String runId;
  private String eventId;
  private Long sequence;
  private String type;
  private String payloadJson;
  private Instant createTime;
}
