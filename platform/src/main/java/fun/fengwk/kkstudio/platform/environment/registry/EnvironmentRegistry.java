package fun.fengwk.kkstudio.platform.environment.registry;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 PostgreSQL 路由租约表 {@code environment_connection} 的多节点 Environment 连接注册表。
 *
 * <p>以 canonical {@link EnvironmentId} 为键。HELLO 认证时通过单条 PostgreSQL upsert 语句原子抢占租约： 缺少或过期路由以新
 * {@code leaseToken} 接管；活跃路由返回 {@link BindResult.RetryLater}。断开连接时保留重连宽限期，允许原连接重新认领。
 *
 * <p>READY、HEARTBEAT 与断开连接均以 {@code (environment_id, owner_node_id, lease_token)} 为围栏更新，
 * 保证只有当前持有该路由租约的本地连接能推进状态。
 */
@Component
public class EnvironmentRegistry {

  private static final String TRY_ACQUIRE_SQL =
      """
      with locked_env as (
          select id, (registration_token = ?) as token_valid
          from environment
          where id = ?
          for update
      ),
      tried_upsert as (
          insert into environment_connection (
              environment_id, owner_node_id, lease_token,
              status, runtime_info, last_seen_at, lease_until
          )
          select
              id, ?, ?,
              'CONNECTING', null, statement_timestamp(), statement_timestamp() + (? * interval '1 millisecond')
          from locked_env
          where token_valid = true
          on conflict (environment_id) do update
          set owner_node_id = excluded.owner_node_id,
              lease_token = excluded.lease_token,
              status = 'CONNECTING',
              runtime_info = null,
              last_seen_at = statement_timestamp(),
              lease_until = statement_timestamp() + (? * interval '1 millisecond')
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

  private static final String MARK_READY_SQL =
      """
      update environment_connection
      set status = 'READY',
          runtime_info = ?::jsonb,
          last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (? * interval '1 millisecond')
      where environment_id = ?
        and owner_node_id = ?
        and lease_token = ?
        and lease_until > statement_timestamp()
      """;

  private static final String HEARTBEAT_SQL =
      """
      update environment_connection
      set last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (? * interval '1 millisecond')
      where environment_id = ?
        and owner_node_id = ?
        and lease_token = ?
        and lease_until > statement_timestamp()
      """;

  private static final String DISCONNECT_SQL =
      """
      update environment_connection
      set status = 'CONNECTING',
          runtime_info = null,
          last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (? * interval '1 millisecond')
      where environment_id = ?
        and owner_node_id = ?
        and lease_token = ?
        and lease_until > statement_timestamp()
      """;

  private static final String FIND_SQL =
      """
      select environment_id, owner_node_id, lease_token,
             status, runtime_info, last_seen_at, lease_until
      from environment_connection
      where environment_id = ?
      """;

  private static final String HAS_ACTIVE_LEASE_SQL =
      """
      select exists (
          select 1 from environment_connection
          where environment_id = ?
            and lease_until > statement_timestamp()
      )
      """;

  private static final String HOLDS_READY_LEASE_SQL =
      """
      select exists (
          select 1 from environment_connection
          where environment_id = ?
            and owner_node_id = ?
            and lease_token = ?
            and status = 'READY'
            and lease_until > statement_timestamp()
      )
      """;

  private static final String HAS_ACTIVE_LEASE_TOKEN_SQL =
      """
      select exists (
          select 1 from environment_connection
          where environment_id = ?
            and owner_node_id = ?
            and lease_token = ?
            and lease_until > statement_timestamp()
      )
      """;

  private static final String LIST_SQL =
      """
      select environment_id, owner_node_id, lease_token,
             status, runtime_info, last_seen_at, lease_until
      from environment_connection
      order by environment_id asc
      """;

  private final JdbcTemplate jdbcTemplate;
  private final UUID ownerNodeId;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();

  public EnvironmentRegistry(
      JdbcTemplate jdbcTemplate, @Qualifier("nodeInstanceId") UUID ownerNodeId) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
  }

  public UUID ownerNodeId() {
    return ownerNodeId;
  }

  /**
   * 在 HELLO 认证时，原子校验 registrationToken 并抢占/续约 {@code environmentId} 的路由租约。
   *
   * @param environmentId 环境 UUID
   * @param registrationToken 注册令牌
   * @param leaseDuration 租约时长
   * @return 类型化绑定结果（Acquired / RetryLater / Rejected）
   */
  public BindResult tryAcquire(
      EnvironmentId environmentId, String registrationToken, Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(registrationToken, "registrationToken");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    UUID newLeaseToken = UUID.randomUUID();
    long millis = leaseDuration.toMillis();
    List<AcquireRow> rows =
        jdbcTemplate.query(
            TRY_ACQUIRE_SQL,
            (rs, rowNum) ->
                new AcquireRow(
                    (UUID) rs.getObject("lease_token"),
                    rs.getBoolean("acquired"),
                    rs.getString("result_type")),
            registrationToken,
            environmentId.value(),
            ownerNodeId,
            newLeaseToken,
            millis,
            millis);

    if (rows.isEmpty()) {
      return BindResult.retryLater("route acquisition returned no row: " + environmentId);
    }
    AcquireRow row = rows.get(0);
    if (row.acquired && row.leaseToken != null) {
      return BindResult.acquired(row.leaseToken);
    }
    return switch (row.resultType) {
      case "NOT_FOUND" -> BindResult.rejected("environment not found: " + environmentId);
      case "INVALID_TOKEN" -> BindResult.rejected(
          "invalid registration token for environment: " + environmentId);
      case "ACTIVE_ROUTE" -> BindResult.retryLater(
          "environment " + environmentId + " is actively held by another connection");
      default -> BindResult.retryLater(
          "route acquisition failed for environment: " + environmentId);
    };
  }

  /**
   * 将环境标记为 READY 并写入 daemon 版本化能力与运行信息对象。
   *
   * @return 围栏校验成功且更新 1 行返回 true；若租约已被夺取（0 行）返回 false
   */
  public boolean markReady(
      EnvironmentId environmentId,
      UUID leaseToken,
      DaemonCapabilities capabilities,
      Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    String runtimeInfoJson = capabilitiesCodec.encode(capabilities);
    int updated =
        jdbcTemplate.update(
            MARK_READY_SQL,
            runtimeInfoJson,
            leaseDuration.toMillis(),
            environmentId.value(),
            ownerNodeId,
            leaseToken);
    return updated > 0;
  }

  /**
   * 刷新路由租约的 last_seen 与 lease_until。
   *
   * @return 围栏校验成功且更新 1 行返回 true；若租约已被夺取（0 行）返回 false
   */
  public boolean heartbeat(EnvironmentId environmentId, UUID leaseToken, Duration leaseDuration) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    int updated =
        jdbcTemplate.update(
            HEARTBEAT_SQL,
            leaseDuration.toMillis(),
            environmentId.value(),
            ownerNodeId,
            leaseToken);
    return updated > 0;
  }

  /**
   * 连接断开时，围栏式将状态回退为 CONNECTING，清空 runtime_info 并保留重连宽限租约。
   *
   * @return 围栏校验成功返回 true
   */
  public boolean disconnect(EnvironmentId environmentId, UUID leaseToken, Duration graceDuration) {
    if (environmentId == null || leaseToken == null) {
      return false;
    }
    try {
      int updated =
          jdbcTemplate.update(
              DISCONNECT_SQL,
              graceDuration.toMillis(),
              environmentId.value(),
              ownerNodeId,
              leaseToken);
      return updated > 0;
    } catch (DataAccessException ignored) {
      return false;
    }
  }

  /** 查询数据库中指定环境的当前路由快照。 */
  public Optional<EnvironmentConnection> find(EnvironmentId environmentId) {
    if (environmentId == null) {
      return Optional.empty();
    }
    List<EnvironmentConnection> results =
        jdbcTemplate.query(FIND_SQL, new EnvironmentConnectionRowMapper(), environmentId.value());
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /** 查询当前数据库中所有活跃环境的路由快照列表。 */
  public List<EnvironmentConnection> list() {
    return jdbcTemplate.query(LIST_SQL, new EnvironmentConnectionRowMapper());
  }

  /** 判定环境当前是否可用（READY 状态且租约未过期）。 */
  public boolean isReady(EnvironmentId environmentId, Instant now, Duration heartbeatTimeout) {
    return find(environmentId).map(env -> env.isReady(now, heartbeatTimeout)).orElse(false);
  }

  /** 判定环境是否有活跃租约（无论 CONNECTING 还是 READY）。 */
  public boolean isOnline(EnvironmentId environmentId, Instant now) {
    return find(environmentId).map(env -> env.isOnline(now)).orElse(false);
  }

  /** 数据库现在时判定指定环境是否有任意活跃租约（用于 admin rotateToken/delete 在锁行下的安全准入）。 */
  public boolean hasActiveLease(EnvironmentId environmentId) {
    if (environmentId == null) {
      return false;
    }
    Boolean exists =
        jdbcTemplate.queryForObject(HAS_ACTIVE_LEASE_SQL, Boolean.class, environmentId.value());
    return Boolean.TRUE.equals(exists);
  }

  /** 数据库现在时判定当前节点是否持有有效的 READY 路由租约（用于本地 capability INVOKE 准入）。 */
  public boolean holdsReadyLease(EnvironmentId environmentId, UUID leaseToken) {
    if (environmentId == null || leaseToken == null) {
      return false;
    }
    Boolean exists =
        jdbcTemplate.queryForObject(
            HOLDS_READY_LEASE_SQL, Boolean.class, environmentId.value(), ownerNodeId, leaseToken);
    return Boolean.TRUE.equals(exists);
  }

  /** 数据库现在时判定当前节点是否持有活跃的连接代币（用于 HELLO 同节点活跃连接防冲突保护）。 */
  public boolean hasActiveLeaseToken(EnvironmentId environmentId, UUID leaseToken) {
    if (environmentId == null || leaseToken == null) {
      return false;
    }
    Boolean exists =
        jdbcTemplate.queryForObject(
            HAS_ACTIVE_LEASE_TOKEN_SQL,
            Boolean.class,
            environmentId.value(),
            ownerNodeId,
            leaseToken);
    return Boolean.TRUE.equals(exists);
  }

  private record AcquireRow(UUID leaseToken, boolean acquired, String resultType) {}

  private final class EnvironmentConnectionRowMapper implements RowMapper<EnvironmentConnection> {
    @Override
    public EnvironmentConnection mapRow(ResultSet rs, int rowNum) throws SQLException {
      EnvironmentId id = EnvironmentId.of((UUID) rs.getObject("environment_id"));
      UUID owner = (UUID) rs.getObject("owner_node_id");
      UUID leaseToken = (UUID) rs.getObject("lease_token");
      LiveEnvironmentStatus status = LiveEnvironmentStatus.valueOf(rs.getString("status"));
      String runtimeInfoJson = rs.getString("runtime_info");
      DaemonCapabilities capabilities = null;
      if (status == LiveEnvironmentStatus.READY && runtimeInfoJson != null) {
        capabilities = capabilitiesCodec.decode(runtimeInfoJson);
      }
      Instant lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime.class).toInstant();
      Instant leaseUntil = rs.getObject("lease_until", OffsetDateTime.class).toInstant();
      return new EnvironmentConnection(
          id, owner, leaseToken, status, capabilities, lastSeenAt, leaseUntil);
    }
  }
}
