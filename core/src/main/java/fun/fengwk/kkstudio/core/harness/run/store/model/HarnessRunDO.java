package fun.fengwk.kkstudio.core.harness.run.store.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class HarnessRunDO {
  private Long id;
  private Long sessionId;
  private Long triggerEntryId;
  private String status;
  private Integer turnIndex;
  private Integer attempt;
  private Long eventSequence;
  private String leaseOwner;
  private LocalDateTime leaseUntil;
  private LocalDateTime nextAttemptAt;
  private LocalDateTime cancelRequestedAt;
  private LocalDateTime createTime;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime updateTime;
}
