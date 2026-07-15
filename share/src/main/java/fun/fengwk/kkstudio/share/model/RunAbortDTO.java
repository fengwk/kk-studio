package fun.fengwk.kkstudio.share.model;

import java.time.LocalDateTime;
import lombok.Data;

/** Abort 调用结果投影；所有 bigint ID 以字符串传输。 */
@Data
public class RunAbortDTO {

  private String sessionId;
  private String runId;
  private boolean newlyRequested;
  private String status;
  private LocalDateTime requestedAt;
}
