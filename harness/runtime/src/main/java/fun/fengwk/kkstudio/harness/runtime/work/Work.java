package fun.fengwk.kkstudio.harness.runtime.work;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Work mailbox target 的 durable 当前调度状态。
 *
 * <p>每次 request 时 {@code wakeVersion} 递增，从而阻止旧的 Processor 完成删除一个更新的 wake； {@code leaseToken}/{@code
 * leaseUntil} fence 掉过期的 worker，且两者总是同时存在或同时缺失。 所有转换都是纯函数：返回下一个状态，或返回 {@link Optional#empty()}
 * 表示删除，绝不会修改接收者。
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

  /** 创建 target 的首次 wake：wakeVersion 为 1，无 lease。 */
  public static Work initial(WorkTarget target, Instant requestedAt) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedAt, "requestedAt");
    return new Work(target, requestedAt, 1L, null, null);
  }

  /**
   * 请求另一次 wake：{@code wakeVersion} 递增，并将 {@code availableAt} 提前到当前时间与 requested time 的最小值，保留当前
   * lease。
   */
  public Work request(Instant requestedAt) {
    Objects.requireNonNull(requestedAt, "requestedAt");
    long nextWakeVersion = Math.addExact(wakeVersion, 1L);
    Instant nextAvailableAt = availableAt.isAfter(requestedAt) ? requestedAt : availableAt;
    return new Work(target, nextAvailableAt, nextWakeVersion, leaseToken, leaseUntil);
  }

  /**
   * 使用全新的 token 将 work claim 到 {@code until}。仅在 {@code availableAt <= now} 且不存在 有效 lease（无 lease 或在
   * {@code now} 时已过期）时允许；{@code until} 必须严格晚于 {@code now}。
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
   * 在 lease 在 {@code now} 仍然有效时延长它；{@code until} 必须严格晚于当前的 {@code leaseUntil}。过期的 lease（在 {@code
   * now} 或之前已过期）会被拒绝。
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
   * 在当前 lease 于 {@code now} 仍有效时完成对 {@code claimedWakeVersion} 的处理：相等的 claim 删除该 work（返回 {@link
   * Optional#empty()}）；过期 claim 仅清除 lease，保留行以便更新的 wake 仍可见；未来的 claim 视为非法。
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
   * 在当前 lease 于 {@code now} 仍有效时对 {@code claimedWakeVersion} 重新调度：相等的 claim 将 {@code availableAt}
   * 设为 {@code requestedAt}；过期 claim 仅将 {@code availableAt} 提前 到最小值；未来的 claim 视为非法。lease 始终被清除。
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
