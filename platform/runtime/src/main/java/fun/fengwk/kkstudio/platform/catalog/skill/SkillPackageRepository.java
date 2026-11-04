package fun.fengwk.kkstudio.platform.catalog.skill;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Platform 全局 Skill Package 权威表（{@code skill_package} 与 {@code skill_package_git_source}）的
 * JdbcClient 仓库。
 *
 * <p>读路径不取锁；写路径要求调用方已开启事务，并以 {@link #lockPackage(String)} 取行锁后按 {@code version} 做 CAS。未开启事务的写在执行任何
 * SQL 之前直接拒绝。成功写在同一事务 Connection 上发送 {@code skill_package_changed}（payload 是 package
 * 名，只用于唤醒环境侧做权威回读）： PostgreSQL 只在提交时投递，CAS 影响 0 行或回滚都静默。
 *
 * <p>{@code manifest} jsonb 列只由应用严格验证：编码为 {@code {"skills":[{name, description}]}} 且按 name
 * 升序，解码拒绝未知与缺失字段、被外部改写过的行无法伪装成合法清单。
 */
@Repository
public class SkillPackageRepository {

  private static final ObjectMapper MANIFEST_MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private static final TypeReference<ManifestDocument> MANIFEST_TYPE = new TypeReference<>() {};

  private static final String COLUMNS =
      "p.package_name, p.description, p.package_version, p.artifact_blob_id, p.manifest,"
          + " p.published_at, p.version, p.create_time, p.update_time, g.repository_url,"
          + " g.branch, g.token, g.head_commit, g.checked_at, g.check_error";

  private static final String FROM =
      " from skill_package p"
          + " left join skill_package_git_source g on g.package_name = p.package_name";

  private static final RowMapper<SkillPackage> ROW_MAPPER =
      (rs, rowNum) -> {
        String repositoryUrl = rs.getString("repository_url");
        SkillPackageGitSource gitSource =
            repositoryUrl == null
                ? null
                : new SkillPackageGitSource(
                    repositoryUrl,
                    rs.getString("branch"),
                    rs.getString("token"),
                    rs.getString("head_commit"),
                    PostgresJdbcTypes.toInstant(rs.getObject("checked_at", OffsetDateTime.class)),
                    rs.getString("check_error"));
        return new SkillPackage(
            rs.getString("package_name"),
            rs.getString("description"),
            rs.getString("package_version"),
            rs.getObject("artifact_blob_id", UUID.class),
            decodeManifest(rs.getString("manifest")),
            PostgresJdbcTypes.toInstant(rs.getObject("published_at", OffsetDateTime.class)),
            rs.getLong("version"),
            PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)),
            PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)),
            gitSource);
      };

  private final JdbcClient jdbcClient;

  public SkillPackageRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 按 {@code package_name asc} 列出全部 Package。 */
  public List<SkillPackage> listPackages() {
    return jdbcClient
        .sql("select " + COLUMNS + FROM + " order by p.package_name asc")
        .query(ROW_MAPPER)
        .list();
  }

  /** 读取某 Package；不存在返回 null。 */
  public SkillPackage getPackage(String packageName) {
    return findOne(
        "select " + COLUMNS + FROM + " where p.package_name = :packageName", packageName);
  }

  /**
   * 以 {@code FOR SHARE} 读取某 Package；不存在返回 null。
   *
   * <p>共享锁与删除、发布等 {@code FOR UPDATE} 互斥，从而拦住 manifest 等非键列修改，同时允许并发的 Agent 引用事务共存。调用方必须已开启事务，并在任何
   * Agent 行锁之前按 package name 升序调用。
   */
  public SkillPackage lockPackageForShare(String packageName) {
    return findOne(
        "select " + COLUMNS + FROM + " where p.package_name = :packageName for share of p",
        packageName);
  }

  /** 以 {@code FOR UPDATE} 读取某 Package；不存在返回 null。 */
  public SkillPackage lockPackage(String packageName) {
    return findOne(
        "select " + COLUMNS + FROM + " where p.package_name = :packageName for update of p",
        packageName);
  }

  /** 插入一行 Package（以及可选的 Git 仓库事实）；未插入返回 false。 */
  public boolean insertPackage(SkillPackage skillPackage) {
    requireTransaction();
    int inserted =
        jdbcClient
            .sql(
                """
                insert into skill_package (
                    package_name, description, package_version, artifact_blob_id, manifest,
                    published_at, version, create_time, update_time
                ) values (
                    :packageName, :description, :packageVersion, :artifactBlobId,
                    cast(:manifestJson as jsonb), :publishedAt, :version,
                    current_timestamp, current_timestamp
                )
                """)
            .param("packageName", skillPackage.packageName())
            .param("description", skillPackage.description())
            .param("packageVersion", skillPackage.packageVersion())
            .param("artifactBlobId", skillPackage.artifactBlobId())
            .param("manifestJson", encodeManifest(skillPackage.skills()))
            .param("publishedAt", PostgresJdbcTypes.toOffsetDateTime(skillPackage.publishedAt()))
            .param("version", skillPackage.version())
            .update();
    if (inserted != 1) {
      return false;
    }
    SkillPackageGitSource gitSource = skillPackage.gitSource();
    if (gitSource != null && !insertGitSource(skillPackage.packageName(), gitSource)) {
      throw new IllegalStateException("insert skill package git source failed");
    }
    notifyChanged(skillPackage.packageName());
    return true;
  }

  /**
   * 以 {@code expectedVersion} 为条件切换当前发布版本（版本号、归档、清单与发布时间）并推进 version。
   *
   * <p>影响行数为 0 表示不存在或并发冲突；归档引用切换与旧引用释放由调用方在同一事务内完成。
   */
  public boolean updatePublication(SkillPackage published, long expectedVersion) {
    requireTransaction();
    int updated =
        jdbcClient
            .sql(
                """
                update skill_package
                set package_version = :packageVersion,
                    artifact_blob_id = :artifactBlobId,
                    manifest = cast(:manifestJson as jsonb),
                    published_at = :publishedAt,
                    version = version + 1,
                    update_time = greatest(update_time, current_timestamp)
                where package_name = :packageName and version = :expectedVersion
                """)
            .param("packageVersion", published.packageVersion())
            .param("artifactBlobId", published.artifactBlobId())
            .param("manifestJson", encodeManifest(published.skills()))
            .param("publishedAt", PostgresJdbcTypes.toOffsetDateTime(published.publishedAt()))
            .param("packageName", published.packageName())
            .param("expectedVersion", expectedVersion)
            .update();
    if (updated != 1) {
      return false;
    }
    notifyChanged(published.packageName());
    return true;
  }

  /** 以 {@code expectedVersion} 为条件更新描述并推进 version；影响行数为 0 表示并发冲突。 */
  public boolean updateDescription(String packageName, String description, long expectedVersion) {
    requireTransaction();
    int updated =
        jdbcClient
            .sql(
                """
                update skill_package
                set description = :description,
                    version = version + 1,
                    update_time = greatest(update_time, current_timestamp)
                where package_name = :packageName and version = :expectedVersion
                """)
            .param("description", description)
            .param("packageName", packageName)
            .param("expectedVersion", expectedVersion)
            .update();
    if (updated != 1) {
      return false;
    }
    notifyChanged(packageName);
    return true;
  }

  /** 更新 Git 渠道的检查观察值；Package 版本不变，因此不推进乐观锁版本。 */
  public boolean updateGitObservation(
      String packageName, String headCommit, Instant checkedAt, String checkError) {
    requireTransaction();
    int updated =
        jdbcClient
            .sql(
                """
                update skill_package_git_source
                set head_commit = :headCommit,
                    checked_at = :checkedAt,
                    check_error = :checkError
                where package_name = :packageName
                """)
            .param("headCommit", headCommit)
            .param("checkedAt", PostgresJdbcTypes.toOffsetDateTime(checkedAt))
            .param("checkError", checkError)
            .param("packageName", packageName)
            .update();
    if (updated != 1) {
      return false;
    }
    notifyChanged(packageName);
    return true;
  }

  /** 更新 Git 渠道的可编辑设置（branch 与访问令牌）；repository URL 是不可变身份。 */
  public boolean updateGitSettings(String packageName, String branch, String token) {
    requireTransaction();
    int updated =
        jdbcClient
            .sql(
                """
                update skill_package_git_source
                set branch = :branch, token = :token
                where package_name = :packageName
                """)
            .param("branch", branch)
            .param("token", token)
            .param("packageName", packageName)
            .update();
    if (updated != 1) {
      return false;
    }
    notifyChanged(packageName);
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

  private boolean insertGitSource(String packageName, SkillPackageGitSource gitSource) {
    return jdbcClient
            .sql(
                """
                insert into skill_package_git_source (
                    package_name, repository_url, branch, token, head_commit, checked_at,
                    check_error
                ) values (
                    :packageName, :repositoryUrl, :branch, :token, :headCommit, :checkedAt,
                    :checkError
                )
                """)
            .param("packageName", packageName)
            .param("repositoryUrl", gitSource.repositoryUrl())
            .param("branch", gitSource.branch())
            .param("token", gitSource.token())
            .param("headCommit", gitSource.headCommit())
            .param("checkedAt", PostgresJdbcTypes.toOffsetDateTime(gitSource.checkedAt()))
            .param("checkError", gitSource.checkError())
            .update()
        == 1;
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
      return MANIFEST_MAPPER.writeValueAsString(
          new ManifestDocument(Objects.requireNonNull(manifest, "skills")));
    } catch (JacksonException error) {
      throw new IllegalArgumentException("skill manifest cannot be serialized", error);
    }
  }

  private static List<SkillManifestEntry> decodeManifest(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      ManifestDocument manifest = MANIFEST_MAPPER.readValue(json, MANIFEST_TYPE);
      if (manifest == null || manifest.skills() == null) {
        throw new IllegalStateException("stored skill manifest is invalid");
      }
      List<SkillManifestEntry> skills = manifest.skills();
      if (skills.stream().anyMatch(Objects::isNull)) {
        throw new IllegalStateException("stored skill manifest is invalid");
      }
      return List.copyOf(skills);
    } catch (JacksonException error) {
      throw new IllegalStateException("stored skill manifest is invalid", error);
    }
  }

  /** {@code manifest} 列的唯一合法形状。 */
  record ManifestDocument(List<SkillManifestEntry> skills) {}
}
