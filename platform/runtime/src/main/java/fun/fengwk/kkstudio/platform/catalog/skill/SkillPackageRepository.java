package fun.fengwk.kkstudio.platform.catalog.skill;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Platform 全局 Skill Package 权威表（{@code skill_package}）的 JdbcClient 仓库。
 *
 * <p>读路径不取锁；写路径要求调用方已开启事务，并以 {@link #lockPackage(String)} 取行锁后按 {@code version} 做 CAS。未开启事务的写在执行任何
 * SQL 之前直接拒绝。成功写在同一事务 Connection 上发送 {@code skill_package_changed}（payload 是 package
 * 名，只用于唤醒环境侧做权威回读）： PostgreSQL 只在提交时投递，CAS 影响 0 行或回滚都静默。
 *
 * <p>{@code skills} jsonb 列只由应用严格验证：编码按 name 升序输出 {@code [{name,
 * description}]}，解码拒绝未知与缺失字段，被外部改写过的行无法伪装成合法 manifest。
 */
@Repository
public class SkillPackageRepository {

  private static final ObjectMapper MANIFEST_MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private static final TypeReference<List<SkillManifestEntry>> MANIFEST_TYPE =
      new TypeReference<>() {};

  private static final String COLUMNS =
      "package_name, description, repository_url, branch, current_commit,"
          + " observed_head_commit, head_checked_at, head_check_error, skills, token, version,"
          + " create_time, update_time";

  private static final RowMapper<SkillPackage> ROW_MAPPER =
      (rs, rowNum) ->
          new SkillPackage(
              rs.getString("package_name"),
              rs.getString("description"),
              rs.getString("repository_url"),
              rs.getString("branch"),
              rs.getString("current_commit"),
              rs.getString("observed_head_commit"),
              PostgresJdbcTypes.toInstant(rs.getObject("head_checked_at", OffsetDateTime.class)),
              rs.getString("head_check_error"),
              decodeManifest(rs.getString("skills")),
              rs.getString("token"),
              rs.getLong("version"),
              PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
              PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));

  private final JdbcClient jdbcClient;

  public SkillPackageRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 按 {@code package_name asc} 列出全部 Package。 */
  public List<SkillPackage> listPackages() {
    return jdbcClient
        .sql("select " + COLUMNS + " from skill_package order by package_name asc")
        .query(ROW_MAPPER)
        .list();
  }

  /** 读取某 Package；不存在返回 null。 */
  public SkillPackage getPackage(String packageName) {
    return findOne(
        "select " + COLUMNS + " from skill_package where package_name = :packageName", packageName);
  }

  /**
   * 以 {@code FOR SHARE} 读取某 Package；不存在返回 null。
   *
   * <p>共享锁与删除、发布等 {@code FOR UPDATE} 互斥，从而拦住 manifest 等非键列修改，同时允许并发的 Agent 引用事务共存。调用方必须已开启事务，并在任何
   * Agent 行锁之前按 package name 升序调用。
   */
  public SkillPackage lockPackageForShare(String packageName) {
    return findOne(
        "select " + COLUMNS + " from skill_package where package_name = :packageName for share",
        packageName);
  }

  /** 以 {@code FOR UPDATE} 读取某 Package；不存在返回 null。 */
  public SkillPackage lockPackage(String packageName) {
    return findOne(
        "select " + COLUMNS + " from skill_package where package_name = :packageName for update",
        packageName);
  }

  /** 插入一行 Package；未插入返回 false。 */
  public boolean insertPackage(SkillPackage skillPackage) {
    requireTransaction();
    int inserted =
        jdbcClient
            .sql(
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
                """)
            .param("packageName", skillPackage.packageName())
            .param("description", skillPackage.description())
            .param("repositoryUrl", skillPackage.repositoryUrl())
            .param("branch", skillPackage.branch())
            .param("currentCommit", skillPackage.currentCommit())
            .param("observedHeadCommit", skillPackage.observedHeadCommit())
            .param(
                "headCheckedAt", PostgresJdbcTypes.toOffsetDateTime(skillPackage.headCheckedAt()))
            .param("headCheckError", skillPackage.headCheckError())
            .param("skillsJson", encodeManifest(skillPackage.skills()))
            .param("token", skillPackage.token())
            .param("version", skillPackage.version())
            .update();
    if (inserted != 1) {
      return false;
    }
    notifyChanged(skillPackage.packageName());
    return true;
  }

  /** 以 {@code expectedVersion} 为条件整体更新 Package 的可变事实并推进 version；影响行数为 0 表示并发冲突。 */
  public boolean updatePackage(SkillPackage skillPackage, long expectedVersion) {
    requireTransaction();
    int updated =
        jdbcClient
            .sql(
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
                """)
            .param("description", skillPackage.description())
            .param("branch", skillPackage.branch())
            .param("currentCommit", skillPackage.currentCommit())
            .param("observedHeadCommit", skillPackage.observedHeadCommit())
            .param(
                "headCheckedAt", PostgresJdbcTypes.toOffsetDateTime(skillPackage.headCheckedAt()))
            .param("headCheckError", skillPackage.headCheckError())
            .param("skillsJson", encodeManifest(skillPackage.skills()))
            .param("token", skillPackage.token())
            .param("packageName", skillPackage.packageName())
            .param("expectedVersion", expectedVersion)
            .update();
    if (updated != 1) {
      return false;
    }
    // 命中即 version + 1，因此成功写的 version 必然真变化，与通知条件一致。
    notifyChanged(skillPackage.packageName());
    return true;
  }

  /** 以 {@code expectedVersion} 为条件删除 Package；影响行数为 0 表示不存在或并发冲突。 */
  public boolean deletePackage(String packageName, long expectedVersion) {
    requireTransaction();
    int deleted =
        jdbcClient
            .sql(
                "delete from skill_package where package_name = :packageName"
                    + " and version = :expectedVersion")
            .param("packageName", packageName)
            .param("expectedVersion", expectedVersion)
            .update();
    if (deleted != 1) {
      return false;
    }
    notifyChanged(packageName);
    return true;
  }

  private SkillPackage findOne(String sql, String packageName) {
    return jdbcClient
        .sql(sql)
        .param("packageName", packageName)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  private void notifyChanged(String packageName) {
    // pg_notify 返回 void：只需在当前事务 Connection 上执行，结果行无需读取。
    jdbcClient
        .sql("select pg_notify('skill_package_changed', :packageName)")
        .param("packageName", packageName)
        .query(rs -> {});
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Skill package change notification requires an active transaction");
    }
  }

  private static String encodeManifest(List<SkillManifestEntry> manifest) {
    try {
      return MANIFEST_MAPPER.writeValueAsString(Objects.requireNonNull(manifest, "skills"));
    } catch (JacksonException error) {
      throw new IllegalArgumentException("skill manifest cannot be serialized", error);
    }
  }

  private static List<SkillManifestEntry> decodeManifest(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      List<SkillManifestEntry> manifest = MANIFEST_MAPPER.readValue(json, MANIFEST_TYPE);
      if (manifest == null || manifest.stream().anyMatch(Objects::isNull)) {
        throw new IllegalStateException("stored skill manifest is invalid");
      }
      return List.copyOf(manifest);
    } catch (JacksonException error) {
      throw new IllegalStateException("stored skill manifest is invalid", error);
    }
  }
}
