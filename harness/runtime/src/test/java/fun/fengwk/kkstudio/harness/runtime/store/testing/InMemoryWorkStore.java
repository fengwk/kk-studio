package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.WorkStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Work 调度原语：claim / renew / complete / reschedule 内部先锁定目标行，request 与删除要求 owning Thread 已锁定。
 *
 * <p>{@code claimNext} 必须是本事务第一个 Work 行锁；同层多行 Work 按 {@code (type, id)} 升序。frozen {@code
 * requiredEnvironmentId} 只允许出现在 TOOL Work 上，是否可 claim 由路由谓词判定。线程与 driver row 的 owner 关联用于 反查 Work
 * 所属 Thread。
 */
final class InMemoryWorkStore implements WorkStore {

  private final InMemoryHarnessTransaction tx;
  private final InMemoryState state;

  InMemoryWorkStore(InMemoryHarnessTransaction tx) {
    this.tx = tx;
    this.state = tx.state();
  }

  @Override
  public Optional<Work> find(WorkTarget target) {
    tx.checkOpen();
    Objects.requireNonNull(target, "target");
    return Optional.ofNullable(state.works.get(target));
  }

  @Override
  public Optional<Work> lock(WorkTarget target) {
    tx.checkOpen();
    Objects.requireNonNull(target, "target");
    tx.locks().requireCanLockWork(target);
    Work work = state.works.get(target);
    if (work != null) {
      tx.locks().recordWorkLock(target);
    }
    return Optional.ofNullable(work);
  }

  @Override
  public Optional<Work> lockClaimed(ClaimedWork claim) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(claim, "claim");
    tx.locks().requireCanLockWork(claim.target());
    Work work = state.works.get(claim.target());
    if (work == null
        || !claim.leaseToken().equals(work.leaseToken())
        || work.leaseUntil() == null
        || !work.leaseUntil().isAfter(now)) {
      // 行缺失 / token 不匹配 / lease 已过期都是正常竞态：lost / stale ownership，不以异常表达。
      return Optional.empty();
    }
    tx.locks().recordWorkLock(claim.target());
    return Optional.of(work);
  }

  @Override
  public boolean delete(WorkTarget target) {
    tx.checkOpen();
    Objects.requireNonNull(target, "target");
    if (!state.works.containsKey(target)) {
      return false;
    }
    requireWorkOwnerLocked(target);
    tx.locks().requireCanLockWork(target);
    state.works.remove(target);
    tx.locks().recordWorkLock(target);
    return true;
  }

  @Override
  public void request(WorkTarget target) {
    request(target, null);
  }

  @Override
  public void request(WorkTarget target, EnvironmentId requiredEnvironmentId) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(target, "target");
    if (requiredEnvironmentId != null && target.type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "requiredEnvironmentId must be null for target type " + target.type());
    }
    requireWorkOwnerLocked(target);
    tx.locks().requireCanLockWork(target);
    Work existing = state.works.get(target);
    Work next =
        existing == null
            ? Work.initial(target, now, requiredEnvironmentId)
            : existing.request(now, requiredEnvironmentId);
    state.works.put(target, next);
    tx.locks().recordWorkLock(target);
  }

  private void requireWorkOwnerLocked(WorkTarget target) {
    UUID threadId =
        switch (target.type()) {
          case THREAD -> {
            if (!state.threads.containsKey(target.id())) {
              throw new IllegalArgumentException("work target does not exist: " + target);
            }
            yield target.id();
          }
          case MODEL -> {
            UUID ownerThreadId = state.driverRowOwners.get(target.id());
            if (!state.driverRows.containsKey(target.id()) || ownerThreadId == null) {
              throw new IllegalArgumentException("work target does not exist: " + target);
            }
            yield ownerThreadId;
          }
          case TOOL -> {
            ToolInvocation tool = state.toolInvocations.get(target.id());
            if (tool == null) {
              throw new IllegalArgumentException("work target does not exist: " + target);
            }
            yield tool.threadId();
          }
        };
    tx.locks().requireLocked(InMemoryHarnessLocks.LockKey.thread(threadId));
  }

  private void requireTargetExists(WorkTarget target) {
    boolean exists =
        switch (target.type()) {
          case THREAD -> state.threads.containsKey(target.id());
          case MODEL -> state.driverRows.containsKey(target.id());
          case TOOL -> state.toolInvocations.containsKey(target.id());
        };
    if (!exists) {
      throw new IllegalArgumentException("work target does not exist: " + target);
    }
  }

  @Override
  public Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration) {
    return claimNext(targetType, leaseToken, leaseDuration, null);
  }

  @Override
  public Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration, UUID nodeInstanceId) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(targetType, "targetType");
    Objects.requireNonNull(leaseToken, "leaseToken");
    HarnessStoreTime.requireWholeMillisecondDuration(leaseDuration, "leaseDuration");
    Instant leaseUntil = now.plus(leaseDuration);
    tx.locks().requireCanClaimWork();
    List<Work> due =
        state.works.values().stream()
            .filter(work -> work.target().type() == targetType)
            .filter(work -> !work.availableAt().isAfter(now))
            .filter(work -> work.leaseToken() == null || !work.leaseUntil().isAfter(now))
            .filter(
                work ->
                    work.requiredEnvironmentId() == null
                        || tx.isRouteReady(nodeInstanceId, work.requiredEnvironmentId(), now))
            .sorted(
                Comparator.comparing(Work::availableAt)
                    .thenComparing(work -> work.target().id(), UuidOrder.COMPARATOR))
            .toList();
    if (due.isEmpty()) {
      return Optional.empty();
    }
    Work candidate = due.get(0);
    requireTargetExists(candidate.target());
    Work claimed = candidate.claim(now, leaseToken, leaseUntil);
    tx.locks().requireCanLockWork(claimed.target());
    state.works.put(claimed.target(), claimed);
    tx.locks().recordWorkLock(claimed.target());
    return Optional.of(
        new ClaimedWork(
            claimed.target(),
            claimed.wakeVersion(),
            claimed.leaseToken(),
            claimed.leaseUntil(),
            claimed.requiredEnvironmentId()));
  }

  @Override
  public boolean renew(ClaimedWork claim, Duration leaseDuration) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(claim, "claim");
    HarnessStoreTime.requireWholeMillisecondDuration(leaseDuration, "leaseDuration");
    Work work = lockedWork(claim.target());
    Instant target = now.plus(leaseDuration);
    if (!target.isAfter(work.leaseUntil())) {
      // 现有 lease 已覆盖目标 margin：保持不变，避免把更长的 lease 缩短。
      return false;
    }
    Work renewed = work.renew(claim.leaseToken(), now, target);
    state.works.put(renewed.target(), renewed);
    return true;
  }

  @Override
  public Optional<Work> complete(ClaimedWork claim) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(claim, "claim");
    Work work = state.works.get(claim.target());
    if (work == null) {
      // 行不存在意味着没有可验证的 lease：completed 的 ownership 已丢失，不能幂等吞掉终态。
      throw new IllegalStateException(
          "work does not exist for target " + claim.target() + " (lost ownership)");
    }
    tx.locks().requireCanLockWork(claim.target());
    tx.locks().recordWorkLock(claim.target());
    Optional<Work> next = work.complete(claim.leaseToken(), claim.claimedWakeVersion(), now);
    if (next.isEmpty()) {
      state.works.remove(claim.target());
    } else {
      state.works.put(claim.target(), next.get());
    }
    return next;
  }

  @Override
  public void reschedule(ClaimedWork claim, Duration delay) {
    tx.checkOpen();
    Instant now = tx.now();
    Objects.requireNonNull(claim, "claim");
    HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(delay, "delay");
    Work work = lockedWork(claim.target());
    Work next =
        work.reschedule(claim.leaseToken(), claim.claimedWakeVersion(), now, now.plus(delay));
    state.works.put(next.target(), next);
  }

  private Work lockedWork(WorkTarget target) {
    tx.locks().requireCanLockWork(target);
    tx.locks().recordWorkLock(target);
    Work work = state.works.get(target);
    if (work == null) {
      throw new IllegalStateException("work does not exist for target " + target);
    }
    return work;
  }
}
