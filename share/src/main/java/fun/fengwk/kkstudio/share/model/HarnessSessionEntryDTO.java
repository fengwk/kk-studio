package fun.fengwk.kkstudio.share.model;

import java.time.LocalDateTime;
import lombok.Data;

/** Harness session entry projection; payload is forwarded as raw JSON. */
@Data
public class HarnessSessionEntryDTO {

  private String sessionEntryId;
  private String sessionId;
  private String parentEntryId;
  private String runId;
  private String entryType;
  private String payloadJson;
  private LocalDateTime createTime;
}
