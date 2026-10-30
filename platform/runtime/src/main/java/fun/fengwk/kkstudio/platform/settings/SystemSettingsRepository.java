package fun.fengwk.kkstudio.platform.settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 基于 PostgreSQL 的单行 {@code system_setting}（{@code id=1}）仓库。
 *
 * <p>配置聚合与行版本一起读写；CAS 更新由数据库 {@code where id = 1 and version = :expectedVersion}
 * 原子保证。写路径要求调用方已开启事务： 在执行 SQL 前先校验事务，未开启事务在写行之前直接拒绝；成功更新与 {@code system_settings_changed} 通知共用同一事务
 * Connection，提交后唤醒其它节点回读权威 version，影响 0 行的陈旧 CAS 静默。
 */
@Repository
public class SystemSettingsRepository {

  /** 当前单行聚合的完整读取投影。 */
  public record SystemSettingsRecord(
      SystemSettings settings, long version, Instant createTime, Instant updateTime) {}

  private static final String COLUMNS =
      "config, version, created_at as create_time, updated_at as update_time";

  static final String UPDATE_BY_VERSION_SQL =
      """
      update system_setting
      set config = cast(:configJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = 1 and version = :expectedVersion
      """;

  private final JdbcClient jdbcClient;
  private final SystemSettingsCodec systemSettingsCodec;
  private final SystemSettingsChangeNotifier notifier;

  public SystemSettingsRepository(
      JdbcClient jdbcClient,
      SystemSettingsCodec systemSettingsCodec,
      SystemSettingsChangeNotifier notifier) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.systemSettingsCodec = Objects.requireNonNull(systemSettingsCodec, "systemSettingsCodec");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  /** 读取 id=1 行；行不存在（只可能由外部 DDL 造成）时返回 {@code null}。 */
  public SystemSettingsRecord get() {
    return jdbcClient
        .sql("select " + COLUMNS + " from system_setting where id = 1")
        .query(this::mapRow)
        .optional()
        .orElse(null);
  }

  /** 以 {@code expectedVersion} CAS 整体替换 config 并 {@code version + 1}；成功返回 true，否则返回 false。 */
  public boolean update(SystemSettings settings, long expectedVersion) {
    SystemSettingsChangeNotifier.requireTransaction();
    if (jdbcClient
            .sql(UPDATE_BY_VERSION_SQL)
            .param("configJson", systemSettingsCodec.encode(settings))
            .param("expectedVersion", expectedVersion)
            .update()
        != 1) {
      return false;
    }
    // WHERE version = expectedVersion 命中即 version = version + 1，payload 是写后权威 version。
    notifier.versionChanged(expectedVersion + 1);
    return true;
  }

  private SystemSettingsRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new SystemSettingsRecord(
        systemSettingsCodec.decode(rs.getString("config")),
        rs.getLong("version"),
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
  }
}
