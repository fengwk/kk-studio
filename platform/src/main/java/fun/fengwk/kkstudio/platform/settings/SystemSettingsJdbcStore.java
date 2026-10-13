package fun.fengwk.kkstudio.platform.settings;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;

/** {@code system_setting} 的 JdbcClient 入口：唯一 id=1 行的读取与 version CAS 更新。 */
@Repository
public class SystemSettingsJdbcStore {

  private static final String COLUMNS =
      "id, config, version, created_at as create_time, updated_at as update_time";

  static final String UPDATE_BY_VERSION_SQL =
      """
      update system_setting
      set config = cast(:configJson as jsonb),
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = 1 and version = :expectedVersion
      """;

  private static final RowMapper<SystemSettingsDO> ROW_MAPPER = SystemSettingsJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public SystemSettingsJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public SystemSettingsDO get() {
    return jdbcClient
        .sql("select " + COLUMNS + " from system_setting where id = 1")
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public int updateByVersion(String configJson, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_BY_VERSION_SQL)
        .param("configJson", configJson)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  private static SystemSettingsDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    SystemSettingsDO row = new SystemSettingsDO();
    row.setId(rs.getLong("id"));
    row.setConfigJson(rs.getString("config"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
