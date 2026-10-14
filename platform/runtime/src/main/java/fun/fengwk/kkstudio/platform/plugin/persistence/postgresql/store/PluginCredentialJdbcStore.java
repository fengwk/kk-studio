package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCredentialDO;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * {@code plugin_credential} 的 JdbcClient 入口。
 *
 * <p>写入只有三种形态：整行 upsert（重新登录）、条件终结（finalize / release，同时匹配 lease token 与 version）与过期 lease
 * 收敛。领取与终结都靠 {@code for update skip locked} 与 CAS version 在多节点间互斥；任何不匹配的写入都是 0
 * 行更新，调用方据此判定竞争失败而不是覆盖。
 */
@Repository
public class PluginCredentialJdbcStore {

  private static final String COLUMNS =
      "plugin_id, encrypted_payload, region, expires_at, next_refresh_at, status, "
          + "last_refreshed_at, last_refresh_error, refresh_lease_token, refresh_lease_until, "
          + "version, create_time, update_time";

  private static final String UPSERT_SQL =
      """
      insert into plugin_credential (
          plugin_id, encrypted_payload, region, expires_at, next_refresh_at,
          status, last_refreshed_at, last_refresh_error,
          refresh_lease_token, refresh_lease_until, version, create_time, update_time
      ) values (
          :pluginId, :encryptedPayload, :region, :expiresAt, :nextRefreshAt,
          :status, null, null,
          null, null, 0, :createTime, :updateTime
      )
      on conflict (plugin_id) do update set
          encrypted_payload = excluded.encrypted_payload,
          region = excluded.region,
          expires_at = excluded.expires_at,
          next_refresh_at = excluded.next_refresh_at,
          status = excluded.status,
          last_refreshed_at = null,
          last_refresh_error = null,
          refresh_lease_token = null,
          refresh_lease_until = null,
          version = plugin_credential.version + 1,
          update_time = excluded.update_time
      """;

  private static final String CLAIM_DUE_SQL =
      """
      with claimable as (
          select plugin_id
          from plugin_credential
          where plugin_id in (:pluginIds)
            and status in ('CONNECTED', 'REFRESH_FAILED')
            and next_refresh_at <= :now
            and (refresh_lease_token is null or refresh_lease_until <= :now)
          order by next_refresh_at, plugin_id
          limit :limit
          for update skip locked
      )
      update plugin_credential credential
      set refresh_lease_token = :leaseToken,
          refresh_lease_until = :leaseUntil,
          version = credential.version + 1,
          update_time = :now
      from claimable
      where credential.plugin_id = claimable.plugin_id
      returning
          credential.plugin_id, credential.encrypted_payload, credential.region,
          credential.expires_at, credential.next_refresh_at, credential.status,
          credential.last_refreshed_at, credential.last_refresh_error,
          credential.refresh_lease_token, credential.refresh_lease_until,
          credential.version, credential.create_time, credential.update_time
      """;

  private static final RowMapper<PluginCredentialDO> ROW_MAPPER = PluginCredentialJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public PluginCredentialJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public PluginCredentialDO getByPluginId(String pluginId) {
    return jdbcClient
        .sql("select " + COLUMNS + " from plugin_credential where plugin_id = :pluginId")
        .param("pluginId", pluginId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public int upsert(PluginCredentialDO row) {
    return jdbcClient
        .sql(UPSERT_SQL)
        .param("pluginId", row.getPluginId())
        .param("encryptedPayload", row.getEncryptedPayload())
        .param("region", row.getRegion())
        .param("expiresAt", PostgresJdbcTypes.toOffsetDateTime(row.getExpiresAt()))
        .param("nextRefreshAt", PostgresJdbcTypes.toOffsetDateTime(row.getNextRefreshAt()))
        .param("status", row.getStatus())
        .param("createTime", PostgresJdbcTypes.toOffsetDateTime(row.getCreateTime()))
        .param("updateTime", PostgresJdbcTypes.toOffsetDateTime(row.getUpdateTime()))
        .update();
  }

  public int deleteByPluginId(String pluginId) {
    return jdbcClient
        .sql("delete from plugin_credential where plugin_id = :pluginId")
        .param("pluginId", pluginId)
        .update();
  }

  public int markExpiredLeasesUncertain(List<String> pluginIds, Instant now, String error) {
    return jdbcClient
        .sql(
            """
            update plugin_credential
            set status = 'REFRESH_UNCERTAIN',
                last_refresh_error = :error,
                refresh_lease_token = null,
                refresh_lease_until = null,
                version = version + 1,
                update_time = :now
            where plugin_id in (:pluginIds)
              and refresh_lease_token is not null
              and refresh_lease_until <= :now
            """)
        .param("pluginIds", pluginIds)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("error", error)
        .update();
  }

  public List<PluginCredentialDO> claimDue(
      List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit) {
    return jdbcClient
        .sql(CLAIM_DUE_SQL)
        .param("pluginIds", pluginIds)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("leaseUntil", PostgresJdbcTypes.toOffsetDateTime(leaseUntil))
        .param("leaseToken", leaseToken)
        .param("limit", limit)
        .query(ROW_MAPPER)
        .list();
  }

  public int finalizeSuccess(
      String pluginId,
      String leaseToken,
      long version,
      byte[] encryptedPayload,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt,
      Instant refreshedAt,
      Instant now) {
    return jdbcClient
        .sql(
            """
            update plugin_credential
            set encrypted_payload = :encryptedPayload,
                region = :region,
                expires_at = :expiresAt,
                next_refresh_at = :nextRefreshAt,
                status = 'CONNECTED',
                last_refreshed_at = :refreshedAt,
                last_refresh_error = null,
                refresh_lease_token = null,
                refresh_lease_until = null,
                version = version + 1,
                update_time = :now
            where plugin_id = :pluginId
              and refresh_lease_token = :leaseToken
              and version = :version
            """)
        .param("encryptedPayload", encryptedPayload)
        .param("region", region)
        .param("expiresAt", PostgresJdbcTypes.toOffsetDateTime(expiresAt))
        .param("nextRefreshAt", PostgresJdbcTypes.toOffsetDateTime(nextRefreshAt))
        .param("refreshedAt", PostgresJdbcTypes.toOffsetDateTime(refreshedAt))
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("pluginId", pluginId)
        .param("leaseToken", leaseToken)
        .param("version", version)
        .update();
  }

  public int finalizeFailure(
      String pluginId,
      String leaseToken,
      long version,
      String status,
      Instant nextRefreshAt,
      String error,
      Instant now) {
    return jdbcClient
        .sql(
            """
            update plugin_credential
            set status = :status,
                next_refresh_at = :nextRefreshAt,
                last_refresh_error = :error,
                refresh_lease_token = null,
                refresh_lease_until = null,
                version = version + 1,
                update_time = :now
            where plugin_id = :pluginId
              and refresh_lease_token = :leaseToken
              and version = :version
            """)
        .param("status", status)
        .param("nextRefreshAt", PostgresJdbcTypes.toOffsetDateTime(nextRefreshAt))
        .param("error", error)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("pluginId", pluginId)
        .param("leaseToken", leaseToken)
        .param("version", version)
        .update();
  }

  public int releaseLease(String pluginId, String leaseToken, long version, Instant now) {
    return jdbcClient
        .sql(
            """
            update plugin_credential
            set refresh_lease_token = null,
                refresh_lease_until = null,
                version = version + 1,
                update_time = :now
            where plugin_id = :pluginId
              and refresh_lease_token = :leaseToken
              and version = :version
            """)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("pluginId", pluginId)
        .param("leaseToken", leaseToken)
        .param("version", version)
        .update();
  }

  public int countOwnedUnexpiredLease(
      String pluginId, String leaseToken, long version, Instant now) {
    return jdbcClient
        .sql(
            """
            select count(*)
            from plugin_credential
            where plugin_id = :pluginId
              and refresh_lease_token = :leaseToken
              and version = :version
              and refresh_lease_until > :now
            """)
        .param("pluginId", pluginId)
        .param("leaseToken", leaseToken)
        .param("version", version)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .query(Integer.class)
        .single();
  }

  public Instant earliestRefreshAt(List<String> pluginIds, Instant now) {
    return jdbcClient
        .sql(
            """
            select min(next_refresh_at)
            from plugin_credential
            where plugin_id in (:pluginIds)
              and status in ('CONNECTED', 'REFRESH_FAILED')
              and (refresh_lease_token is null or refresh_lease_until <= :now)
            """)
        .param("pluginIds", pluginIds)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .query((rs, rowNum) -> PostgresJdbcTypes.toInstant(rs.getObject(1, OffsetDateTime.class)))
        .optional()
        .orElse(null);
  }

  public int rejectUsedCredential(
      String pluginId, long version, byte[] encryptedPayload, String error, Instant now) {
    return jdbcClient
        .sql(
            """
            update plugin_credential
            set status = 'REAUTH_REQUIRED',
                last_refresh_error = :error,
                refresh_lease_token = null,
                refresh_lease_until = null,
                version = version + 1,
                update_time = :now
            where plugin_id = :pluginId
              and (version = :version or encrypted_payload = :encryptedPayload)
            """)
        .param("error", error)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("pluginId", pluginId)
        .param("version", version)
        .param("encryptedPayload", encryptedPayload)
        .update();
  }

  private static PluginCredentialDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    PluginCredentialDO row = new PluginCredentialDO();
    row.setPluginId(rs.getString("plugin_id"));
    row.setEncryptedPayload(rs.getBytes("encrypted_payload"));
    row.setRegion(rs.getString("region"));
    row.setExpiresAt(toInstant(rs, "expires_at"));
    row.setNextRefreshAt(toInstant(rs, "next_refresh_at"));
    row.setStatus(rs.getString("status"));
    row.setLastRefreshedAt(toInstant(rs, "last_refreshed_at"));
    row.setLastRefreshError(rs.getString("last_refresh_error"));
    row.setRefreshLeaseToken(rs.getString("refresh_lease_token"));
    row.setRefreshLeaseUntil(toInstant(rs, "refresh_lease_until"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(toInstant(rs, "create_time"));
    row.setUpdateTime(toInstant(rs, "update_time"));
    return row;
  }

  private static Instant toInstant(ResultSet rs, String column) throws SQLException {
    return PostgresJdbcTypes.toInstant(rs.getObject(column, OffsetDateTime.class));
  }
}
