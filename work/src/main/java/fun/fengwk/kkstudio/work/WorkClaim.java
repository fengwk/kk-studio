package fun.fengwk.kkstudio.work;

import fun.fengwk.kkstudio.common.work.WorkState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 一次成功 claim 的围栏凭据：后续续租、完成与重排都必须原样回传。 */
public record WorkClaim(
    UUID targetId,
    long claimedWakeVersion,
    String leaseToken,
    Instant leaseUntil,
    UUID requiredEnvironmentId) {

  public WorkClaim {
    targetId = Objects.requireNonNull(targetId, "targetId");
    if (claimedWakeVersion <= 0) {
      throw new IllegalArgumentException("claimedWakeVersion must be positive");
    }
    leaseToken = WorkState.requireLeaseToken(leaseToken);
    leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
  }

  /** 无环境亲和性的 claim（ISSUE / CANVAS_RUN / Harness THREAD、MODEL 与 server-side TOOL）。 */
  public WorkClaim(UUID targetId, long claimedWakeVersion, String leaseToken, Instant leaseUntil) {
    this(targetId, claimedWakeVersion, leaseToken, leaseUntil, null);
  }
}
