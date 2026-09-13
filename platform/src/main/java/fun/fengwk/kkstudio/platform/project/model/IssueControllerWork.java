package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueControllerWork {

  private UUID issueId;
  private long wakeVersion;
  private Instant dueAt;
  private String leaseToken;
  private Instant leaseUntil;
  private Instant updatedAt;
}
