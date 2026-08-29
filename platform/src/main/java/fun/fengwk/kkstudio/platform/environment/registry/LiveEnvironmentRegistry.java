package fun.fengwk.kkstudio.platform.environment.registry;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 PostgreSQL 路由租约表 {@code live_environment} 的多节点 Live Environment 注册表。
 *
 * <p>以 canonical {@link EnvironmentName} 为键。HELLO 认证时通过单条 PostgreSQL upsert 语句原子抢占租约： 缺少或过期路由以新
 * {@code routeToken} 接管；同 daemonId 活跃路由返回 {@link BindResult.RetryLater}； 不同 daemonId 活跃路由返回 {@link
 * BindResult.Conflict}（终态冲突）。断开连接时保留重连宽限期，允许同一 daemonId 重新认领。
 *
 * <p>READY、HEARTBEAT 与断开连接均以 {@code (environment_name, owner_node_id, route_token)} 为围栏更新，
 * 保证只有当前持有该路由租约的本地连接能推进状态。
 */
@Component
public class LiveEnvironmentRegistry {

  private static final String TRY_ACQUIRE_SQL =
      """
      with tried_upsert as (
          insert into live_environment (
              environment_name, daemon_id, owner_node_id, route_token,
              status, capabilities, last_seen_at, lease_until
          ) values (
              ?, ?, ?, ?,
              'CONNECTING', null, statement_timestamp(), statement_timestamp() + (? * interval '1 millisecond')
          )
          on conflict (environment_name) do update
          set daemon_id = excluded.daemon_id,
              owner_node_id = excluded.owner_node_id,
              route_token = excluded.route_token,
              status = 'CONNECTING',
              capabilities = null,
              last_seen_at = statement_timestamp(),
              lease_until = statement_timestamp() + (? * interval '1 millisecond')
          where live_environment.lease_until <= statement_timestamp()
          returning live_environment.route_token, live_environment.daemon_id, true as acquired
      )
      select route_token, daemon_id, acquired from tried_upsert
      union all
      select route_token, daemon_id, false as acquired from live_environment
      where environment_name = ? and not exists (select 1 from tried_upsert)
      """;

  private static final String MARK_READY_SQL =
      """
      update live_environment
      set status = 'READY',
          capabilities = ?::jsonb,
          last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (? * interval '1 millisecond')
      where environment_name = ?
        and owner_node_id = ?
        and route_token = ?
      """;

  private static final String HEARTBEAT_SQL =
      """
      update live_environment
      set last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (? * interval '1 millisecond')
      where environment_name = ?
        and owner_node_id = ?
        and route_token = ?
      """;

  private static final String DISCONNECT_SQL =
      """
      update live_environment
      set status = 'CONNECTING',
          capabilities = null,
          last_seen_at = statement_timestamp(),
          lease_until = statement_timestamp() + (? * interval '1 millisecond')
      where environment_name = ?
        and owner_node_id = ?
        and route_token = ?
      """;

  private static final String FIND_SQL =
      """
      select environment_name, daemon_id, owner_node_id, route_token,
             status, capabilities, last_seen_at, lease_until
      from live_environment
      where environment_name = ?
      """;

  private static final String LIST_SQL =
      """
      select environment_name, daemon_id, owner_node_id, route_token,
             status, capabilities, last_seen_at, lease_until
      from live_environment
      order by environment_name asc
      """;

  private final JdbcTemplate jdbcTemplate;
  private final UUID ownerNodeId;
  private final DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();

  public LiveEnvironmentRegistry(
      JdbcTemplate jdbcTemplate, @Qualifier("nodeInstanceId") UUID ownerNodeId) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
  }

  public UUID ownerNodeId() {
    return ownerNodeId;
  }

  /**
   * 在 HELLO 认证后，尝试原子抢占 {@code environmentName} 的路由租约。
   *
   * @param environmentName 规范环境名
   * @param daemonId daemon 实例 ID
   * @param leaseDuration 租约时长
   * @return 类型化绑定结果
   */
  public BindResult tryAcquire(
      EnvironmentName environmentName, String daemonId, Duration leaseDuration) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(daemonId, "daemonId");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    UUID newRouteToken = UUID.randomUUID();
    long millis = leaseDuration.toMillis();
    List<AcquireRow> rows =
        jdbcTemplate.query(
            TRY_ACQUIRE_SQL,
            (rs, rowNum) ->
                new AcquireRow(
                    (UUID) rs.getObject("route_token"),
                    rs.getString("daemon_id"),
                    rs.getBoolean("acquired")),
            environmentName.value(),
            daemonId,
            ownerNodeId,
            newRouteToken,
            millis,
            millis,
            environmentName.value());

    if (rows.isEmpty()) {
      return BindResult.retryLater("route acquisition returned no row: " + environmentName);
    }
    AcquireRow row = rows.get(0);
    if (row.acquired) {
      return BindResult.acquired(newRouteToken);
    }
    if (daemonId.equals(row.daemonId)) {
      return BindResult.retryLater(
          "environment " + environmentName + " is actively held by same daemonId " + daemonId);
    }
    return BindResult.conflict(
        "environment " + environmentName + " is actively held by another daemonId " + row.daemonId);
  }

  /**
   * 将环境标记为 READY 并写入 daemon 版本化能力对象。
   *
   * @return 围栏校验成功且更新 1 行返回 true；若租约已被夺取（0 行）返回 false
   */
  public boolean markReady(
      EnvironmentName environmentName,
      UUID routeToken,
      DaemonCapabilities capabilities,
      Duration leaseDuration) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(routeToken, "routeToken");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    String capabilitiesJson = capabilitiesCodec.encode(capabilities);
    int updated =
        jdbcTemplate.update(
            MARK_READY_SQL,
            capabilitiesJson,
            leaseDuration.toMillis(),
            environmentName.value(),
            ownerNodeId,
            routeToken);
    return updated > 0;
  }

  /**
   * 刷新路由租约的 last_seen 与 lease_until。
   *
   * @return 围栏校验成功且更新 1 行返回 true；若租约已被夺取（0 行）返回 false
   */
  public boolean heartbeat(
      EnvironmentName environmentName, UUID routeToken, Duration leaseDuration) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(routeToken, "routeToken");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    int updated =
        jdbcTemplate.update(
            HEARTBEAT_SQL,
            leaseDuration.toMillis(),
            environmentName.value(),
            ownerNodeId,
            routeToken);
    return updated > 0;
  }

  /**
   * 连接断开时，围栏式将状态回退为 CONNECTING，清空 capabilities 并保留重连宽限租约。
   *
   * @return 围栏校验成功返回 true
   */
  public boolean disconnect(
      EnvironmentName environmentName, UUID routeToken, Duration graceDuration) {
    if (environmentName == null || routeToken == null) {
      return false;
    }
    try {
      int updated =
          jdbcTemplate.update(
              DISCONNECT_SQL,
              graceDuration.toMillis(),
              environmentName.value(),
              ownerNodeId,
              routeToken);
      return updated > 0;
    } catch (DataAccessException ignored) {
      return false;
    }
  }

  /** 查询数据库中指定环境的当前路由快照。 */
  public Optional<LiveEnvironment> find(EnvironmentName environmentName) {
    if (environmentName == null) {
      return Optional.empty();
    }
    List<LiveEnvironment> results =
        jdbcTemplate.query(FIND_SQL, new LiveEnvironmentRowMapper(), environmentName.value());
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  /** 查询当前数据库中所有活跃环境的路由快照列表。 */
  public List<LiveEnvironment> list() {
    return jdbcTemplate.query(LIST_SQL, new LiveEnvironmentRowMapper());
  }

  /** 判定环境当前是否可用（READY 状态且租约未过期）。 */
  public boolean isReady(EnvironmentName environmentName, Instant now, Duration heartbeatTimeout) {
    return find(environmentName).map(env -> env.isReady(now, heartbeatTimeout)).orElse(false);
  }

  private record AcquireRow(UUID routeToken, String daemonId, boolean acquired) {}

  private final class LiveEnvironmentRowMapper implements RowMapper<LiveEnvironment> {
    @Override
    public LiveEnvironment mapRow(ResultSet rs, int rowNum) throws SQLException {
      EnvironmentName name = new EnvironmentName(rs.getString("environment_name"));
      String daemonId = rs.getString("daemon_id");
      UUID owner = (UUID) rs.getObject("owner_node_id");
      UUID routeToken = (UUID) rs.getObject("route_token");
      LiveEnvironmentStatus status = LiveEnvironmentStatus.valueOf(rs.getString("status"));
      String capabilitiesJson = rs.getString("capabilities");
      DaemonCapabilities capabilities = null;
      if (status == LiveEnvironmentStatus.READY && capabilitiesJson != null) {
        capabilities = capabilitiesCodec.decode(capabilitiesJson);
      }
      Instant lastSeenAt = rs.getTimestamp("last_seen_at").toInstant();
      Instant leaseUntil = rs.getTimestamp("lease_until").toInstant();
      return new LiveEnvironment(
          name, daemonId, owner, routeToken, status, capabilities, lastSeenAt, leaseUntil);
    }
  }
}
