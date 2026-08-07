package fun.fengwk.kkstudio.harness.runtime.work;

import java.time.Instant;
import java.util.Objects;

/**
 * 已 claim Work 行的不可变快照：target、worker 必须随 lease token 一同回传到 {@link Work#complete(String, long,
 * Instant)} / {@link Work#reschedule(String, long, Instant, Instant)} 的 claimed wakeVersion，以及
 * lease horizon。
 */
public record ClaimedWork(
    WorkTarget target, long claimedWakeVersion, String leaseToken, Instant leaseUntil) {

  public ClaimedWork {
    target = Objects.requireNonNull(target, "target");
    if (claimedWakeVersion <= 0) {
      throw new IllegalArgumentException("claimedWakeVersion must be positive");
    }
    leaseToken = WorkValues.requireCanonicalToken(leaseToken);
    leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil");
  }
}
