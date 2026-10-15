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
import java.util.UUID;

/**
 * {@code plugin_credential} 的 JdbcClient 入口。
 *
 * <p>写入只有几种形态：条件插入（要求当前无行）、条件整行替换（匹配认证代际、上下文指纹与 version）、条件删除（同一组匹配）、条件终结（finalize / release）与过期
 * lease 收敛。没有无条件 upsert：不匹配的写入都是 0 行更新，调用方据此判定竞争失败而不是覆盖。领取与终结都靠 {@code for update skip locked} 与
 * CAS version 在多节点间互斥。
 *
 * <p>静态密钥行没有 region/expires_at/next_refresh_at，因此既不会被 refresh 领取（{@code next_refresh_at <= now} 对
 * null 为假），也没有本地过期语义。
 */
@Repository
public class PluginCredentialJdbcStore {

  private static final String COLUMNS =
      "plugin_id, auth_generation, context_fingerprint, encrypted_payload, region, expires_at, "
          + "next_refresh_at, status, last_refreshed_at, last_refresh_error, refresh_lease_token, "
          + "refresh_lease_until, version, create_time, update_time";

  private static final String INSERT_SQL =
      """
      insert into plugin_credential (
          plugin_id, auth_generation, context_fingerprint, encrypted_payload, region, expires_at,
          next_refresh_at, status, last_refreshed_at, last_refresh_error,
          refresh_lease_token, refresh_lease_until, version, create_time, update_time
      ) values (
          :pluginId, :authGeneration, :contextFingerprint, :encryptedPayload, :region, :expiresAt,
          :nextRefreshAt, :status, :lastRefreshedAt, :lastRefreshError,
          :refreshLeaseToken, :refreshLeaseUntil, :version, :createTime, :updateTime
      )
      on conflict (plugin_id) do nothing
      """;

  private static final String REPLACE_SQL =
      """
      update plugin_credential
      set auth_generation = :authGeneration,
          context_fingerprint = :contextFingerprint,
          encrypted_payload = :encryptedPayload,
          region = :region,
          expires_at = :expiresAt,
          next_refresh_at = :nextRefreshAt,
          status = :status,
          last_refreshed_at = :lastRefreshedAt,
          last_refresh_error = :lastRefreshError,
          refresh_lease_token = :refreshLeaseToken,
          refresh_lease_until = :refreshLeaseUntil,
          version = version + 1,
          update_time = :updateTime
      where plugin_id = :pluginId
        and auth_generation = :expectedAuthGeneration
        and context_fingerprint = :expectedContextFingerprint
        and version = :expectedVersion
      """;

  private static final String DELETE_SQL =
      """
      delete from plugin_credential
      where plugin_id = :pluginId
        and auth_generation = :expectedAuthGeneration
        and context_fingerprint = :expectedContextFingerprint
        and version = :expectedVersion
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
          credential.plugin_id, credential.auth_generation, credential.context_fingerprint,
          credential.encrypted_payload, credential.region, credential.expires_at,
          credential.next_refresh_at, credential.status, credential.last_refreshed_at,
          credential.last_refresh_error, credential.refresh_lease_token,
          credential.refresh_lease_until, credential.version, credential.create_time,
          credential.update_time
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

  public int insert(PluginCredentialDO row) {
    return bindRow(jdbcClient.sql(INSERT_SQL), row).update();
  }

  public int replace(
      PluginCredentialDO row,
      UUID expectedAuthGeneration,
      String expectedContextFingerprint,
      long expectedVersion) {
    return bindRow(jdbcClient.sql(REPLACE_SQL), row)
        .param("expectedAuthGeneration", expectedAuthGeneration)
        .param("expectedContextFingerprint", expectedContextFingerprint)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteByReference(
      String pluginId,
      UUID expectedAuthGeneration,
      String expectedContextFingerprint,
      long expectedVersion) {
    return jdbcClient
        .sql(DELETE_SQL)
        .param("pluginId", pluginId)
        .param("expectedAuthGeneration", expectedAuthGeneration)
        .param("expectedContextFingerprint", expectedContextFingerprint)
        .param("expectedVersion", expectedVersion)
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
      UUID authGeneration,
      String contextFingerprint,
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
              and auth_generation = :authGeneration
              and context_fingerprint = :contextFingerprint
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
        .param("authGeneration", authGeneration)
        .param("contextFingerprint", contextFingerprint)
        .param("leaseToken", leaseToken)
        .param("version", version)
        .update();
  }

  public int finalizeFailure(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
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
              and auth_generation = :authGeneration
              and context_fingerprint = :contextFingerprint
              and refresh_lease_token = :leaseToken
              and version = :version
            """)
        .param("status", status)
        .param("nextRefreshAt", PostgresJdbcTypes.toOffsetDateTime(nextRefreshAt))
        .param("error", error)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("pluginId", pluginId)
        .param("authGeneration", authGeneration)
        .param("contextFingerprint", contextFingerprint)
        .param("leaseToken", leaseToken)
        .param("version", version)
        .update();
  }

  public int releaseLease(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      Instant now) {
    return jdbcClient
        .sql(
            """
            update plugin_credential
            set refresh_lease_token = null,
                refresh_lease_until = null,
                version = version + 1,
                update_time = :now
            where plugin_id = :pluginId
              and auth_generation = :authGeneration
              and context_fingerprint = :contextFingerprint
              and refresh_lease_token = :leaseToken
              and version = :version
            """)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("pluginId", pluginId)
        .param("authGeneration", authGeneration)
        .param("contextFingerprint", contextFingerprint)
        .param("leaseToken", leaseToken)
        .param("version", version)
        .update();
  }

  public int countOwnedUnexpiredLease(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      Instant now) {
    return jdbcClient
        .sql(
            """
            select count(*)
            from plugin_credential
            where plugin_id = :pluginId
              and auth_generation = :authGeneration
              and context_fingerprint = :contextFingerprint
              and refresh_lease_token = :leaseToken
              and version = :version
              and refresh_lease_until > :now
            """)
        .param("pluginId", pluginId)
        .param("authGeneration", authGeneration)
        .param("contextFingerprint", contextFingerprint)
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
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      long version,
      byte[] encryptedPayload,
      String error,
      Instant now) {
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
              and auth_generation = :authGeneration
              and context_fingerprint = :contextFingerprint
              and (version = :version or encrypted_payload = :encryptedPayload)
            """)
        .param("error", error)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("pluginId", pluginId)
        .param("authGeneration", authGeneration)
        .param("contextFingerprint", contextFingerprint)
        .param("version", version)
        .param("encryptedPayload", encryptedPayload)
        .update();
  }

  private static JdbcClient.StatementSpec bindRow(
      JdbcClient.StatementSpec statement, PluginCredentialDO row) {
    return statement
        .param("pluginId", row.getPluginId())
        .param("authGeneration", row.getAuthGeneration())
        .param("contextFingerprint", row.getContextFingerprint())
        .param("encryptedPayload", row.getEncryptedPayload())
        .param("region", row.getRegion())
        .param("expiresAt", PostgresJdbcTypes.toOffsetDateTime(row.getExpiresAt()))
        .param("nextRefreshAt", PostgresJdbcTypes.toOffsetDateTime(row.getNextRefreshAt()))
        .param("status", row.getStatus())
        .param("lastRefreshedAt", PostgresJdbcTypes.toOffsetDateTime(row.getLastRefreshedAt()))
        .param("lastRefreshError", row.getLastRefreshError())
        .param("refreshLeaseToken", row.getRefreshLeaseToken())
        .param("refreshLeaseUntil", PostgresJdbcTypes.toOffsetDateTime(row.getRefreshLeaseUntil()))
        .param("version", row.getVersion())
        .param("createTime", PostgresJdbcTypes.toOffsetDateTime(row.getCreateTime()))
        .param("updateTime", PostgresJdbcTypes.toOffsetDateTime(row.getUpdateTime()));
  }

  private static PluginCredentialDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    PluginCredentialDO row = new PluginCredentialDO();
    row.setPluginId(rs.getString("plugin_id"));
    row.setAuthGeneration(rs.getObject("auth_generation", UUID.class));
    row.setContextFingerprint(rs.getString("context_fingerprint"));
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
