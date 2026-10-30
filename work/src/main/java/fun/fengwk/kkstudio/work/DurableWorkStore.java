package fun.fengwk.kkstudio.work;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import fun.fengwk.kkstudio.common.work.WorkState;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code durable_work} 的唯一 SQL 实现。
 *
 * <p>每个实例绑定一种 target type。所有跃迁先由 {@link WorkState} 计算，本类只负责在调用方物理事务中读取/锁定行、写回下一状态并发送 {@code
 * durable_work} 提示。没有外层事务时 {@link DurableWorkJdbc#inTransaction} 开启短事务；绝不使用 {@code
 * REQUIRES_NEW}，也绝不吞掉底层 JDBC 异常。
 *
 * <p>时间域：due、lease 签发与有效性一律使用数据库 {@code statement_timestamp()} 截断到毫秒；调用方只提供相对 {@link
 * Duration}。环境路由只影响 claim：{@code required_environment_id} 非空时，要求传入的 {@code nodeInstanceId} 在 {@code
 * environment_connection} 中持有 READY 且未过期的租约。
 */
public final class DurableWorkStore {

  /** 全部 Durable Work 共用的 NOTIFY channel；payload 是 target type。 */
  public static final String CHANNEL = "durable_work";

  private static final String NOW = "date_trunc('milliseconds', statement_timestamp())";

  private static final String COLUMNS =
      "target_id, available_at, wake_version, lease_token, lease_until, required_environment_id";

  private static final RowMapper<DurableWork> WORK_MAPPER =
      (resultSet, rowNumber) ->
          new DurableWork(
              resultSet.getObject("target_id", UUID.class),
              new WorkState(
                  instant(resultSet.getTimestamp("available_at")),
                  resultSet.getLong("wake_version"),
                  resultSet.getString("lease_token"),
                  instant(resultSet.getTimestamp("lease_until")),
                  resultSet.getObject("required_environment_id", UUID.class)));

  private static final RowMapper<Row> ROW_MAPPER =
      (resultSet, rowNumber) ->
          new Row(
              new DurableWork(
                  resultSet.getObject("target_id", UUID.class),
                  new WorkState(
                      instant(resultSet.getTimestamp("available_at")),
                      resultSet.getLong("wake_version"),
                      resultSet.getString("lease_token"),
                      instant(resultSet.getTimestamp("lease_until")),
                      resultSet.getObject("required_environment_id", UUID.class))),
              instant(resultSet.getTimestamp("database_now")));

  private static final String SELECT_WORK =
      "select " + COLUMNS + " from durable_work where target_type = ? and target_id = ?";

  private static final String SELECT_ROW_FOR_UPDATE =
      "select "
          + COLUMNS
          + ", "
          + NOW
          + " as database_now from durable_work"
          + " where target_type = ? and target_id = ? for update";

  private static final String SELECT_OWNED_ROW_FOR_UPDATE =
      "select "
          + COLUMNS
          + ", "
          + NOW
          + " as database_now from durable_work"
          + " where target_type = ? and target_id = ? and lease_token = ?"
          + " and lease_until > statement_timestamp() for update";

  private static final String INSERT_WORK =
      "insert into durable_work (target_type, target_id, available_at, wake_version,"
          + " lease_token, lease_until, required_environment_id)"
          + " values (?, ?, ?, ?, null, null, ?)"
          + " on conflict (target_type, target_id) do nothing"
          + " returning "
          + COLUMNS;

  private static final String UPDATE_WORK =
      "update durable_work set available_at = ?, wake_version = ?, lease_token = ?,"
          + " lease_until = ? where target_type = ? and target_id = ?";

  private static final String DELETE_WORK =
      "delete from durable_work where target_type = ? and target_id = ?";

  /**
   * claim 候选：目标类型、到期、无有效租约；环境亲和性通过 EXISTS 要求当前节点持有 READY 路由租约。
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} 与行锁、应用层 lease token 围栏分属不同层次，互不替代。{@code cast(? as uuid)}
   * 在无路由 节点时绑定 null，因此带环境亲和性的行不会被任何无节点 claim 领取。
   */
  private static final String CLAIM_NEXT =
      """
      with candidate as (
          select target_id, available_at, wake_version, lease_token, lease_until,
                 required_environment_id, date_trunc('milliseconds', statement_timestamp()) as database_now
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
                      and ec.owner_node_id = cast(? as uuid)
                      and ec.status = 'READY'
                      and ec.lease_until > statement_timestamp()
                )
            )
          order by available_at, target_id
          for update skip locked
          limit 1
      )
      select target_id, available_at, wake_version, lease_token, lease_until,
             required_environment_id, database_now
      from candidate
      """;

  private final String targetType;
  private final DurableWorkJdbc jdbc;

  /** 生产构造：默认 JDBC 钩子加入调用方事务，没有外层事务时开启短事务。 */
  public DurableWorkStore(
      String targetType, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this(targetType, new JdbcDurableWorkJdbc(jdbc, transactionManager));
  }

  /** Harness 等自有事务核心的构造：SQL 与事务状态由调用方提供的钩子承载。 */
  public DurableWorkStore(String targetType, DurableWorkJdbc jdbc) {
    if (targetType == null || targetType.isBlank()) {
      throw new IllegalArgumentException("targetType must not be blank");
    }
    this.targetType = targetType;
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public String targetType() {
    return targetType;
  }

  /** 请求一次立即 wake：新行 wakeVersion=1；已有行递增 wakeVersion、due 取更早值并保留当前租约。 */
  public DurableWork request(UUID targetId) {
    return request(targetId, Duration.ZERO, null);
  }

  /** 请求一次可延迟的 wake。新行 due 为数据库此刻加 {@code delay}；已有行递增 wakeVersion，due 取更早值并保留当前租约。 */
  public DurableWork request(UUID targetId, Duration delay) {
    return request(targetId, delay, null);
  }

  /** 请求一次立即 wake，并可选携带环境亲和性；已冻结的亲和性只允许传 null（不改变）或相同值。 */
  public DurableWork request(UUID targetId, UUID requiredEnvironmentId) {
    return request(targetId, Duration.ZERO, requiredEnvironmentId);
  }

  /**
   * 请求一次可延迟的 wake，并可选携带环境亲和性。
   *
   * <p>插入使用 {@code on conflict do nothing}，冲突后的更新在行锁下由 {@link WorkState#request} 计算，避免在 SQL 中复制跃迁。
   * {@code delay} 只作用于本次请求的 due，不改变已有更早的 due。
   */
  public DurableWork request(UUID targetId, Duration delay, UUID requiredEnvironmentId) {
    Objects.requireNonNull(targetId, "targetId");
    Duration delayValue = requireNonNegativeWholeMillis(delay, "delay");
    return jdbc.inTransaction(
        () -> {
          while (true) {
            Instant now = databaseNow();
            Instant due = now.plus(delayValue);
            WorkState initial = WorkState.initial(due, requiredEnvironmentId);
            Optional<DurableWork> inserted =
                jdbc.queryOne(
                    INSERT_WORK,
                    WORK_MAPPER,
                    targetType,
                    targetId,
                    Timestamp.from(initial.availableAt()),
                    initial.wakeVersion(),
                    requiredEnvironmentId);
            if (inserted.isPresent()) {
              notifyIfClaimable(targetId);
              return inserted.get();
            }
            Optional<Row> existing =
                jdbc.queryOne(SELECT_ROW_FOR_UPDATE, ROW_MAPPER, targetType, targetId);
            if (existing.isEmpty()) {
              // 竞争事务在 insert 冲突与 select 之间删除了行：按新建重试。
              continue;
            }
            WorkState requested = existing.get().work().state().request(due, requiredEnvironmentId);
            update(targetId, requested);
            notifyIfClaimable(targetId);
            return new DurableWork(targetId, requested);
          }
        });
  }

  public Optional<DurableWork> find(UUID targetId) {
    return jdbc.queryOne(
        SELECT_WORK, WORK_MAPPER, targetType, Objects.requireNonNull(targetId, "targetId"));
  }

  /** 锁定 Work 行；要求调用方已有活动事务，锁保持到事务结束。 */
  public Optional<DurableWork> lock(UUID targetId) {
    requireActiveTransaction();
    return jdbc.queryOne(
        SELECT_ROW_FOR_UPDATE,
        (resultSet, rowNumber) -> ROW_MAPPER.mapRow(resultSet, rowNumber).work(),
        targetType,
        Objects.requireNonNull(targetId, "targetId"));
  }

  /** 在已有事务中锁定仍由 {@code claim} 持有且租约有效的 Work 行；失权返回空且不产生锁。 */
  public Optional<DurableWork> lockClaimed(WorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    requireActiveTransaction();
    return jdbc.queryOne(
        SELECT_OWNED_ROW_FOR_UPDATE,
        (resultSet, rowNumber) -> ROW_MAPPER.mapRow(resultSet, rowNumber).work(),
        targetType,
        claim.targetId(),
        claim.leaseToken());
  }

  /** 领取下一条到期且无有效租约的 Work（无节点路由围栏）。 */
  public Optional<WorkClaim> claimNext(String leaseToken, Duration leaseDuration) {
    return claimNext(leaseToken, leaseDuration, null);
  }

  /** 领取下一条到期且无有效租约的 Work；带环境亲和性的行只由 {@code nodeInstanceId} 持有 READY 路由租约时可见。 */
  public Optional<WorkClaim> claimNext(
      String leaseToken, Duration leaseDuration, UUID nodeInstanceId) {
    String token = WorkState.requireLeaseToken(leaseToken);
    Duration lease = requirePositiveWholeMillis(leaseDuration, "leaseDuration");
    return jdbc.inTransaction(
        () -> {
          Optional<Row> candidate =
              jdbc.queryOne(CLAIM_NEXT, ROW_MAPPER, targetType, nodeInstanceId);
          if (candidate.isEmpty()) {
            return Optional.empty();
          }
          Row row = candidate.get();
          WorkState claimed = row.work().state().claim(row.now(), token, row.now().plus(lease));
          update(row.work().targetId(), claimed);
          return Optional.of(
              new WorkClaim(
                  row.work().targetId(),
                  claimed.wakeVersion(),
                  claimed.leaseToken(),
                  claimed.leaseUntil(),
                  claimed.requiredEnvironmentId()));
        });
  }

  /**
   * 延长仍由 {@code claim} 持有的租约；已覆盖目标时点返回 false 且不写。行不存在抛 {@link IllegalStateException}，token
   * 不匹配或租约过期抛 {@link IllegalArgumentException}。
   */
  public boolean renew(WorkClaim claim, Duration leaseDuration) {
    Objects.requireNonNull(claim, "claim");
    Duration lease = requirePositiveWholeMillis(leaseDuration, "leaseDuration");
    return jdbc.inTransaction(
        () -> {
          Row row = lockRow(claim.targetId());
          WorkState state = row.work().state();
          state.requireActiveLease(claim.leaseToken(), row.now());
          Instant target = row.now().plus(lease);
          if (!target.isAfter(state.leaseUntil())) {
            return false;
          }
          update(claim.targetId(), state.renew(claim.leaseToken(), row.now(), target));
          return true;
        });
  }

  /**
   * 完成 claim：wakeVersion 未变则删除行并返回空；处理期间到达新 wake 则清除租约、保留行并返回新状态。
   *
   * <p>行不存在抛 {@link IllegalStateException}，token 不匹配或租约过期抛 {@link IllegalArgumentException}。
   */
  public Optional<DurableWork> complete(WorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    return jdbc.inTransaction(
        () -> {
          Row row = lockRow(claim.targetId());
          Optional<WorkState> next =
              row.work()
                  .state()
                  .complete(claim.leaseToken(), claim.claimedWakeVersion(), row.now());
          if (next.isEmpty()) {
            int deleted = jdbc.update(DELETE_WORK, targetType, claim.targetId());
            if (deleted != 1) {
              throw new IllegalStateException(
                  "work does not exist for target " + claim.targetId() + " (lost ownership)");
            }
            return Optional.empty();
          }
          update(claim.targetId(), next.get());
          notifyIfClaimable(claim.targetId());
          return Optional.of(new DurableWork(claim.targetId(), next.get()));
        });
  }

  /**
   * 归还 claim 并把下一次处理推迟 {@code delay}；处理期间到达的新 wake 保留更早的 due。
   *
   * <p>行不存在抛 {@link IllegalStateException}，token 不匹配或租约过期抛 {@link IllegalArgumentException}。
   */
  public void reschedule(WorkClaim claim, Duration delay) {
    Objects.requireNonNull(claim, "claim");
    Duration delayValue = requireNonNegativeWholeMillis(delay, "delay");
    jdbc.inTransaction(
        () -> {
          Row row = lockRow(claim.targetId());
          WorkState next =
              row.work()
                  .state()
                  .reschedule(
                      claim.leaseToken(),
                      claim.claimedWakeVersion(),
                      row.now(),
                      row.now().plus(delayValue));
          update(claim.targetId(), next);
          notifyIfClaimable(claim.targetId());
          return null;
        });
  }

  /** 以数据库时钟判断 claim 是否仍持有有效租约。 */
  public boolean isOwned(WorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    return Boolean.TRUE.equals(
        jdbc.queryOne(
                "select exists (select 1 from durable_work where target_type = ? and target_id = ?"
                    + " and lease_token = ? and lease_until > statement_timestamp()) as owned",
                (resultSet, rowNumber) -> resultSet.getBoolean("owned"),
                targetType,
                claim.targetId(),
                claim.leaseToken())
            .orElse(false));
  }

  /** 无条件删除 target 的 mailbox 行；返回是否实际删除。 */
  public boolean delete(UUID targetId) {
    return jdbc.update(DELETE_WORK, targetType, Objects.requireNonNull(targetId, "targetId")) == 1;
  }

  /** 批量删除 target 的 mailbox 行；返回实际删除数。 */
  public int deleteAll(Collection<UUID> targetIds) {
    Objects.requireNonNull(targetIds, "targetIds");
    if (targetIds.isEmpty()) {
      return 0;
    }
    List<UUID> ids = List.copyOf(targetIds);
    String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
    Object[] arguments = new Object[ids.size() + 1];
    arguments[0] = targetType;
    for (int index = 0; index < ids.size(); index++) {
      arguments[index + 1] = ids.get(index);
    }
    return jdbc.update(
        "delete from durable_work where target_type = ? and target_id in (" + placeholders + ")",
        arguments);
  }

  private Row lockRow(UUID targetId) {
    return jdbc.queryOne(SELECT_ROW_FOR_UPDATE, ROW_MAPPER, targetType, targetId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "work does not exist for target " + targetId + " (lost ownership)"));
  }

  private Instant databaseNow() {
    return jdbc.queryOne(
            "select " + NOW + " as database_now",
            (resultSet, rowNumber) -> instant(resultSet.getTimestamp("database_now")))
        .orElseThrow(() -> new IllegalStateException("statement_timestamp returned no row"));
  }

  private void update(UUID targetId, WorkState state) {
    int updated =
        jdbc.update(
            UPDATE_WORK,
            Timestamp.from(state.availableAt()),
            state.wakeVersion(),
            state.leaseToken(),
            timestamp(state.leaseUntil()),
            targetType,
            targetId);
    if (updated != 1) {
      throw new IllegalStateException(
          "work does not exist for target " + targetId + " (lost ownership)");
    }
  }

  /** due 已到时发送提示；新 wake 即使到达有效租约期间也必须可见，未来 due 留给轮询。 */
  private void notifyIfClaimable(UUID targetId) {
    jdbc.queryOne(
        "select pg_notify('"
            + CHANNEL
            + "', target_type) as ignored, true as sent from durable_work"
            + " where target_type = ? and target_id = ?"
            + " and available_at <= clock_timestamp()",
        (resultSet, rowNumber) -> resultSet.getBoolean("sent"),
        targetType,
        targetId);
  }

  private void requireActiveTransaction() {
    if (!jdbc.transactionActive()) {
      throw new IllegalStateException("work row locking requires an active transaction");
    }
  }

  private static Duration requirePositiveWholeMillis(Duration duration, String name) {
    Duration value = requireNonNegativeWholeMillis(duration, name);
    if (value.isZero()) {
      throw new IllegalArgumentException(name + " must be at least 1ms");
    }
    return value;
  }

  private static Duration requireNonNegativeWholeMillis(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    long millis = duration.toMillis();
    if (duration.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    if (!duration.equals(Duration.ofMillis(millis))) {
      throw new IllegalArgumentException(name + " must be a whole number of milliseconds");
    }
    return duration;
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private record Row(DurableWork work, Instant now) {}
}
