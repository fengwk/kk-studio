package fun.fengwk.kkstudio.harness.runtime.work;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Work mailbox target 的持久化当前调度状态。
 *
 * <p>每次 request 时 {@code wakeVersion} 递增，从而阻止旧的 Processor 完成删除一个更新的 wake； {@code leaseToken} /
 * {@code leaseUntil} 形成所有权围栏拦截过期的 worker，且两者总是同时存在或同时缺失。所有转换都是纯函数：返回下一个状态，或返回 {@link
 * Optional#empty()} 表示删除，绝不会修改接收者。
 *
 * <p>环境亲和性（Environment affinity）：{@code requiredEnvironmentId} 冻结该 Work 必须路由的环境 id；仅允许 targetType 为
 * {@link WorkTargetType#TOOL} 时非空；THREAD/MODEL 必须为空，server-side TOOL 可为空。它在首次创建时冻结，并在后续 {@link
 * #request}、{@link #claim}、{@link #renew}、{@link #complete} 与 {@link #reschedule} 纯函数状态跃迁中完整保留； 后续
 * request 若传入冲突环境需求将被拒绝。Runtime 仅携带该路由要求，PostgreSQL/Dispatcher claim 由 Infra 层按当前节点匹配 READY 且 lease
 * 未过期的 Environment route 实施所有权围栏（契约由 {@code WorkTest} 与 Store contract 守卫）。
 */
public record Work(
    WorkTarget target,
    Instant availableAt,
    long wakeVersion,
    String leaseToken,
    Instant leaseUntil,
    EnvironmentId requiredEnvironmentId) {

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
    if (requiredEnvironmentId != null && target.type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "requiredEnvironmentId must be null for target type " + target.type());
    }
  }

  /** 5 参数便捷构造：无环境亲和性（THREAD/MODEL 或 server-side TOOL）。 */
  public Work(
      WorkTarget target,
      Instant availableAt,
      long wakeVersion,
      String leaseToken,
      Instant leaseUntil) {
    this(target, availableAt, wakeVersion, leaseToken, leaseUntil, null);
  }

  /** 创建 target 的首次 wake：wakeVersion 为 1，无 lease，无环境亲和性。 */
  public static Work initial(WorkTarget target, Instant requestedAt) {
    return initial(target, requestedAt, null);
  }

  /** 创建 target 的首次 wake：wakeVersion 为 1，无 lease，冻结环境亲和性。 */
  public static Work initial(
      WorkTarget target, Instant requestedAt, EnvironmentId requiredEnvironmentId) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(requestedAt, "requestedAt");
    return new Work(target, requestedAt, 1L, null, null, requiredEnvironmentId);
  }

  /**
   * 请求另一次 wake：{@code wakeVersion} 递增，并将 {@code availableAt} 提前到当前时间与 requested time 的最小值，保留当前
   * lease 与环境亲和性。
   */
  public Work request(Instant requestedAt) {
    return request(requestedAt, null);
  }

  /**
   * 请求另一次 wake：{@code wakeVersion} 递增，并将 {@code availableAt} 提前到当前时间与 requested time 的最小值，保留当前
   * lease。若传入非空环境需求，必须与已冻结的亲和性一致，否则抛出 {@link IllegalArgumentException}。
   */
  public Work request(Instant requestedAt, EnvironmentId newRequiredEnvironmentId) {
    Objects.requireNonNull(requestedAt, "requestedAt");
    if (newRequiredEnvironmentId != null
        && !Objects.equals(requiredEnvironmentId, newRequiredEnvironmentId)) {
      throw new IllegalArgumentException(
          "conflicting requiredEnvironmentId: existing "
              + requiredEnvironmentId
              + " vs requested "
              + newRequiredEnvironmentId);
    }
    long nextWakeVersion = Math.addExact(wakeVersion, 1L);
    Instant nextAvailableAt = availableAt.isAfter(requestedAt) ? requestedAt : availableAt;
    return new Work(
        target, nextAvailableAt, nextWakeVersion, leaseToken, leaseUntil, requiredEnvironmentId);
  }

  /**
   * 使用全新的 token 将 work claim 到 {@code until}。仅在 {@code availableAt <= now} 且不存在 有效 lease（无 lease 或在
   * {@code now} 时已过期）时允许；{@code until} 必须严格晚于 {@code now}。保留环境亲和性。
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
    return new Work(target, availableAt, wakeVersion, canonicalToken, until, requiredEnvironmentId);
  }

  /**
   * 在 lease 在 {@code now} 仍然有效时延长它；{@code until} 必须严格晚于当前的 {@code leaseUntil}。过期的 lease（在 {@code
   * now} 或之前已过期）会被拒绝。保留环境亲和性。
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
    return new Work(target, availableAt, wakeVersion, leaseToken, until, requiredEnvironmentId);
  }

  /**
   * 在当前 lease 于 {@code now} 仍有效时完成对 {@code claimedWakeVersion} 的处理：相等的 claim 删除该 work（返回 {@link
   * Optional#empty()}）；过期 claim 仅清除 lease，保留行以便更新的 wake 仍可见；未来的 claim 视为非法。保留环境亲和性。
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
    return Optional.of(
        new Work(target, availableAt, wakeVersion, null, null, requiredEnvironmentId));
  }

  /**
   * 在当前 lease 于 {@code now} 仍有效时对 {@code claimedWakeVersion} 重新调度：相等的 claim 将 {@code availableAt}
   * 设为 {@code requestedAt}；过期 claim 仅将 {@code availableAt} 提前 到最小值；未来的 claim 视为非法。lease
   * 始终被清除，保留环境亲和性。
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
    return new Work(target, nextAvailableAt, wakeVersion, null, null, requiredEnvironmentId);
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
