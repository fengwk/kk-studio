package fun.fengwk.kkstudio.platform.plugin.persistence;

import static fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes.toInstant;
import static fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes.toOffsetDateTime;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/** {@code plugin_auth_attempt} 存取：每个 Plugin 至多一个待完成 attempt，complete 以单条条件删除原子消费。 */
@Repository
public class PluginAuthAttemptRepository {

  private static final RowMapper<PluginAuthAttemptRow> ROW_MAPPER =
      (rs, row) ->
          new PluginAuthAttemptRow(
              rs.getString("plugin_id"),
              rs.getString("nonce_hash"),
              rs.getLong("config_revision"),
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
                plugin_id, nonce_hash, config_revision, region, expires_at)
            values (:pluginId, :nonceHash, :configRevision, :region, :expiresAt)
            on conflict (plugin_id) do update
            set nonce_hash = excluded.nonce_hash, config_revision = excluded.config_revision,
                region = excluded.region, expires_at = excluded.expires_at,
                created_at = current_timestamp
            """)
        .param("pluginId", attempt.pluginId())
        .param("nonceHash", attempt.nonceHash())
        .param("configRevision", attempt.configRevision())
        .param("region", attempt.region())
        .param("expiresAt", toOffsetDateTime(attempt.expiresAt()))
        .update();
  }

  /**
   * 原子消费：摘要命中、未过期且 prepare 时的配置修订仍是当前修订才删除并返回；否则返回空。
   *
   * <p>修订条件让配置在 prepare 之后发生的任何变化都使迟到回调失效。
   */
  public Optional<PluginAuthAttemptRow> consume(String pluginId, String nonceHash, Instant now) {
    return jdbc.sql(
            """
            delete from plugin_auth_attempt attempt
            using plugin_configuration configuration
            where attempt.plugin_id = :pluginId and attempt.nonce_hash = :nonceHash
              and attempt.expires_at > :now
              and configuration.plugin_id = attempt.plugin_id
              and configuration.revision = attempt.config_revision
            returning attempt.*
            """)
        .param("pluginId", pluginId)
        .param("nonceHash", nonceHash)
        .param("now", toOffsetDateTime(now))
        .query(ROW_MAPPER)
        .optional();
  }
}
