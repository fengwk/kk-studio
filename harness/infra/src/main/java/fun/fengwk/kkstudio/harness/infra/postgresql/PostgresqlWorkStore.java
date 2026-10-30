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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable Work 聚合的 PostgreSQL 实现。 */
final class PostgresqlWorkStore implements WorkStore {

  private final PostgresqlHarnessTransaction tx;

  /**
   * Work claim-only 短事务候选选取与租约写入 SQL。
   *
   * <p>Candidate CTE 执行条件过滤与环境路由围栏判定：
   *
   * <ul>
   *   <li>目标类型匹配、已到期（{@code available_at <= statement_timestamp()}）且无未过期租约；
   *   <li>Environment route 亲和性：当 {@code required_environment_id} 为空时无亲和性；非空时通过 EXISTS 关联 {@code
   *       environment_connection}，要求连接所有者为当前传入的 {@code nodeInstanceId}、状态为 {@code READY} 且租约有效；
   *   <li>使用 {@code FOR UPDATE SKIP LOCKED} 悲观跳过正被其他事务锁定或并发处理的行，选出单条候选；
   *   <li>外层 UPDATE 原子写入新的 {@code lease_token}，并以 {@code date_trunc('milliseconds',
   *       statement_timestamp() + ?::interval)} 计算 {@code lease_until}，返回完整 Work 行。deadline 与 due /
   *       expiry 使用同一数据库时间域。
   * </ul>
   */
  private static final String CLAIM_NEXT_WORK =
      """
      with candidate as (
          select target_type, target_id
          from durable_work
          where target_type = ?
            and available_at <= statement_timestamp()
            and (lease_until is null or lease_until <= statement_timestamp())
            and (
                required_environment_id is null
                or exists (
                    select 1
                    from environment_connection ec
                    where ec.environment_id = durable_work.required_environment_id
                      and ec.owner_node_id = ?
                      and ec.status = 'READY'
                      and ec.lease_until > statement_timestamp()
                )
            )
          order by available_at, target_id
          for update skip locked
          limit 1
      )
      update durable_work work
      set lease_token = ?,
          lease_until = date_trunc('milliseconds', statement_timestamp() + ?::interval)
      from candidate
      where work.target_type = candidate.target_type
        and work.target_id = candidate.target_id
      returning work.*
      """;

  /**
   * Work 登记与唤醒 upsert SQL。
   *
   * <p>插入新工作或更新既有工作：
   *
   * <ul>
   *   <li>若目标不存在则插入初始行（初始 wake_version = 1，lease 为空），available_at 取数据库权威时间「此刻」的毫秒截断；
   *   <li>若已存在则把 {@code available_at} 提前为 min(现有, 数据库权威时间)，并递增 {@code wake_version}；因此一次唤醒始终表示「立即」，
   *       不把调用方的 JVM 绝对时刻写成 due；
   *   <li>保留既有 {@code lease_token} 与 {@code lease_until} 不变；
   *   <li>冲突拒绝：WHERE 子句要求传入的 {@code required_environment_id} 必须与既有值一致或传入为 null； 若传入了与已冻结 affinity
   *       冲突的值，则更新 0 行并导致上层抛错拒绝。
   * </ul>
   */
  private static final String REQUEST_WORK =
      """
      insert into durable_work (
          target_type, target_id, available_at, wake_version, lease_token, lease_until, required_environment_id
      ) values (?, ?, date_trunc('milliseconds', statement_timestamp()), 1, null, null, ?)
      on conflict (target_type, target_id) do update
      set available_at = least(durable_work.available_at, excluded.available_at),
          wake_version = durable_work.wake_version + 1
      where (durable_work.required_environment_id is not distinct from excluded.required_environment_id
             or excluded.required_environment_id is null)
      returning *
      """;

  PostgresqlWorkStore(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  @Override
  public Optional<Work> find(WorkTarget target) {
    tx.checkOpen();
    Objects.requireNonNull(target, "target");
    return tx.queryOne(
        "select * from durable_work where target_type = ? and target_id = ?",
        PostgresqlHarnessRows.WORK,
        target.type().name(),
        target.id());
  }

  /**
   * 按锁阶梯获取单个 Work 的排他行级锁（{@code FOR UPDATE}）。
   *
   * <p>必须遵守 {@code (targetType.ordinal(), targetId)} 升序排序。
   */
  @Override
  public Optional<Work> lock(WorkTarget target) {
    tx.checkOpen();
    Objects.requireNonNull(target, "target");
    tx.requireCanLockWork(target);
    Optional<Work> work =
        tx.queryOne(
            """
            select *
            from durable_work
            where target_type = ? and target_id = ?
            for update
            """,
            PostgresqlHarnessRows.WORK,
            target.type().name(),
            target.id());
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
        tx.queryOne(
            """
            select *
            from durable_work
            where target_type = ?
              and target_id = ?
              and lease_token = ?
              and lease_until > statement_timestamp()
            for update
            """,
            PostgresqlHarnessRows.WORK,
            claim.target().type().name(),
            claim.target().id(),
            claim.leaseToken());
    work.ifPresent(ignored -> tx.recordWorkLock(claim.target()));
    return work;
  }

  /** 删除指定 Work。要求必须已持有其属主实体的行级锁。 */
  @Override
  public boolean delete(WorkTarget target) {
    tx.checkOpen();
    Objects.requireNonNull(target, "target");
    if (tx.work().find(target).isEmpty()) {
      return false;
    }
    requireWorkOwnerLocked(target);
    Optional<Work> locked = tx.work().lock(target);
    if (locked.isEmpty()) {
      return false;
    }
    int deleted =
        tx.updateJdbc(
            "delete from durable_work where target_type = ? and target_id = ?",
            target.type().name(),
            target.id());
    tx.requireSingleUpdate(deleted, "work", target.id());
    // Work 行删除使「待领取环境等待」集合变化；属主实体若仍在该事务内即可解析真实执行根。
    tx.notifyEnvironmentWorkChanged(locked.get());
    return true;
  }

  @Override
  public void request(WorkTarget target) {
    tx.work().request(target, null);
  }

  /**
   * 登记或唤醒指定 Work。
   *
   * <p>必须在已锁定属主 Thread 的事务内调用。执行 upsert 原语：
   *
   * <ul>
   *   <li>若 Work 不存在则新建，初始 wake_version 为 1，available_at 取数据库权威时间「此刻」；
   *   <li>若已存在则更新 {@code available_at = least(current, statement_timestamp())} 且递增 {@code
   *       wake_version}；本原语只表达一次立即可处理的唤醒；
   *   <li>{@code requiredEnvironmentId} 仅允许用于 TOOL Work；
   *   <li><b>已冻结 Affinity 冲突拒绝：</b>upsert WHERE 子句要求传入的 {@code requiredEnvironmentId} 与既有值一致或传入为
   *       null； 若已存在的 Work 绑定的环境与新传入的值冲突，更新 0 行并抛出 {@link IllegalArgumentException} 明确拒绝，防止环境亲和性漂移；
   *   <li>提交前发送 {@code pg_notify} availability hint。
   * </ul>
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
    Work work =
        tx.writeOne(
                REQUEST_WORK,
                PostgresqlHarnessRows.WORK,
                target.type().name(),
                target.id(),
                requiredEnvironmentId == null ? null : requiredEnvironmentId.value())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "conflicting requiredEnvironmentId for work target " + target));
    tx.recordWorkLock(work.target());
    tx.notifyWorkAvailable(work.target().type());
    // 新建/重置冻结了环境亲和性的 TOOL Work：新增或提前了「待领取环境等待」事实，需失效待处理读模型。
    tx.notifyEnvironmentWorkChanged(work);
  }

  @Override
  public Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration) {
    return tx.work().claimNext(targetType, leaseToken, leaseDuration, null);
  }

  /**
   * Work claim-only 短事务：以 {@code FOR UPDATE SKIP LOCKED} 和 Environment route 路由围栏选取单条候选并签发租约。
   *
   * <p><b>Environment route 路由围栏：</b>
   *
   * <ul>
   *   <li>{@code required_environment_id} 为空时无亲和性要求，任何活跃 Dispatcher 均可获取；
   *   <li>非空时，要求在 {@code environment_connection} 中存在匹配的 {@code environment_id}、所有者为传入的 {@code
   *       nodeInstanceId}、状态为 {@code READY} 且租约未过期的记录；
   *   <li>路由不确定（如断联、未就绪、租约过期、归属其他节点）时不返回候选；底层数据库故障则抛错并使 claim 事务失败；
   *   <li>层次区分：本路由围栏与底层数据库行级并发控制 {@code FOR UPDATE SKIP LOCKED} 及应用层 {@code lease_token} / {@code
   *       lease_until} 所有权围栏分属不同层次，互不替代。
   * </ul>
   */
  public Optional<ClaimedWork> claimNext(
      WorkTargetType targetType, String leaseToken, Duration leaseDuration, UUID nodeInstanceId) {
    tx.checkOpen();
    Objects.requireNonNull(targetType, "targetType");
    Objects.requireNonNull(leaseToken, "leaseToken");
    HarnessStoreTime.requireWholeMillisecondDuration(leaseDuration, "leaseDuration");
    requireCanonicalLeaseToken(leaseToken);
    tx.requireCanClaimWork();
    Optional<Work> claimed =
        tx.writeOne(
            CLAIM_NEXT_WORK,
            PostgresqlHarnessRows.WORK,
            targetType.name(),
            nodeInstanceId,
            leaseToken,
            leaseInterval(leaseDuration));
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    Work work = claimed.get();
    requireTargetExists(work.target());
    tx.recordWorkLock(work.target());
    // 领取冻结了环境亲和性的 TOOL Work：签发执行租约使「待领取环境等待」条目消失，需失效待处理读模型。
    tx.notifyEnvironmentWorkChanged(work);
    return Optional.of(
        new ClaimedWork(
            work.target(),
            work.wakeVersion(),
            work.leaseToken(),
            work.leaseUntil(),
            work.requiredEnvironmentId()));
  }

  /**
   * 延长 claim 的 lease，使其至少覆盖数据库时间加上 {@code leaseDuration}。
   *
   * <p>有效性与新 deadline 都在 {@code statement_timestamp()} 时间域内计算；已覆盖目标的更长 lease 不会被缩短。
   */
  @Override
  public boolean renew(ClaimedWork claim, Duration leaseDuration) {
    tx.checkOpen();
    Objects.requireNonNull(claim, "claim");
    HarnessStoreTime.requireWholeMillisecondDuration(leaseDuration, "leaseDuration");
    Instant databaseNow = tx.databaseNow();
    Work work = lockOwnedWorkAtDatabaseTime(claim);
    Instant target = databaseNow.plus(leaseDuration);
    if (!target.isAfter(work.leaseUntil())) {
      // 现有 lease 已覆盖目标 margin：保持不变，避免把更长的 lease 缩短。
      return false;
    }
    updateWork(work.renew(claim.leaseToken(), databaseNow, target));
    return true;
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
    Instant databaseNow = tx.databaseNow();
    Work work = lockOwnedWorkAtDatabaseTime(claim);
    Optional<Work> next =
        work.complete(claim.leaseToken(), claim.claimedWakeVersion(), databaseNow);
    if (next.isEmpty()) {
      int deleted =
          tx.updateJdbc(
              "delete from durable_work where target_type = ? and target_id = ?",
              claim.target().type().name(),
              claim.target().id());
      tx.requireSingleUpdate(deleted, "work", claim.target().id());
    } else {
      updateWork(next.get());
      tx.notifyWorkAvailable(claim.target().type());
    }
    // Work 终态变更（删除或清租约保留）都会改变「待领取环境等待」集合，需失效待处理读模型。
    tx.notifyEnvironmentWorkChanged(work);
    return next;
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
    Instant databaseNow = tx.databaseNow();
    Work work = lockOwnedWorkAtDatabaseTime(claim);
    Work next =
        work.reschedule(
            claim.leaseToken(), claim.claimedWakeVersion(), databaseNow, databaseNow.plus(delay));
    updateWork(next);
    tx.notifyWorkAvailable(claim.target().type());
    // 清租约重排使 Work 可能重新进入「待领取环境等待」，需失效待处理读模型。
    tx.notifyEnvironmentWorkChanged(work);
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

  /**
   * 锁定 Work 行并按数据库时间校验 claim ownership。
   *
   * <p>行不存在抛 {@link IllegalStateException}（lost ownership）；token 不匹配或数据库时间下租约已过期抛 {@link
   * IllegalArgumentException}。有效性判定完全在数据库时间域。
   */
  private Work lockOwnedWorkAtDatabaseTime(ClaimedWork claim) {
    Optional<Work> locked = tx.work().lockClaimed(claim);
    if (locked.isPresent()) {
      return locked.get();
    }
    if (tx.work().find(claim.target()).isEmpty()) {
      throw new IllegalStateException(
          "work does not exist for target " + claim.target() + " (lost ownership)");
    }
    throw new IllegalArgumentException(
        "lease is stale at database time for target " + claim.target());
  }

  /** 复用 {@link ClaimedWork} 的构造不变量校验 leaseToken 是 canonical 非空白 token（失败即请求校验错误）。 */
  private static void requireCanonicalLeaseToken(String leaseToken) {
    new ClaimedWork(
        new WorkTarget(WorkTargetType.THREAD, new UUID(0L, 0L)), 1L, leaseToken, Instant.EPOCH);
  }

  /** PostgreSQL interval 字面量：duration 已限定为正的整毫秒。 */
  private static String leaseInterval(Duration leaseDuration) {
    return leaseDuration.toMillis() + " milliseconds";
  }

  private void updateWork(Work work) {
    int updated =
        tx.updateJdbc(
            """
            update durable_work
            set available_at = ?,
                wake_version = ?,
                lease_token = ?,
                lease_until = ?
            where target_type = ? and target_id = ?
            """,
            PostgresqlHarnessRows.timestamp(work.availableAt()),
            work.wakeVersion(),
            work.leaseToken(),
            PostgresqlHarnessRows.timestamp(work.leaseUntil()),
            work.target().type().name(),
            work.target().id());
    tx.requireSingleUpdate(updated, "work", work.target().id());
  }
}
