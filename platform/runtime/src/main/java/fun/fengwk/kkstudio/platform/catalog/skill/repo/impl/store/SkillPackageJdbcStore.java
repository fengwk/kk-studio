package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;
import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Platform 全局 Skill Package 权威表（{@code skill_package}）的 JdbcClient 入口。
 *
 * <p>每个 {@code package_name} 恰一行：读路径不取锁，写路径先以 {@code for update} 锁行再按 {@code version} 做 CAS。
 */
@Repository
public class SkillPackageJdbcStore {

  private static final String COLUMNS =
      "package_name, description, repository_url, branch, current_commit,"
          + " observed_head_commit, head_checked_at, head_check_error, skills, token, version,"
          + " create_time, update_time";

  private static final String INSERT_SQL =
      """
      insert into skill_package (
          package_name, description, repository_url, branch, current_commit,
          observed_head_commit, head_checked_at, head_check_error, skills, token, version,
          create_time, update_time
      ) values (
          :packageName, :description, :repositoryUrl,
          :branch, :currentCommit, :observedHeadCommit,
          :headCheckedAt, :headCheckError,
          cast(:skillsJson as jsonb),
          :token, :version,
          current_timestamp, current_timestamp
      )
      """;

  private static final String UPDATE_PACKAGE_SQL =
      """
      update skill_package
      set description = :description,
          branch = :branch,
          current_commit = :currentCommit,
          observed_head_commit = :observedHeadCommit,
          head_checked_at = :headCheckedAt,
          head_check_error = :headCheckError,
          skills = cast(:skillsJson as jsonb),
          token = :token,
          version = version + 1,
          update_time = greatest(update_time, current_timestamp)
      where package_name = :packageName and version = :expectedVersion
      """;

  private static final RowMapper<SkillPackageDO> ROW_MAPPER = SkillPackageJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public SkillPackageJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public SkillPackageDO getPackage(String packageName) {
    return findOne(
        "select " + COLUMNS + " from skill_package where package_name = :packageName", packageName);
  }

  public SkillPackageDO lockPackageForShare(String packageName) {
    return findOne(
        "select " + COLUMNS + " from skill_package where package_name = :packageName for share",
        packageName);
  }

  public SkillPackageDO lockPackage(String packageName) {
    return findOne(
        "select " + COLUMNS + " from skill_package where package_name = :packageName for update",
        packageName);
  }

  public List<SkillPackageDO> listPackages() {
    return jdbcClient
        .sql("select " + COLUMNS + " from skill_package order by package_name asc")
        .query(ROW_MAPPER)
        .list();
  }

  public int insertPackage(SkillPackageDO skillPackage) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("packageName", skillPackage.getPackageName())
        .param("description", skillPackage.getDescription())
        .param("repositoryUrl", skillPackage.getRepositoryUrl())
        .param("branch", skillPackage.getBranch())
        .param("currentCommit", skillPackage.getCurrentCommit())
        .param("observedHeadCommit", skillPackage.getObservedHeadCommit())
        .param("headCheckedAt", PostgresJdbcTypes.toOffsetDateTime(skillPackage.getHeadCheckedAt()))
        .param("headCheckError", skillPackage.getHeadCheckError())
        .param("skillsJson", skillPackage.getSkillsJson())
        .param("token", skillPackage.getToken())
        .param("version", skillPackage.getVersion())
        .update();
  }

  public int updatePackage(SkillPackageDO skillPackage, long expectedVersion) {
    return jdbcClient
        .sql(UPDATE_PACKAGE_SQL)
        .param("description", skillPackage.getDescription())
        .param("branch", skillPackage.getBranch())
        .param("currentCommit", skillPackage.getCurrentCommit())
        .param("observedHeadCommit", skillPackage.getObservedHeadCommit())
        .param("headCheckedAt", PostgresJdbcTypes.toOffsetDateTime(skillPackage.getHeadCheckedAt()))
        .param("headCheckError", skillPackage.getHeadCheckError())
        .param("skillsJson", skillPackage.getSkillsJson())
        .param("token", skillPackage.getToken())
        .param("packageName", skillPackage.getPackageName())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deletePackage(String packageName, long expectedVersion) {
    return jdbcClient
        .sql(
            "delete from skill_package where package_name = :packageName and version = :expectedVersion")
        .param("packageName", packageName)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  private SkillPackageDO findOne(String sql, String packageName) {
    return jdbcClient
        .sql(sql)
        .param("packageName", packageName)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  private static SkillPackageDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    SkillPackageDO row = new SkillPackageDO();
    row.setPackageName(rs.getString("package_name"));
    row.setDescription(rs.getString("description"));
    row.setRepositoryUrl(rs.getString("repository_url"));
    row.setBranch(rs.getString("branch"));
    row.setCurrentCommit(rs.getString("current_commit"));
    row.setObservedHeadCommit(rs.getString("observed_head_commit"));
    row.setHeadCheckedAt(
        PostgresJdbcTypes.toInstant(rs.getObject("head_checked_at", OffsetDateTime.class)));
    row.setHeadCheckError(rs.getString("head_check_error"));
    row.setSkillsJson(rs.getString("skills"));
    row.setToken(rs.getString("token"));
    row.setVersion(rs.getLong("version"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }
}
