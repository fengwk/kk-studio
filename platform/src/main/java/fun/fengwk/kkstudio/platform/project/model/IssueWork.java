package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** 按 Issue 领取的调度工作：每 Issue 最多单行，确定性 lease/wake 围栏。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueWork {

  private UUID issueId;
  private long wakeVersion;
  private Instant dueAt;
  private String leaseToken;
  private Instant leaseUntil;
  private Instant updatedAt;
}
