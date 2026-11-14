package fun.fengwk.kkstudio.platform.environment.registry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.EnvironmentChangeNotifier;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 握手准入的路由租约抢占：单条 PostgreSQL upsert 在一个短事务内完成 token 校验、租约接管与 CONNECTING 事件追加。
 *
 * <p>只有真实 upsert 成功（Acquired）才在提交前发布 {@code environment_changed}；认证拒绝与活跃租约抢占失败都不写行，保持静默。行锁顺序与
 * {@link EnvironmentRegistry} 的围栏写一致：先锁 {@code environment} 卡片行，再写 {@code environment_connection}。
 */
final class EnvironmentLeaseAdmission {

  private static final String TRY_ACQUIRE_SQL =
      """
      with locked_env as (
          select id, (registration_token_hash = :tokenHash) as token_valid
          from environment
          where id = :environmentId
          for update
      ),
      tried_upsert as (
          insert into environment_connection (
              environment_id, owner_node_id, lease_token,
              status, ready_metadata, recent_events, last_seen_at, lease_until
          )
          select
              id, :ownerNodeId, :leaseToken,
              'CONNECTING', null, jsonb_build_array(:connectingEvent::jsonb), statement_timestamp(), statement_timestamp() + (:leaseMillis * interval '1 millisecond')
          from locked_env
          where token_valid = true
          on conflict (environment_id) do update
          set owner_node_id = excluded.owner_node_id,
              lease_token = excluded.lease_token,
              status = 'CONNECTING',
              recent_events = case
                  when jsonb_array_length(environment_connection.recent_events) >= :maxRecentEvents
                      then (environment_connection.recent_events - 0) || excluded.recent_events
                      else environment_connection.recent_events || excluded.recent_events
                  end,
              last_seen_at = statement_timestamp(),
              lease_until = statement_timestamp() + (:leaseMillis * interval '1 millisecond')
          where environment_connection.lease_until <= statement_timestamp()
             or (environment_connection.status = 'CONNECTING' and environment_connection.owner_node_id = excluded.owner_node_id)
          returning environment_connection.lease_token, true as acquired
      )
      select lease_token, true as acquired, 'ACQUIRED' as result_type
      from tried_upsert
      union all
      select null::uuid as lease_token, false as acquired,
             case
                when not exists (select 1 from locked_env) then 'NOT_FOUND'
                when not (select token_valid from locked_env) then 'INVALID_TOKEN'
                else 'ACTIVE_ROUTE'
             end as result_type
      from (select 1) as dummy
      where not exists (select 1 from tried_upsert)
      """;

  private final JdbcClient jdbcClient;
  private final UUID ownerNodeId;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;
  private final EnvironmentChangeNotifier notifier;
  private final EnvironmentStateCodec stateCodec = new EnvironmentStateCodec();

  EnvironmentLeaseAdmission(
      JdbcClient jdbcClient,
      UUID ownerNodeId,
      Clock clock,
      TransactionTemplate transactionTemplate,
      EnvironmentChangeNotifier notifier) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  /**
   * 原子校验注册凭据并抢占或续约路由租约。
   *
   * @return 类型化绑定结果（Acquired / RetryLater / Rejected）
   */
  LeaseBindResult bind(
      EnvironmentId environmentId, String registrationToken, Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(registrationToken, "registrationToken");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    UUID newLeaseToken = UUID.randomUUID();
    String connectingEvent = connectingEvent();
    Optional<AcquireRow> acquired =
        acquire(environmentId, registrationToken, leaseDuration, newLeaseToken, connectingEvent);
    if (acquired.isEmpty()) {
      return new LeaseBindResult.RetryLater("route acquisition returned no row: " + environmentId);
    }
    return toBindResult(environmentId, acquired.get());
  }

  /** 构造随租约一起写入的事件；{@code message} 是固定事实，绝不承载 Daemon 输入。 */
  private String connectingEvent() {
    return stateCodec.encodeEvent(
        new EnvironmentEvent(
            clock.instant(),
            EnvironmentEventLevel.INFO,
            EnvironmentEventType.CONNECTING,
            "daemon connection accepted"));
  }

  private Optional<AcquireRow> acquire(
      EnvironmentId environmentId,
      String registrationToken,
      Duration leaseDuration,
      UUID newLeaseToken,
      String connectingEvent) {
    return transactionTemplate.execute(
        status -> {
          List<AcquireRow> rows =
              jdbcClient
                  .sql(TRY_ACQUIRE_SQL)
                  .param("tokenHash", RegistrationTokens.hash(registrationToken))
                  .param("environmentId", environmentId.value())
                  .param("ownerNodeId", ownerNodeId)
                  .param("leaseToken", newLeaseToken)
                  .param("connectingEvent", connectingEvent)
                  .param("leaseMillis", leaseDuration.toMillis())
                  .param("maxRecentEvents", EnvironmentConnection.MAX_RECENT_EVENTS)
                  .query(
                      (rs, rowNum) ->
                          new AcquireRow(
                              (UUID) rs.getObject("lease_token"),
                              rs.getBoolean("acquired"),
                              rs.getString("result_type")))
                  .list();
          if (rows.isEmpty()) {
            return Optional.<AcquireRow>empty();
          }
          AcquireRow candidate = rows.get(0);
          if (candidate.acquired() && candidate.leaseToken() != null) {
            notifier.environmentChanged(environmentId.value());
          }
          return Optional.of(candidate);
        });
  }

  private static LeaseBindResult toBindResult(EnvironmentId environmentId, AcquireRow row) {
    if (row.acquired() && row.leaseToken() != null) {
      return new LeaseBindResult.Acquired(row.leaseToken());
    }
    return switch (row.resultType()) {
      case "NOT_FOUND" -> new LeaseBindResult.Rejected("environment not found: " + environmentId);
      case "INVALID_TOKEN" -> new LeaseBindResult.Rejected(
          "invalid registration token for environment: " + environmentId);
      case "ACTIVE_ROUTE" -> new LeaseBindResult.RetryLater(
          "environment " + environmentId + " is actively held by another connection");
      default -> new LeaseBindResult.RetryLater(
          "route acquisition failed for environment: " + environmentId);
    };
  }

  private record AcquireRow(UUID leaseToken, boolean acquired, String resultType) {}
}
