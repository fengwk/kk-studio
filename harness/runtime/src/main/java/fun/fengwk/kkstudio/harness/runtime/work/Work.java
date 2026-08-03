package fun.fengwk.kkstudio.harness.runtime.work;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable current scheduling state of one Work mailbox target.
 *
 * <p>{@code wakeVersion} increases on every request and prevents an old Processor completion from
 * deleting a newer wake; {@code leaseToken}/{@code leaseUntil} fence stale workers and are always
 * both present or both absent. All transitions are pure: they return the next state or {@link
 * Optional#empty()} for deletion and never mutate the receiver.
 */
public record Work(
    WorkTarget target,
    Instant availableAt,
    long wakeVersion,
    String leaseToken,
    Instant leaseUntil) {

  public Work {
    target = Objects.requireNonNull(target, "target");
    availableAt = Objects.requireNonNull(availableAt, "availableAt");
    if (wakeVersion <= 0) {
      throw new IllegalArgumentException("wakeVersion must be positive");
    }
    if (leaseToken != null) {
      leaseToken = WorkValues.requireCanonicalToken(leaseToken);
    }
    if ((leaseToken == null) != (leaseUntil == null)) {
      throw new IllegalArgumentException(
          "leaseToken and leaseUntil must both be present or both be absent");
    }
  }

  /** Creates the first wake of a target: wakeVersion 1, no lease. */
  public static Work initial(WorkTarget target, Instant requestedAt) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedAt, "requestedAt");
    return new Work(target, requestedAt, 1L, null, null);
  }

  /**
   * Requests another wake: increments {@code wakeVersion}, pulls {@code availableAt} forward to the
   * minimum of the current and requested times and preserves the current lease.
   */
  public Work request(Instant requestedAt) {
    Objects.requireNonNull(requestedAt, "requestedAt");
    long nextWakeVersion = Math.addExact(wakeVersion, 1L);
    Instant nextAvailableAt = availableAt.isAfter(requestedAt) ? requestedAt : availableAt;
    return new Work(target, nextAvailableAt, nextWakeVersion, leaseToken, leaseUntil);
  }

  /**
   * Claims the work for {@code until} with a fresh token. Only allowed when {@code availableAt <=
   * now} and there is no active lease (none or already expired at {@code now}); {@code until} must
   * be strictly after {@code now}.
   */
  public Work claim(Instant now, String token, Instant until) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(until, "until");
    String canonicalToken = WorkValues.requireCanonicalToken(token);
    if (availableAt.isAfter(now)) {
      throw new IllegalArgumentException("work is not available yet");
    }
    if (hasActiveLeaseAt(now)) {
      throw new IllegalArgumentException("work has an active lease");
    }
    if (!until.isAfter(now)) {
      throw new IllegalArgumentException("lease until must be after now");
    }
    return new Work(target, availableAt, wakeVersion, canonicalToken, until);
  }

  /**
   * Extends the current lease while it is still active at {@code now}; {@code until} must strictly
   * extend the current {@code leaseUntil}. A stale lease (expired at or before {@code now}) is
   * rejected.
   */
  public Work renew(String token, Instant now, Instant until) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(until, "until");
    requireTokenMatch(token);
    requireActiveLeaseAt(now);
    if (!until.isAfter(leaseUntil)) {
      throw new IllegalArgumentException("renew must strictly extend the current lease");
    }
    return new Work(target, availableAt, wakeVersion, leaseToken, until);
  }

  /**
   * Completes processing for {@code claimedWakeVersion} while the current lease is active at {@code
   * now}: an equal claim deletes the work (returns {@link Optional#empty()}); a stale claim clears
   * only the lease and keeps the row so a newer wake remains visible; a future claim is invalid.
   */
  public Optional<Work> complete(String token, long claimedWakeVersion, Instant now) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(now, "now");
    requireTokenMatch(token);
    if (claimedWakeVersion <= 0) {
      throw new IllegalArgumentException("claimedWakeVersion must be positive");
    }
    requireActiveLeaseAt(now);
    if (claimedWakeVersion > wakeVersion) {
      throw new IllegalArgumentException("claimed wakeVersion must not exceed current");
    }
    if (claimedWakeVersion == wakeVersion) {
      return Optional.empty();
    }
    return Optional.of(new Work(target, availableAt, wakeVersion, null, null));
  }

  /**
   * Reschedules processing for {@code claimedWakeVersion} while the current lease is active at
   * {@code now}: an equal claim sets {@code availableAt} to {@code requestedAt}; a stale claim only
   * pulls {@code availableAt} forward to the minimum; a future claim is invalid. The lease is
   * always cleared.
   */
  public Work reschedule(String token, long claimedWakeVersion, Instant now, Instant requestedAt) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(requestedAt, "requestedAt");
    requireTokenMatch(token);
    if (claimedWakeVersion <= 0) {
      throw new IllegalArgumentException("claimedWakeVersion must be positive");
    }
    requireActiveLeaseAt(now);
    if (claimedWakeVersion > wakeVersion) {
      throw new IllegalArgumentException("claimed wakeVersion must not exceed current");
    }
    Instant nextAvailableAt;
    if (claimedWakeVersion == wakeVersion) {
      nextAvailableAt = requestedAt;
    } else {
      nextAvailableAt = availableAt.isAfter(requestedAt) ? requestedAt : availableAt;
    }
    return new Work(target, nextAvailableAt, wakeVersion, null, null);
  }

  private boolean hasActiveLeaseAt(Instant now) {
    return leaseUntil != null && leaseUntil.isAfter(now);
  }

  private void requireActiveLeaseAt(Instant now) {
    if (!leaseUntil.isAfter(now)) {
      throw new IllegalArgumentException("lease is stale at now");
    }
  }

  private void requireTokenMatch(String token) {
    if (leaseToken == null || !leaseToken.equals(token)) {
      throw new IllegalArgumentException("lease token mismatch");
    }
  }
}
