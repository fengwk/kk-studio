package fun.fengwk.kkstudio.platform.project.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** 已领取的 Issue work：携带围栏令牌与租约，后续推进必须复核与其一致。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimedIssueWork {

  private UUID issueId;
  private long claimedWakeVersion;
  private String leaseToken;
  private Instant leaseUntil;
}
