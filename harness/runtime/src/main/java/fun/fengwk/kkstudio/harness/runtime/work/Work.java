package fun.fengwk.kkstudio.harness.runtime.work;

import fun.fengwk.kkstudio.common.work.WorkState;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 Work mailbox target 的持久化当前调度状态。
 *
 * <p>调度跃迁的唯一实现是 {@link WorkState}；本类型只保留 Harness 的 typed target 与 {@link EnvironmentId}
 * 边界，并在两者之间做无损映射。环境亲和性冻结该 Work 必须路由的环境 id；仅允许 targetType 为 {@link WorkTargetType#TOOL}
 * 时非空；THREAD/MODEL 必须为空，server-side TOOL 可为空。
 *
 * <p>每次 request 时 {@code wakeVersion} 递增，从而阻止旧的 Processor 完成删除一个更新的 wake； {@code leaseToken} /
 * {@code leaseUntil} 形成所有权围栏拦截过期的 worker，且两者总是同时存在或同时缺失。
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
    if (requiredEnvironmentId != null && target.type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "requiredEnvironmentId must be null for target type " + target.type());
    }
    // 复用公共状态机的字段不变量，避免 Harness 维护第二份 wake/lease 校验。
    new WorkState(
        availableAt,
        wakeVersion,
        leaseToken,
        leaseUntil,
        requiredEnvironmentId == null ? null : requiredEnvironmentId.value());
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

  /** 把公共调度状态映射为 Harness typed Work；target 与环境由调用方提供。 */
  public static Work from(WorkTarget target, WorkState state) {
    Objects.requireNonNull(state, "state");
    return new Work(
        target,
        state.availableAt(),
        state.wakeVersion(),
        state.leaseToken(),
        state.leaseUntil(),
        state.requiredEnvironmentId() == null
            ? null
            : EnvironmentId.of(state.requiredEnvironmentId()));
  }

  /** 创建 target 的首次 wake：wakeVersion 为 1，无 lease，无环境亲和性；{@code now} 是该实现权威时间域中的立即可用时刻。 */
  public static Work initial(WorkTarget target, Instant now) {
    return initial(target, now, null);
  }

  /** 创建 target 的首次 wake：wakeVersion 为 1，无 lease，冻结环境亲和性；{@code now} 是该实现权威时间域中的立即可用时刻。 */
  public static Work initial(WorkTarget target, Instant now, EnvironmentId requiredEnvironmentId) {
    Objects.requireNonNull(target, "target");
    return from(
        target,
        WorkState.initial(
            now, requiredEnvironmentId == null ? null : requiredEnvironmentId.value()));
  }

  /**
   * 请求另一次 wake：{@code wakeVersion} 递增，并将 {@code availableAt} 提前到当前时间与 wake 时刻的最小值，保留当前 lease
   * 与环境亲和性。
   */
  public Work request(Instant now) {
    return request(now, null);
  }

  /**
   * 请求另一次 wake：{@code wakeVersion} 递增，并将 {@code availableAt} 提前到当前时间与 wake 时刻的最小值，保留当前
   * lease。若传入非空环境需求，必须与已冻结的亲和性一致，否则抛出 {@link IllegalArgumentException}。
   */
  public Work request(Instant now, EnvironmentId newRequiredEnvironmentId) {
    return from(
        target,
        state()
            .request(
                now, newRequiredEnvironmentId == null ? null : newRequiredEnvironmentId.value()));
  }

  /**
   * 使用全新的 token 将 work claim 到 {@code until}。仅在 {@code availableAt <= now} 且不存在 有效 lease（无 lease 或在
   * {@code now} 时已过期）时允许；{@code until} 必须严格晚于 {@code now}。保留环境亲和性。
   */
  public Work claim(Instant now, String token, Instant until) {
    return from(target, state().claim(now, token, until));
  }

  /**
   * 在 lease 在 {@code now} 仍然有效时延长它；{@code until} 必须严格晚于当前的 {@code leaseUntil}。过期的 lease（在 {@code
   * now} 或之前已过期）会被拒绝。保留环境亲和性。
   */
  public Work renew(String token, Instant now, Instant until) {
    return from(target, state().renew(token, now, until));
  }

  /**
   * 在当前 lease 于 {@code now} 仍有效时完成对 {@code claimedWakeVersion} 的处理：相等的 claim 删除该 work（返回 {@link
   * Optional#empty()}）；过期 claim 仅清除 lease，保留行以便更新的 wake 仍可见；未来的 claim 视为非法。保留环境亲和性。
   */
  public Optional<Work> complete(String token, long claimedWakeVersion, Instant now) {
    return state().complete(token, claimedWakeVersion, now).map(next -> from(target, next));
  }

  /**
   * 在当前 lease 于 {@code now} 仍有效时对 {@code claimedWakeVersion} 重新调度：相等的 claim 将 {@code availableAt}
   * 设为 {@code availableAt}；过期 claim 仅将 {@code availableAt} 提前到最小值；未来的 claim 视为非法。lease
   * 始终被清除，保留环境亲和性。
   */
  public Work reschedule(String token, long claimedWakeVersion, Instant now, Instant availableAt) {
    return from(target, state().reschedule(token, claimedWakeVersion, now, availableAt));
  }

  /** 当前 lease 在 {@code now} 是否仍由 {@code token} 持有。 */
  public boolean isOwnedBy(String token, Instant now) {
    return leaseToken != null
        && leaseToken.equals(token)
        && leaseUntil != null
        && leaseUntil.isAfter(now);
  }

  /** 映射到公共状态机；Harness 不再维护第二套跃迁实现。 */
  public WorkState state() {
    return new WorkState(
        availableAt,
        wakeVersion,
        leaseToken,
        leaseUntil,
        requiredEnvironmentId == null ? null : requiredEnvironmentId.value());
  }
}
