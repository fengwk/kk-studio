package fun.fengwk.kkstudio.platform.environment;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigCodec;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code environment} 的 JdbcClient 仓库：卡片读取、两种行锁、CAS 写与硬删除；安装码使用普通列并与配置写入共享同一行锁。
 *
 * <p>所有写方法都要求调用方已开启真实外层事务。稳定 Environment 的 create/update/delete 在写入成功后于同一事务连接发布 {@code
 * environment_changed}，通知失败会连同写入一起回滚。安装码签发不推进版本；兑换在同一语句中清空 code、轮换 token 并递增版本。
 * 显式保存配置或轮换 token 会撤销未兑换安装码，仅修改备注不会。
 */
@Repository
public class EnvironmentRepository {

  private static final String COLUMNS =
      "id, name, note, registration_token_hash, daemon_config::text as daemon_config_json, version, "
          + "created_at, updated_at";

  static final String UPDATE_BY_ID_SQL =
      """
      update environment
      set registration_token_hash = :registrationTokenHash,
          note = :note,
          daemon_config = cast(:daemonConfigJson as jsonb),
          install_code_hash = case
              when registration_token_hash is distinct from :registrationTokenHash
                  or daemon_config is distinct from cast(:daemonConfigJson as jsonb) then null
              else install_code_hash
          end,
          install_code_expires_at = case
              when registration_token_hash is distinct from :registrationTokenHash
                  or daemon_config is distinct from cast(:daemonConfigJson as jsonb) then null
              else install_code_expires_at
          end,
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = :id and version = :expectedVersion
      """;

  private static final String INSERT_SQL =
      """
      insert into environment (
          id, name, note, registration_token_hash, daemon_config, created_at, updated_at, version
      ) values (
          :id, :name, :note, :registrationTokenHash, cast(:daemonConfigJson as jsonb),
          current_timestamp, current_timestamp, 0
      )
      """;

  private static final String SAVE_INSTALL_CODE_SQL =
      """
      update environment
      set install_code_hash = :codeHash,
          install_code_expires_at = statement_timestamp() + interval '5 minutes'
      where id = :environmentId and version = :expectedVersion
      returning install_code_expires_at
      """;

  private static final String REDEEM_INSTALL_CODE_SQL =
      """
      update environment
      set install_code_hash = null,
          install_code_expires_at = null,
          registration_token_hash = :registrationTokenHash,
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = :environmentId
        and version = :expectedVersion
        and install_code_hash = :codeHash
        and install_code_expires_at > statement_timestamp()
      returning version
      """;

  private static final RowMapper<Environment> ROW_MAPPER = EnvironmentRepository::mapRow;

  private final JdbcClient jdbcClient;
  private final EnvironmentChangeNotifier notifier;

  public EnvironmentRepository(JdbcClient jdbcClient, EnvironmentChangeNotifier notifier) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    this.notifier = Objects.requireNonNull(notifier, "notifier");
  }

  public List<Environment> listNewestFirst() {
    return jdbcClient
        .sql(
            "select "
                + COLUMNS
                + " from environment order by updated_at desc, created_at desc, name asc")
        .query(ROW_MAPPER)
        .list();
  }

  /** 按 {@code name asc} 列出全部 Environment（含 registrationTokenHash），用于一次性导出快照。 */
  public List<Environment> listAll() {
    return jdbcClient
        .sql("select " + COLUMNS + " from environment order by name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public Environment getById(UUID id) {
    return findOne("select " + COLUMNS + " from environment where id = :id", "id", id);
  }

  public Environment getByName(String name) {
    return findOne("select " + COLUMNS + " from environment where name = :name", "name", name);
  }

  public Environment getByRegistrationTokenHash(String registrationTokenHash) {
    return findOne(
        "select "
            + COLUMNS
            + " from environment where registration_token_hash = :registrationTokenHash",
        "registrationTokenHash",
        registrationTokenHash);
  }

  public Environment lockById(UUID id) {
    return findOne("select " + COLUMNS + " from environment where id = :id for update", "id", id);
  }

  public Environment lockForKeyShare(UUID id) {
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

  public boolean create(Environment environment) {
    EnvironmentChangeNotifier.requireTransaction();
    boolean created =
        jdbcClient
                .sql(INSERT_SQL)
                .param("id", environment.getId())
                .param("name", environment.getName())
                .param("note", environment.getNote())
                .param("registrationTokenHash", environment.getRegistrationTokenHash())
                .param("daemonConfigJson", DaemonConfigCodec.write(environment.getDaemonConfig()))
                .update()
            == 1;
    if (created) {
      notifier.environmentChanged(environment.getId());
    }
    return created;
  }

  /** CAS 原子更新备注、完整配置与 registrationTokenHash；name 是不可变身份，绝不参与更新。 */
  public boolean updateById(Environment environment, long expectedVersion) {
    EnvironmentChangeNotifier.requireTransaction();
    boolean updated =
        jdbcClient
                .sql(UPDATE_BY_ID_SQL)
                .param("registrationTokenHash", environment.getRegistrationTokenHash())
                .param("note", environment.getNote())
                .param("daemonConfigJson", DaemonConfigCodec.write(environment.getDaemonConfig()))
                .param("id", environment.getId())
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (updated) {
      notifier.environmentChanged(environment.getId());
    }
    return updated;
  }

  public boolean deleteById(UUID id, long expectedVersion) {
    EnvironmentChangeNotifier.requireTransaction();
    boolean deleted =
        jdbcClient
                .sql("delete from environment where id = :id and version = :expectedVersion")
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (deleted) {
      // 连接行随 environment 级联删除，同一 environment 的通知已覆盖级联，不另造连接级来源。
      notifier.environmentChanged(id);
    }
    return deleted;
  }

  /**
   * 保存或覆盖 Environment 行上的临时安装 code；不修改配置，不递增 CAS 版本。
   *
   * <p>调用方必须在锁定同一 Environment 行的事务中执行，并通过 {@code expectedVersion} 证明签发基于当前配置版本。失效时间由数据库时间
   * 加上固定五分钟窗口生成，返回实际写入的过期时间。
   */
  public Optional<Instant> saveInstallCode(
      UUID environmentId, long expectedVersion, String codeHash) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(codeHash, "codeHash");
    EnvironmentChangeNotifier.requireTransaction();
    return jdbcClient
        .sql(SAVE_INSTALL_CODE_SQL)
        .param("environmentId", environmentId)
        .param("expectedVersion", expectedVersion)
        .param("codeHash", codeHash)
        .query(
            (rs, rowNum) ->
                PostgresJdbcTypes.toInstant(rs.getObject("install_code_expires_at", OffsetDateTime.class)))
        .optional();
  }

  /**
   * 原子兑换安装 code：在数据库时间下严格校验摘要与有效期后，清空 code、轮换注册凭证并递增版本。
   *
   * <p>调用方必须在锁定同一 Environment 行的事务中执行；摘要不匹配、已过期或版本不匹配时返回空且不写入任何字段。返回值是兑换后的新 CAS
   * 版本，脚本必须使用该版本而不是兑换前版本。
   */
  public Optional<Long> redeemInstallCode(
      UUID environmentId, long expectedVersion, String codeHash, String registrationTokenHash) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(codeHash, "codeHash");
    Objects.requireNonNull(registrationTokenHash, "registrationTokenHash");
    EnvironmentChangeNotifier.requireTransaction();
    Optional<Long> redeemed =
        jdbcClient
            .sql(REDEEM_INSTALL_CODE_SQL)
            .param("environmentId", environmentId)
            .param("expectedVersion", expectedVersion)
            .param("codeHash", codeHash)
            .param("registrationTokenHash", registrationTokenHash)
            .query(Long.class)
            .optional();
    redeemed.ifPresent(ignored -> notifier.environmentChanged(environmentId));
    return redeemed;
  }

  /** 是否存在引用该 Environment 的 durable work；删除与更新准入共用同一权威判定。 */
  public boolean hasActiveWork(UUID environmentId) {
    Long count =
        jdbcClient
            .sql("select count(1) from durable_work where required_environment_id = :environmentId")
            .param("environmentId", environmentId)
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  private Environment findOne(String sql, String paramName, Object paramValue) {
    return jdbcClient
        .sql(sql)
        .param(paramName, paramValue)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  private static Environment mapRow(ResultSet rs, int rowNum) throws SQLException {
    Environment environment = new Environment();
    environment.setId(rs.getObject("id", UUID.class));
    environment.setName(rs.getString("name"));
    environment.setNote(rs.getString("note"));
    environment.setRegistrationTokenHash(rs.getString("registration_token_hash"));
    environment.setDaemonConfig(DaemonConfigCodec.read(rs.getString("daemon_config_json")));
    environment.setVersion(rs.getLong("version"));
    environment.setCreatedAt(
        PostgresJdbcTypes.toInstant(rs.getObject("created_at", OffsetDateTime.class)));
    environment.setUpdatedAt(
        PostgresJdbcTypes.toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
    return environment;
  }
}
