package fun.fengwk.kkstudio.platform.pluginhost.persistence;

import static fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes.toInstant;
import static fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes.toOffsetDateTime;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** 每个 Plugin 至多一个 attempt；写入均由调用方先在同一事务锁定配置行。 */
@Repository
public class PluginAuthAttemptRepository {

  private static final RowMapper<PluginAuthAttemptRow> ROW_MAPPER =
      (rs, row) ->
          new PluginAuthAttemptRow(
              rs.getString("plugin_id"),
              rs.getString("nonce_hash"),
              rs.getLong("config_version"),
              rs.getString("region"),
              toInstant(rs.getObject("expires_at", OffsetDateTime.class)));

  private final JdbcClient jdbc;

  public PluginAuthAttemptRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /** 写入新的 attempt，同一 Plugin 的旧 attempt 立即失效。 */
  public void replace(PluginAuthAttemptRow attempt) {
    jdbc.sql(
            """
            insert into plugin_auth_attempt (
                plugin_id, nonce_hash, config_version, region, expires_at)
            values (:pluginId, :nonceHash, :configVersion, :region, :expiresAt)
            on conflict (plugin_id) do update
            set nonce_hash = excluded.nonce_hash, config_version = excluded.config_version,
                region = excluded.region, expires_at = excluded.expires_at,
                created_at = current_timestamp
            """)
        .param("pluginId", attempt.pluginId())
        .param("nonceHash", attempt.nonceHash())
        .param("configVersion", attempt.configVersion())
        .param("region", attempt.region())
        .param("expiresAt", toOffsetDateTime(attempt.expiresAt()))
        .update();
  }

  /** 将命中的公开 nonce 摘要替换为只在服务内使用的认领摘要。 */
  public Optional<PluginAuthAttemptRow> claim(
      String pluginId, String nonceHash, String claimHash, long currentVersion, Instant now) {
    return jdbc.sql(
            """
            update plugin_auth_attempt set nonce_hash = :claimHash
            where plugin_id = :pluginId and nonce_hash = :nonceHash
              and config_version = :configVersion and expires_at > :now
            returning *
            """)
        .param("pluginId", pluginId)
        .param("nonceHash", nonceHash)
        .param("claimHash", claimHash)
        .param("configVersion", currentVersion)
        .param("now", toOffsetDateTime(now))
        .query(ROW_MAPPER)
        .optional();
  }

  /** 仅消费本次认领且仍未过期的行。 */
  public boolean consumeClaim(PluginAuthAttemptRow claim, Instant now) {
    return jdbc.sql(
                """
                delete from plugin_auth_attempt
                where plugin_id = :pluginId and nonce_hash = :claimHash
                  and config_version = :configVersion and expires_at > :now
                """)
            .param("pluginId", claim.pluginId())
            .param("claimHash", claim.nonceHash())
            .param("configVersion", claim.configVersion())
            .param("now", toOffsetDateTime(now))
            .update()
        == 1;
  }

  public void delete(String pluginId) {
    jdbc.sql("delete from plugin_auth_attempt where plugin_id = :pluginId")
        .param("pluginId", pluginId)
        .update();
  }
}
