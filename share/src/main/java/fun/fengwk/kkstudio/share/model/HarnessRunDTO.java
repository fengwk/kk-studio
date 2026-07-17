package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Harness run query result; ids are decimal strings of the underlying bigint values. */
@Data
public class HarnessRunDTO {

  private String runId;
  private String sessionId;
  private String triggerEntryId;
  private String status;
  private Integer turnIndex;
  private Integer attempt;
  private Long eventSequence;
  private LocalDateTime nextAttemptAt;
  private LocalDateTime cancelRequestedAt;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime createTime;
  private LocalDateTime updateTime;
}
