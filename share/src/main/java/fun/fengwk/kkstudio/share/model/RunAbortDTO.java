package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Abort 调用结果投影；所有 bigint ID 以字符串传输。 */
@Data
public class RunAbortDTO {

  private String sessionId;
  private String runId;
  private boolean newlyRequested;
  private String status;
  private LocalDateTime requestedAt;
}
