package fun.fengwk.kkstudio.platform.environment.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code environment} 的 JdbcClient 入口：读取、两种行锁、CAS 写与硬删除；token 变更在同一 CAS 语句中清空安装 code。 */
@Repository
public class EnvironmentJdbcStore {

  private static final String COLUMNS =
      "id, name, registration_token_hash, install_config::text as install_config_json, version, "
          + "created_at as create_time, updated_at as update_time";

  static final String UPDATE_BY_ID_SQL =
      """
      update environment
      set registration_token_hash = :registrationTokenHash,
          install_config = cast(:installConfigJson as jsonb),
          install_code = case
              when registration_token_hash is distinct from :registrationTokenHash then null
              else install_code
          end,
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = :id and version = :expectedVersion
      """;

  private static final String INSERT_SQL =
      """
      insert into environment (
          id, name, registration_token_hash, install_config, created_at, updated_at, version
      ) values (
          :id, :name, :registrationTokenHash, cast(:installConfigJson as jsonb), current_timestamp, current_timestamp, 0
      )
      """;

  private static final RowMapper<EnvironmentDO> ROW_MAPPER = EnvironmentJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public EnvironmentJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public List<EnvironmentDO> listNewestFirst() {
    return jdbcClient
        .sql(
            "select "
                + COLUMNS
                + " from environment order by updated_at desc, created_at desc, name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public List<EnvironmentDO> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from environment order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public EnvironmentDO getById(UUID id) {
    return findOne("select " + COLUMNS + " from environment where id = :id", "id", id);
  }

  public EnvironmentDO getByName(String name) {
    return findOne("select " + COLUMNS + " from environment where name = :name", "name", name);
  }

  public EnvironmentDO getByRegistrationTokenHash(String registrationTokenHash) {
    return findOne(
        "select "
            + COLUMNS
            + " from environment where registration_token_hash = :registrationTokenHash",
        "registrationTokenHash",
        registrationTokenHash);
  }

  public EnvironmentDO lockById(UUID id) {
    return findOne("select " + COLUMNS + " from environment where id = :id for update", "id", id);
  }

  public EnvironmentDO lockForKeyShare(UUID id) {
    return findOne(
        "select " + COLUMNS + " from environment where id = :id for key share", "id", id);
  }

  public boolean existsByName(String name) {
    return jdbcClient
        .sql("select count(1) > 0 from environment where name = :name")
        .param("name", name)
        .query(Boolean.class)
        .single();
  }

  public int insert(EnvironmentDO environment) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("id", environment.getId())
        .param("name", environment.getName())
        .param("registrationTokenHash", environment.getRegistrationTokenHash())
        .param("installConfigJson", environment.getInstallConfigJson())
        .update();
  }

  public int updateById(EnvironmentDO environment, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_BY_ID_SQL)
        .param("registrationTokenHash", environment.getRegistrationTokenHash())
        .param("installConfigJson", environment.getInstallConfigJson())
        .param("id", environment.getId())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteById(UUID id, long expectedVersion) {
    return jdbcClient
        .sql("delete from environment where id = :id and version = :expectedVersion")
        .param("id", id)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  private EnvironmentDO findOne(String sql, String paramName, Object paramValue) {
    return jdbcClient
        .sql(sql)
        .param(paramName, paramValue)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  private static EnvironmentDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    EnvironmentDO row = new EnvironmentDO();
    row.setId(rs.getObject("id", UUID.class));
    row.setName(rs.getString("name"));
    row.setRegistrationTokenHash(rs.getString("registration_token_hash"));
    row.setInstallConfigJson(rs.getString("install_config_json"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
