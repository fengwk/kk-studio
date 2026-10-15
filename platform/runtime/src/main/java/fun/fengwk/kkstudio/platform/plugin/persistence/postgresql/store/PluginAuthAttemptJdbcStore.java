package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginAuthAttemptDO;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code plugin_auth_attempt} 的 JdbcClient 入口。
 *
 * <p>写入只有一条 upsert：每个 Plugin 一个 pending attempt，新 prepare 直接替换旧行，因此不存在「同时两个有效 attempt」。消费只有一条条件
 * delete ... returning：必须摘要命中、{@code expires_at > now}、epoch 未前进且当前 manifest 坐标仍与 attempt 相等，所以并发
 * complete、重放、过期与 revoke/配置变更后的迟到回调都只能有至多一个成功者，其余得到 0 行。
 */
@Repository
public class PluginAuthAttemptJdbcStore {

  private static final String COLUMNS =
      "plugin_id, nonce_hash, auth_generation, context_fingerprint, config_revision, region, "
          + "expires_at";

  private static final String REPLACE_SQL =
      """
      insert into plugin_auth_attempt (
          plugin_id, nonce_hash, auth_generation, context_fingerprint, config_revision, region,
          expires_at
      ) values (
          :pluginId, :nonceHash, :authGeneration, :contextFingerprint, :configRevision, :region,
          :expiresAt
      )
      on conflict (plugin_id) do update
      set nonce_hash = excluded.nonce_hash,
          auth_generation = excluded.auth_generation,
          context_fingerprint = excluded.context_fingerprint,
          config_revision = excluded.config_revision,
          region = excluded.region,
          expires_at = excluded.expires_at,
          created_at = current_timestamp
      """;

  /**
   * 单条语句同时判定摘要、过期、全局 epoch 与当前 manifest 坐标：任一条不满足都不会删除任何行，因此不存在「先检查、再按 nonce 单独删除」的漂移窗口。manifest
   * 坐标来自权威 {@code plugin_catalog_state.manifest_json}，并与 attempt 行逐字段比较。
   */
  private static final String CONSUME_SQL =
      """
      delete from plugin_auth_attempt a
      using plugin_catalog_state s
      where a.plugin_id = :pluginId
        and a.nonce_hash = :nonceHash
        and a.expires_at > :now
        and s.id = 1
        and s.epoch = :expectedEpoch
        and (s.manifest_json -> 'plugins' -> :pluginId ->> 'configRevision')::bigint
            = a.config_revision
        and (s.manifest_json -> 'plugins' -> :pluginId ->> 'authGeneration')::uuid
            = a.auth_generation
        and (s.manifest_json -> 'plugins' -> :pluginId ->> 'contextFingerprint')
            = a.context_fingerprint
      returning
          a.plugin_id, a.nonce_hash, a.auth_generation, a.context_fingerprint, a.config_revision,
          a.region, a.expires_at
      """;

  private static final RowMapper<PluginAuthAttemptDO> ROW_MAPPER =
      PluginAuthAttemptJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public PluginAuthAttemptJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public PluginAuthAttemptDO getByPluginId(String pluginId) {
    return jdbcClient
        .sql("select " + COLUMNS + " from plugin_auth_attempt where plugin_id = :pluginId")
        .param("pluginId", pluginId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public int replace(PluginAuthAttemptDO row) {
    return jdbcClient
        .sql(REPLACE_SQL)
        .param("pluginId", row.getPluginId())
        .param("nonceHash", row.getNonceHash())
        .param("authGeneration", row.getAuthGeneration())
        .param("contextFingerprint", row.getContextFingerprint())
        .param("configRevision", row.getConfigRevision())
        .param("region", row.getRegion())
        .param("expiresAt", PostgresJdbcTypes.toOffsetDateTime(row.getExpiresAt()))
        .update();
  }

  public PluginAuthAttemptDO consume(
      String pluginId, String nonceHash, Instant now, long expectedEpoch) {
    return jdbcClient
        .sql(CONSUME_SQL)
        .param("pluginId", pluginId)
        .param("nonceHash", nonceHash)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("expectedEpoch", expectedEpoch)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  private static PluginAuthAttemptDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    PluginAuthAttemptDO row = new PluginAuthAttemptDO();
    row.setPluginId(rs.getString("plugin_id"));
    row.setNonceHash(rs.getString("nonce_hash"));
    row.setAuthGeneration(rs.getObject("auth_generation", UUID.class));
    row.setContextFingerprint(rs.getString("context_fingerprint"));
    row.setConfigRevision(rs.getLong("config_revision"));
    row.setRegion(rs.getString("region"));
    row.setExpiresAt(PostgresJdbcTypes.toInstant(rs.getObject("expires_at", OffsetDateTime.class)));
    return row;
  }
}
