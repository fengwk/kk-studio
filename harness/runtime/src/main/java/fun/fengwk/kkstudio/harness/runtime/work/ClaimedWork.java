package fun.fengwk.kkstudio.harness.runtime.work;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of a claimed Work row: target, the claimed wakeVersion the worker must pass
 * back to {@link Work#complete(String, long, Instant)} / {@link Work#reschedule(String, long,
 * Instant, Instant)} together with the lease token, and the lease horizon.
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
