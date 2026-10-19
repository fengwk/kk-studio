package fun.fengwk.kkstudio.platform.plugin.persistence;

import static fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes.toInstant;
import static fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes.toOffsetDateTime;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code plugin_credential} 存取：每个已认证 Plugin 至多一行。
 *
 * <p>所有写入都以 {@code version} 做 CAS 并让它 +1；刷新路径额外要求持有自己的刷新租约，因此迟到的终结、调用期拒绝与人工替换互不覆盖。
 */
@Repository
public class PluginCredentialRepository {

  private static final String COLUMNS =
      "plugin_id, payload_json, region, expires_at, next_refresh_at, status, last_refreshed_at,"
          + " last_refresh_error, refresh_lease_token, refresh_lease_until, version";

  private static final RowMapper<PluginCredentialRow> ROW_MAPPER =
      (rs, row) ->
          new PluginCredentialRow(
              rs.getString("plugin_id"),
              rs.getString("payload_json"),
              rs.getString("region"),
              toInstant(rs.getObject("expires_at", OffsetDateTime.class)),
              toInstant(rs.getObject("next_refresh_at", OffsetDateTime.class)),
              PluginCredentialStatus.valueOf(rs.getString("status")),
              toInstant(rs.getObject("last_refreshed_at", OffsetDateTime.class)),
              rs.getString("last_refresh_error"),
              rs.getString("refresh_lease_token"),
              toInstant(rs.getObject("refresh_lease_until", OffsetDateTime.class)),
              rs.getLong("version"));

  private final JdbcClient jdbc;

  public PluginCredentialRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public Optional<PluginCredentialRow> find(String pluginId) {
    return jdbc.sql("select " + COLUMNS + " from plugin_credential where plugin_id = :pluginId")
        .param("pluginId", pluginId)
        .query(ROW_MAPPER)
        .optional();
  }

  /** 写入一份新的已连接凭据：没有行时插入，有行时整体替换并清空刷新状态。 */
  public void upsertConnected(
      String pluginId,
      String payloadJson,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt) {
    jdbc.sql(
            """
            insert into plugin_credential (
                plugin_id, payload_json, region, expires_at, next_refresh_at, status)
            values (:pluginId, :payloadJson, :region, :expiresAt, :nextRefreshAt, 'CONNECTED')
            on conflict (plugin_id) do update
            set payload_json = excluded.payload_json,
                region = excluded.region,
                expires_at = excluded.expires_at,
                next_refresh_at = excluded.next_refresh_at,
                status = 'CONNECTED',
                last_refreshed_at = null,
                last_refresh_error = null,
                refresh_lease_token = null,
                refresh_lease_until = null,
                version = plugin_credential.version + 1,
                updated_at = current_timestamp
            """)
        .param("pluginId", pluginId)
        .param("payloadJson", payloadJson)
        .param("region", region)
        .param("expiresAt", toOffsetDateTime(expiresAt))
        .param("nextRefreshAt", toOffsetDateTime(nextRefreshAt))
        .update();
  }

  public void delete(String pluginId) {
    jdbc.sql("delete from plugin_credential where plugin_id = :pluginId")
        .param("pluginId", pluginId)
        .update();
  }

  /** 调用期确定性拒绝：只作废读取时的那一版凭据。 */
  public boolean rejectUsed(String pluginId, long version, String error) {
    return jdbc.sql(
                """
                update plugin_credential
                set status = 'REAUTH_REQUIRED', last_refresh_error = :error,
                    version = version + 1, updated_at = current_timestamp
                where plugin_id = :pluginId and version = :version
                """)
            .param("error", error)
            .param("pluginId", pluginId)
            .param("version", version)
            .update()
        == 1;
  }

  /** 租约在数据库时钟下已过期而未终结的刷新结果未知：收敛为 {@code REFRESH_UNCERTAIN}。 */
  public int markExpiredLeasesUncertain(List<String> pluginIds, String error) {
    return jdbc.sql(
            """
            update plugin_credential
            set status = 'REFRESH_UNCERTAIN', last_refresh_error = :error,
                refresh_lease_token = null, refresh_lease_until = null,
                version = version + 1, updated_at = current_timestamp
            where plugin_id in (:pluginIds)
              and refresh_lease_token is not null and refresh_lease_until <= statement_timestamp()
            """)
        .param("pluginIds", pluginIds)
        .param("error", error)
        .update();
  }

  /** 领取一条到期的交互凭据并签发刷新租约；到期与租约都以数据库时钟判定。 */
  public Optional<PluginCredentialRow> claimDue(
      List<String> pluginIds, Duration leaseDuration, String leaseToken) {
    return jdbc.sql(
            """
            with claimable as (
                select plugin_id from plugin_credential
                where plugin_id in (:pluginIds)
                  and status in ('CONNECTED', 'REFRESH_FAILED')
                  and next_refresh_at <= statement_timestamp()
                  and (refresh_lease_token is null or refresh_lease_until <= statement_timestamp())
                order by next_refresh_at, plugin_id
                limit 1
                for update skip locked
            )
            update plugin_credential credential
            set refresh_lease_token = :leaseToken,
                refresh_lease_until = date_trunc('milliseconds', statement_timestamp())
                    + :leaseMillis * interval '1 millisecond',
                version = credential.version + 1, updated_at = current_timestamp
            from claimable
            where credential.plugin_id = claimable.plugin_id
            returning credential.*
            """)
        .param("pluginIds", pluginIds)
        .param("leaseMillis", leaseDuration.toMillis())
        .param("leaseToken", leaseToken)
        .query(ROW_MAPPER)
        .optional();
  }

  public boolean finalizeSuccess(
      PluginCredentialRow claimed,
      String payloadJson,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt,
      Instant refreshedAt) {
    return jdbc.sql(
                """
                update plugin_credential
                set payload_json = :payloadJson, region = :region, expires_at = :expiresAt,
                    next_refresh_at = :nextRefreshAt, status = 'CONNECTED',
                    last_refreshed_at = :refreshedAt, last_refresh_error = null,
                    refresh_lease_token = null, refresh_lease_until = null,
                    version = version + 1, updated_at = current_timestamp
                where plugin_id = :pluginId and refresh_lease_token = :leaseToken
                  and version = :version
                """)
            .param("payloadJson", payloadJson)
            .param("region", region)
            .param("expiresAt", toOffsetDateTime(expiresAt))
            .param("nextRefreshAt", toOffsetDateTime(nextRefreshAt))
            .param("refreshedAt", toOffsetDateTime(refreshedAt))
            .param("pluginId", claimed.pluginId())
            .param("leaseToken", claimed.refreshLeaseToken())
            .param("version", claimed.version())
            .update()
        == 1;
  }

  public boolean finalizeFailure(
      PluginCredentialRow claimed,
      PluginCredentialStatus status,
      Instant nextRefreshAt,
      String error) {
    return jdbc.sql(
                """
                update plugin_credential
                set status = :status, next_refresh_at = :nextRefreshAt,
                    last_refresh_error = :error,
                    refresh_lease_token = null, refresh_lease_until = null,
                    version = version + 1, updated_at = current_timestamp
                where plugin_id = :pluginId and refresh_lease_token = :leaseToken
                  and version = :version
                """)
            .param("status", status.name())
            .param("nextRefreshAt", toOffsetDateTime(nextRefreshAt))
            .param("error", error)
            .param("pluginId", claimed.pluginId())
            .param("leaseToken", claimed.refreshLeaseToken())
            .param("version", claimed.version())
            .update()
        == 1;
  }

  public boolean releaseLease(PluginCredentialRow claimed) {
    return jdbc.sql(
                """
                update plugin_credential
                set refresh_lease_token = null, refresh_lease_until = null,
                    version = version + 1, updated_at = current_timestamp
                where plugin_id = :pluginId and refresh_lease_token = :leaseToken
                  and version = :version
                """)
            .param("pluginId", claimed.pluginId())
            .param("leaseToken", claimed.refreshLeaseToken())
            .param("version", claimed.version())
            .update()
        == 1;
  }

  /** 刷新器发出请求前确认租约仍属于自己且未过期。 */
  public boolean ownsUnexpiredLease(PluginCredentialRow claimed) {
    return jdbc.sql(
                """
                select count(*) from plugin_credential
                where plugin_id = :pluginId and refresh_lease_token = :leaseToken
                  and version = :version and refresh_lease_until > statement_timestamp()
                """)
            .param("pluginId", claimed.pluginId())
            .param("leaseToken", claimed.refreshLeaseToken())
            .param("version", claimed.version())
            .query(Long.class)
            .single()
        == 1L;
  }

  /** 未被刷新租约持有的最早下一次刷新时刻，用于调度下一次扫描。 */
  public Optional<Instant> earliestRefreshAt(List<String> pluginIds) {
    return jdbc
        .sql(
            """
            select min(next_refresh_at) from plugin_credential
            where plugin_id in (:pluginIds) and status in ('CONNECTED', 'REFRESH_FAILED')
              and (refresh_lease_token is null or refresh_lease_until <= statement_timestamp())
            """)
        .param("pluginIds", pluginIds)
        .query((rs, row) -> toInstant(rs.getObject(1, OffsetDateTime.class)))
        .list()
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }
}
