package fun.fengwk.kkstudio.harness.runtime.work;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;

/**
 * 已 claim Work 行的不可变快照：target、worker 必须随 lease token 一同回传到 {@link Work#complete(String, long,
 * Instant)} / {@link Work#reschedule(String, long, Instant, Instant)} 的 claimed wakeVersion，以及
 * lease horizon 与可选环境亲和性。
 */
public record ClaimedWork(
    WorkTarget target,
    long claimedWakeVersion,
    String leaseToken,
    Instant leaseUntil,
    EnvironmentId requiredEnvironmentId) {

  public ClaimedWork {
    target = Objects.requireNonNull(target, "target");
    if (claimedWakeVersion <= 0) {
      throw new IllegalArgumentException("claimedWakeVersion must be positive");
    }
    leaseToken = WorkValues.requireCanonicalToken(leaseToken);
    leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
    if (requiredEnvironmentId != null && target.type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "requiredEnvironmentId must be null for target type " + target.type());
    }
  }

  /** 4 参数便捷构造：无环境亲和性（THREAD/MODEL 或 server-side TOOL）。 */
  public ClaimedWork(
      WorkTarget target, long claimedWakeVersion, String leaseToken, Instant leaseUntil) {
    this(target, claimedWakeVersion, leaseToken, leaseUntil, null);
  }
}
