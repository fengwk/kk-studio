package fun.fengwk.kkstudio.work;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一种 target type 在 {@code durable_work} 上的队列视图。
 *
 * <p>所有时间由数据库 {@code statement_timestamp()} 截断到毫秒得出。会让行变为可领取的写入（请求、释放、重排）与到期提示在同一事务内完成：
 * 调用方已有事务时加入，否则自开短事务，避免「事实已提交、提示尚未发送」。
 */
public final class DurableWorkQueue {

  /** 全部 Durable Work 共用的 NOTIFY channel；payload 是 target type。 */
  public static final String CHANNEL = "durable_work";

  private static final int MAX_LEASE_TOKEN_LENGTH = 128;

  private static final String NOW = "date_trunc('milliseconds', statement_timestamp())";

  private static final String COLUMNS =
      "target_id, wake_version, available_at, lease_token, lease_until";

  private static final RowMapper<DurableWork> WORK =
      (rs, row) ->
          new DurableWork(
              rs.getObject("target_id", UUID.class),
              rs.getLong("wake_version"),
              rs.getObject("available_at", OffsetDateTime.class).toInstant(),
              rs.getString("lease_token"),
              instant(rs.getObject("lease_until", OffsetDateTime.class)));

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final String targetType;

  public DurableWorkQueue(
      JdbcClient jdbc, PlatformTransactionManager transactionManager, String targetType) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    if (targetType == null || targetType.isBlank()) {
      throw new IllegalArgumentException("targetType must not be blank");
    }
    this.targetType = targetType;
  }

  public String targetType() {
    return targetType;
  }

  /** 请求一次唤醒：新行 wakeVersion=1；已有行递增 wakeVersion、due 取更早值并保留当前租约。 */
  public DurableWork request(UUID targetId, Duration delay) {
    Objects.requireNonNull(targetId, "targetId");
    long delayMillis = nonNegativeMillis(delay, "delay");
    return transactions.execute(
        status -> {
          DurableWork work =
              jdbc.sql(
                      "insert into durable_work (target_type, target_id, available_at, wake_version)"
                          + " values (:type, :id, "
                          + NOW
                          + " + :delay * interval '1 millisecond', 1)"
                          + " on conflict (target_type, target_id) do update"
                          + " set available_at = least(durable_work.available_at,"
                          + " excluded.available_at),"
                          + " wake_version = durable_work.wake_version + 1"
                          + " returning "
                          + COLUMNS)
                  .param("type", targetType)
                  .param("id", targetId)
                  .param("delay", delayMillis)
                  .query(WORK)
                  .single();
          notifyIfDue(targetId);
          return work;
        });
  }

  public Optional<DurableWork> find(UUID targetId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from durable_work where target_type = :type and target_id = :id")
        .param("type", targetType)
        .param("id", Objects.requireNonNull(targetId, "targetId"))
        .query(WORK)
        .optional();
  }

  /** 按 (availableAt, targetId) 领取下一条已到期且无有效租约的行。 */
  public Optional<WorkClaim> claimNext(String leaseToken, Duration leaseDuration) {
    String token = requireToken(leaseToken);
    long leaseMillis = positiveMillis(leaseDuration, "leaseDuration");
    return jdbc.sql(
            "with candidate as ("
                + " select target_id from durable_work"
                + " where target_type = :type and available_at <= "
                + NOW
                + " and (lease_until is null or lease_until <= "
                + NOW
                + ")"
                + " order by available_at, target_id"
                + " for update skip locked limit 1)"
                + " update durable_work work set lease_token = :token,"
                + " lease_until = "
                + NOW
                + " + :lease * interval '1 millisecond'"
                + " from candidate"
                + " where work.target_type = :type and work.target_id = candidate.target_id"
                + " returning work.target_id, work.wake_version, work.available_at,"
                + " work.lease_token, work.lease_until")
        .param("type", targetType)
        .param("token", token)
        .param("lease", leaseMillis)
        .query(WORK)
        .optional()
        .map(work -> new WorkClaim(work.targetId(), work.wakeVersion(), token, work.leaseUntil()));
  }

  /** 把仍有效的租约延长到至少「此刻 + leaseDuration」；租约已失效或 token 不符返回 false。 */
  public boolean renew(WorkClaim claim, Duration leaseDuration) {
    Objects.requireNonNull(claim, "claim");
    long leaseMillis = positiveMillis(leaseDuration, "leaseDuration");
    return jdbc.sql(
                "update durable_work set lease_until = greatest(lease_until, "
                    + NOW
                    + " + :lease * interval '1 millisecond')"
                    + ownedBy())
            .param("lease", leaseMillis)
            .param("type", targetType)
            .param("id", claim.targetId())
            .param("token", claim.leaseToken())
            .update()
        == 1;
  }

  /** 完成 claim：wakeVersion 未变则删除行并返回 true；处理期间到达新 wake 则释放租约、立即到期并返回 false。租约已失效时不写入并返回 false。 */
  public boolean complete(WorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    return transactions.execute(
        status -> {
          if (jdbc.sql("delete from durable_work" + ownedBy() + " and wake_version = :version")
                  .param("type", targetType)
                  .param("id", claim.targetId())
                  .param("token", claim.leaseToken())
                  .param("version", claim.wakeVersion())
                  .update()
              == 1) {
            return true;
          }
          if (jdbc.sql(
                      "update durable_work set lease_token = null, lease_until = null,"
                          + " available_at = least(available_at, "
                          + NOW
                          + ")"
                          + ownedBy())
                  .param("type", targetType)
                  .param("id", claim.targetId())
                  .param("token", claim.leaseToken())
                  .update()
              == 1) {
            notifyIfDue(claim.targetId());
          }
          return false;
        });
  }

  /** 归还 claim 并把下一次处理推迟 {@code delay}；处理期间到达的新 wake 保留更早的 due。租约已失效时不写入并返回 false。 */
  public boolean reschedule(WorkClaim claim, Duration delay) {
    Objects.requireNonNull(claim, "claim");
    long delayMillis = nonNegativeMillis(delay, "delay");
    return transactions.execute(
        status -> {
          boolean written =
              jdbc.sql(
                          "update durable_work set lease_token = null, lease_until = null,"
                              + " available_at = case when wake_version = :version then "
                              + NOW
                              + " + :delay * interval '1 millisecond'"
                              + " else least(available_at, "
                              + NOW
                              + " + :delay * interval '1 millisecond') end"
                              + ownedBy())
                      .param("version", claim.wakeVersion())
                      .param("delay", delayMillis)
                      .param("type", targetType)
                      .param("id", claim.targetId())
                      .param("token", claim.leaseToken())
                      .update()
                  == 1;
          if (written) {
            notifyIfDue(claim.targetId());
          }
          return written;
        });
  }

  /** 以数据库时钟判断 claim 是否仍持有有效租约。 */
  public boolean isOwned(WorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    return jdbc.sql("select count(*) from durable_work" + ownedBy())
            .param("type", targetType)
            .param("id", claim.targetId())
            .param("token", claim.leaseToken())
            .query(Long.class)
            .single()
        == 1L;
  }

  /** 在调用方事务中锁定仍有效的 claim，锁保持到事务结束；调用方负责先取得目标业务行锁。 */
  public boolean lockOwned(WorkClaim claim) {
    Objects.requireNonNull(claim, "claim");
    return jdbc.sql("select target_id from durable_work" + ownedBy() + " for update")
        .param("type", targetType)
        .param("id", claim.targetId())
        .param("token", claim.leaseToken())
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  /** 无条件删除 target 的 mailbox 行（target 本身被删除时调用）。 */
  public void delete(UUID targetId) {
    jdbc.sql("delete from durable_work where target_type = :type and target_id = :id")
        .param("type", targetType)
        .param("id", Objects.requireNonNull(targetId, "targetId"))
        .update();
  }

  private static String ownedBy() {
    return " where target_type = :type and target_id = :id and lease_token = :token"
        + " and lease_until > "
        + NOW;
  }

  private void notifyIfDue(UUID targetId) {
    jdbc.sql(
            "select pg_notify('"
                + CHANNEL
                + "', target_type) from durable_work"
                + " where target_type = :type and target_id = :id"
                + " and available_at <= clock_timestamp()"
                + " and (lease_until is null or lease_until <= clock_timestamp())")
        .param("type", targetType)
        .param("id", targetId)
        .query(String.class)
        .list();
  }

  private static String requireToken(String token) {
    if (token == null || token.isBlank() || token.length() > MAX_LEASE_TOKEN_LENGTH) {
      throw new IllegalArgumentException("leaseToken must contain 1 to 128 characters");
    }
    return token;
  }

  private static long nonNegativeMillis(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return duration.toMillis();
  }

  private static long positiveMillis(Duration duration, String name) {
    long millis = nonNegativeMillis(duration, name);
    if (millis == 0) {
      throw new IllegalArgumentException(name + " must be at least 1ms");
    }
    return millis;
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
