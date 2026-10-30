package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessTransaction.LockKey;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.WorkStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.work.DurableWork;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Harness 的 Work 视图：锁阶梯、目标存在性与环境等待通知留在本类，{@code durable_work} SQL 与调度跃迁全部委托 {@link
 * DurableWorkStore}。
 *
 * <p>每种 target type 一个 Store 实例，共享本事务的连接与数据库时间。claim 仍要求本事务尚未持有 Work 行锁；request / delete 仍要求 owning
 * Thread 已锁定。
 */
final class PostgresqlWorkStore implements WorkStore {

  private final PostgresqlHarnessTransaction tx;
  private final Map<WorkTargetType, DurableWorkStore> stores;

  PostgresqlWorkStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
    Map<WorkTargetType, DurableWorkStore> stores = new EnumMap<>(WorkTargetType.class);
    PostgresqlDurableWorkJdbc jdbc = new PostgresqlDurableWorkJdbc(tx);
    for (WorkTargetType type : WorkTargetType.values()) {
      stores.put(type, new DurableWorkStore(type.name(), jdbc));
    }
    this.stores = Map.copyOf(stores);
  }

  @Override
  public Optional<Work> find(WorkTarget target) {
    tx.checkOpen();
    return store(target).find(target.id()).map(work -> toWork(target, work));
  }

  /**
   * 按锁阶梯获取单个 Work 的排他行级锁（{@code FOR UPDATE}）。
   *
   * <p>必须遵守 {@code (targetType.ordinal(), targetId)} 升序排序。
   */
  @Override
  public Optional<Work> lock(WorkTarget target) {
    tx.checkOpen();
    tx.requireCanLockWork(target);
    Optional<Work> work = store(target).lock(target.id()).map(row -> toWork(target, row));
    work.ifPresent(ignored -> tx.recordWorkLock(target));
    return work;
  }

  /**
   * Work 所有权围栏校验：获取行级排他锁并校验 {@code lease_token} 匹配且 {@code lease_until > statement_timestamp()}。
   *
   * <p>有效性按数据库时间判定。若租约已过期或被其他 Dispatcher 接管，返回 empty，调用方绝不能推进该 Work 状态。
   */
  @Override
  public Optional<Work> lockClaimed(ClaimedWork claim) {
    tx.checkOpen();
    Objects.requireNonNull(claim, "claim");
    tx.requireCanLockWork(claim.target());
    Optional<Work> work =
        store(claim.target()).lockClaimed(toClaim(claim)).map(row -> toWork(claim.target(), row));
    work.ifPresent(ignored -> tx.recordWorkLock(claim.target()));
    return work;
  }

  /** 删除指定 Work。要求必须已持有其属主实体的行级锁。 */
  @Override
  public boolean delete(WorkTarget target) {
    tx.checkOpen();
    Optional<Work> existing = find(target);
    if (existing.isEmpty()) {
      return false;
    }
    requireWorkOwnerLocked(target);
    Optional<Work> locked = lock(target);
    if (locked.isEmpty()) {
      return false;
    }
    if (!store(target).delete(target.id())) {
      return false;
    }
    tx.notifyEnvironmentWorkChanged(target, locked.get().requiredEnvironmentId());
    return true;
  }

  @Override
  public void request(WorkTarget target) {
    request(target, null);
  }

  /**
   * 登记或唤醒指定 Work。
   *
   * <p>必须在已锁定属主 Thread 的事务内调用。委托中央 Store 计算立即 wake：不存在则新建，已存在则提前 due 并递增
   * wakeVersion，已冻结的环境亲和性冲突被拒绝。
   */
  @Override
  public void request(WorkTarget target, EnvironmentId requiredEnvironmentId) {
    tx.checkOpen();
    Objects.requireNonNull(target, "target");
    if (requiredEnvironmentId != null && target.type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "requiredEnvironmentId must be null for target type " + target.type());
    }
    requireWorkOwnerLocked(target);
    tx.requireCanLockWork(target);
    DurableWork written =
        store(target)
            .request(
                target.id(), requiredEnvironmentId == null ? null : requiredEnvironmentId.value());
    Work work = toWork(target, written);
    tx.recordWorkLock(work.target());
    tx.notifyEnvironmentWorkChanged(target, work.requiredEnvironmentId());
  }

  @Override
  public Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration) {
    return claimNext(targetType, leaseToken, leaseDuration, null);
  }

  /**
   * Work claim-only：由中央 Store 以 {@code FOR UPDATE SKIP LOCKED} 与 READY owner 路由围栏选取单条候选并签发租约。
   *
   * <p>本方法额外校验目标业务行存在，并登记 Work 锁与环境等待失效。路由围栏、行锁跳过与 lease token 围栏仍分属不同层次。
   */
  @Override
  public Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration, UUID nodeInstanceId) {
    tx.checkOpen();
    Objects.requireNonNull(targetType, "targetType");
    HarnessStoreTime.requireWholeMillisecondDuration(leaseDuration, "leaseDuration");
    tx.requireCanClaimWork();
    Optional<WorkClaim> claimed =
        store(targetType).claimNext(leaseToken, leaseDuration, nodeInstanceId);
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    WorkClaim claim = claimed.get();
    WorkTarget target = new WorkTarget(targetType, claim.targetId());
    requireTargetExists(target);
    tx.recordWorkLock(target);
    tx.notifyEnvironmentWorkChanged(
        target,
        claim.requiredEnvironmentId() == null
            ? null
            : EnvironmentId.of(claim.requiredEnvironmentId()));
    return Optional.of(toClaimedWork(target, claim));
  }

  /**
   * 延长 claim 的 lease，使其至少覆盖数据库时间加上 {@code leaseDuration}。
   *
   * <p>有效性与新 deadline 都在数据库时间域内计算；已覆盖目标的更长 lease 不会被缩短。
   */
  @Override
  public boolean renew(ClaimedWork claim, Duration leaseDuration) {
    tx.checkOpen();
    Objects.requireNonNull(claim, "claim");
    HarnessStoreTime.requireWholeMillisecondDuration(leaseDuration, "leaseDuration");
    tx.requireCanLockWork(claim.target());
    boolean renewed = store(claim.target()).renew(toClaim(claim), leaseDuration);
    tx.recordWorkLock(claim.target());
    return renewed;
  }

  /**
   * Final Work Fence：完成工作并校验 wakeVersion。
   *
   * <p>若 claimed wakeVersion 仍为最新，则直接删除该 Work 行；若处理期间有新 wake 请求导致版本递增，则清除租约保留该行
   * 并发送唤醒通知待后续调度；若租约丢失则抛错回滚事务。
   */
  @Override
  public Optional<Work> complete(ClaimedWork claim) {
    tx.checkOpen();
    Objects.requireNonNull(claim, "claim");
    tx.requireCanLockWork(claim.target());
    Optional<DurableWork> next = store(claim.target()).complete(toClaim(claim));
    tx.recordWorkLock(claim.target());
    tx.notifyEnvironmentWorkChanged(claim.target(), claim.requiredEnvironmentId());
    return next.map(work -> toWork(claim.target(), work));
  }

  /**
   * 重排 Work 调度时间：清除租约，并把 availableAt 设为数据库权威时间加上 {@code delay}，保留当前 wakeVersion。
   *
   * <p>{@code delay} 必须是非负整毫秒 Duration（零表示立即）；due 由数据库时间加 delay 计算。
   */
  @Override
  public void reschedule(ClaimedWork claim, Duration delay) {
    tx.checkOpen();
    Objects.requireNonNull(claim, "claim");
    HarnessStoreTime.requireNonNegativeWholeMillisecondDuration(delay, "delay");
    tx.requireCanLockWork(claim.target());
    store(claim.target()).reschedule(toClaim(claim), delay);
    tx.recordWorkLock(claim.target());
    tx.notifyEnvironmentWorkChanged(claim.target(), claim.requiredEnvironmentId());
  }

  private DurableWorkStore store(WorkTarget target) {
    Objects.requireNonNull(target, "target");
    return store(target.type());
  }

  private DurableWorkStore store(WorkTargetType targetType) {
    Objects.requireNonNull(targetType, "targetType");
    return stores.get(targetType);
  }

  private void requireWorkOwnerLocked(WorkTarget target) {
    UUID threadId =
        switch (target.type()) {
          case THREAD -> tx.threads()
              .find(target.id())
              .orElseThrow(
                  () -> new IllegalArgumentException("work target does not exist: " + target))
              .id();
          case MODEL -> tx.builtin()
              .findModelInvocation(target.id())
              .orElseThrow(
                  () -> new IllegalArgumentException("work target does not exist: " + target))
              .threadId();
          case TOOL -> {
            ToolInvocation tool =
                tx.tools()
                    .find(target.id())
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException("work target does not exist: " + target));
            yield tool.threadId();
          }
        };
    tx.requireLocked(LockKey.thread(threadId));
  }

  private void requireTargetExists(WorkTarget target) {
    boolean exists =
        switch (target.type()) {
          case THREAD -> tx.queryExists(
              "select exists (select 1 from harness_thread where id = ?)", target.id());
          case MODEL -> tx.queryExists(
              "select exists (select 1 from harness_model_invocation where id = ?)", target.id());
          case TOOL -> tx.queryExists(
              "select exists (select 1 from harness_tool_invocation where id = ?)", target.id());
        };
    if (!exists) {
      throw new IllegalArgumentException("work target does not exist: " + target);
    }
  }

  private static Work toWork(WorkTarget target, DurableWork work) {
    return Work.from(target, work.state());
  }

  private static ClaimedWork toClaimedWork(WorkTarget target, WorkClaim claim) {
    return new ClaimedWork(
        target,
        claim.claimedWakeVersion(),
        claim.leaseToken(),
        claim.leaseUntil(),
        claim.requiredEnvironmentId() == null
            ? null
            : EnvironmentId.of(claim.requiredEnvironmentId()));
  }

  private static WorkClaim toClaim(ClaimedWork claim) {
    return new WorkClaim(
        claim.target().id(),
        claim.claimedWakeVersion(),
        claim.leaseToken(),
        claim.leaseUntil(),
        claim.requiredEnvironmentId() == null ? null : claim.requiredEnvironmentId().value());
  }
}
