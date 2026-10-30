package fun.fengwk.kkstudio.common.work;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable Work 行的纯调度状态：due、wake 版本、租约与环境亲和性。
 *
 * <p>该类型不包含 target 身份或领域枚举，因此可以被 Harness、Project 与 Canvas 的 Store 共同使用。所有跃迁都不读取时钟，也不修改接收者：
 * 调用方传入其权威时间域中的 {@code now}，方法返回下一个状态或用 {@link Optional#empty()} 表示删除。
 *
 * <p>租约不变量：{@code leaseToken} 与 {@code leaseUntil} 必须同时存在或同时缺失；有效租约要求 {@code leaseUntil} 严格晚于
 * {@code now}。
 */
public record WorkState(
    Instant availableAt,
    long wakeVersion,
    String leaseToken,
    Instant leaseUntil,
    UUID requiredEnvironmentId) {

  private static final int MAX_LEASE_TOKEN_LENGTH = 128;

  public WorkState {
    availableAt = Objects.requireNonNull(availableAt, "availableAt");
    if (wakeVersion <= 0) {
      throw new IllegalArgumentException("wakeVersion must be positive");
    }
    if (leaseToken != null) {
      leaseToken = requireLeaseToken(leaseToken);
    }
    if ((leaseToken == null) != (leaseUntil == null)) {
      throw new IllegalArgumentException(
          "leaseToken and leaseUntil must both be present or both be absent");
    }
  }

  /** 创建 target 的首次 wake：wakeVersion 为 1，无 lease，可选冻结环境亲和性。 */
  public static WorkState initial(Instant now, UUID requiredEnvironmentId) {
    Objects.requireNonNull(now, "now");
    return new WorkState(now, 1L, null, null, requiredEnvironmentId);
  }

  /**
   * 请求另一次 wake：wakeVersion 递增，due 提前到 {@code now} 与当前 due 的较早者，保留 lease 与环境亲和性。
   *
   * <p>非空的新环境需求必须与已冻结值一致；传 null 表示不改变现有亲和性。
   */
  public WorkState request(Instant now, UUID newRequiredEnvironmentId) {
    Objects.requireNonNull(now, "now");
    if (newRequiredEnvironmentId != null
        && !Objects.equals(requiredEnvironmentId, newRequiredEnvironmentId)) {
      throw new IllegalArgumentException(
          "conflicting requiredEnvironmentId: existing "
              + requiredEnvironmentId
              + " vs requested "
              + newRequiredEnvironmentId);
    }
    long nextWakeVersion = Math.addExact(wakeVersion, 1L);
    Instant nextAvailableAt = availableAt.isAfter(now) ? now : availableAt;
    return new WorkState(
        nextAvailableAt, nextWakeVersion, leaseToken, leaseUntil, requiredEnvironmentId);
  }

  /**
   * 使用新 token 将 work claim 到 {@code until}。仅当 due 已到且不存在有效 lease，并且 {@code until} 严格晚于 {@code now}
   * 时允许。
   */
  public WorkState claim(Instant now, String token, Instant until) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(until, "until");
    String canonicalToken = requireLeaseToken(token);
    if (availableAt.isAfter(now)) {
      throw new IllegalArgumentException("work is not available yet");
    }
    if (hasActiveLeaseAt(now)) {
      throw new IllegalArgumentException("work has an active lease");
    }
    if (!until.isAfter(now)) {
      throw new IllegalArgumentException("lease until must be after now");
    }
    return new WorkState(availableAt, wakeVersion, canonicalToken, until, requiredEnvironmentId);
  }

  /** 在 lease 仍有效时延长它；{@code until} 必须严格晚于当前 {@code leaseUntil}。过期 lease 或 token 不匹配都被拒绝。 */
  public WorkState renew(String token, Instant now, Instant until) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(until, "until");
    requireActiveLease(token, now);
    if (!until.isAfter(leaseUntil)) {
      throw new IllegalArgumentException("renew must strictly extend the current lease");
    }
    return new WorkState(availableAt, wakeVersion, leaseToken, until, requiredEnvironmentId);
  }

  /** 在 lease 仍有效时完成 {@code claimedWakeVersion}：版本相同返回空（删除行）；处理期间出现更新的 wake 时只清除 lease，保留行与 due。 */
  public Optional<WorkState> complete(String token, long claimedWakeVersion, Instant now) {
    Objects.requireNonNull(now, "now");
    requireActiveLease(token, now);
    requireClaimedWakeVersion(claimedWakeVersion);
    if (claimedWakeVersion == wakeVersion) {
      return Optional.empty();
    }
    return Optional.of(new WorkState(availableAt, wakeVersion, null, null, requiredEnvironmentId));
  }

  /**
   * 在 lease 仍有效时按 {@code claimedWakeVersion} 重排：版本相同把 due 设为 {@code availableAt}；出现更新 wake 时保留更早的
   * due。 lease 总是被清除，保留环境亲和性。
   */
  public WorkState reschedule(
      String token, long claimedWakeVersion, Instant now, Instant availableAt) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(availableAt, "availableAt");
    requireActiveLease(token, now);
    requireClaimedWakeVersion(claimedWakeVersion);
    Instant nextAvailableAt;
    if (claimedWakeVersion == wakeVersion) {
      nextAvailableAt = availableAt;
    } else {
      nextAvailableAt = this.availableAt.isAfter(availableAt) ? availableAt : this.availableAt;
    }
    return new WorkState(nextAvailableAt, wakeVersion, null, null, requiredEnvironmentId);
  }

  /** 当前 lease 在 {@code now} 是否仍有效。 */
  public boolean hasActiveLeaseAt(Instant now) {
    return leaseUntil != null && leaseUntil.isAfter(now);
  }

  /** 校验 token 匹配且 lease 在 {@code now} 仍有效；失败表示丢失所有权。 */
  public void requireActiveLease(String token, Instant now) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(now, "now");
    if (leaseToken == null || !leaseToken.equals(token)) {
      throw new IllegalArgumentException("lease token mismatch");
    }
    if (!hasActiveLeaseAt(now)) {
      throw new IllegalArgumentException("lease is stale at now");
    }
  }

  /** 校验 canonical lease token：非空白、无首尾空白且不超过 128 个字符。 */
  public static String requireLeaseToken(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("lease token must not be blank");
    }
    if (!token.equals(token.strip())) {
      throw new IllegalArgumentException("lease token must not contain surrounding whitespace");
    }
    if (token.length() > MAX_LEASE_TOKEN_LENGTH) {
      throw new IllegalArgumentException("lease token must be <= 128 characters");
    }
    return token;
  }

  private void requireClaimedWakeVersion(long claimedWakeVersion) {
    if (claimedWakeVersion <= 0) {
      throw new IllegalArgumentException("claimedWakeVersion must be positive");
    }
    if (claimedWakeVersion > wakeVersion) {
      throw new IllegalArgumentException("claimed wakeVersion must not exceed current");
    }
  }
}
