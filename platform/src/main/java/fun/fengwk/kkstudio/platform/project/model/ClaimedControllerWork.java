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
public class ClaimedControllerWork {

  private UUID issueId;
  private long claimedWakeVersion;
  private String leaseToken;
  private Instant leaseUntil;
}
