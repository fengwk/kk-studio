package fun.fengwk.kkstudio.platform.storage.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code session_file} 的 JdbcClient 仓库：Session 虚拟文件元数据与乐观锁更新。 */
@Repository
public class SessionFileRepository {

  private static final RowMapper<SessionFile> ROW_MAPPER = SessionFileRepository::mapRow;

  private final JdbcClient jdbcClient;

  public SessionFileRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  /** 查询 Session 内指定路径的文件元数据。 */
  public Optional<SessionFile> find(UUID sessionId, String path) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(path, "path must not be null");
    return jdbcClient
        .sql(
            """
            select session_id, path, blob_id, revision, updated_at
            from session_file
            where session_id = :sessionId and path = :path
            """)
        .param("sessionId", sessionId)
        .param("path", path)
        .query(ROW_MAPPER)
        .optional();
  }

  /** 列出 Session 内所有文件，按路径升序排列。 */
  public List<SessionFile> listBySession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return jdbcClient
        .sql(
            """
            select session_id, path, blob_id, revision, updated_at
            from session_file
            where session_id = :sessionId
            order by path asc
            """)
        .param("sessionId", sessionId)
        .query(ROW_MAPPER)
        .list();
  }

  /** 列出 Session 内指定路径前缀匹配的所有文件，按路径升序排列。 */
  public List<SessionFile> listByPrefix(UUID sessionId, String pathPrefix) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (pathPrefix == null || pathPrefix.isEmpty()) {
      return listBySession(sessionId);
    }
    return jdbcClient
        .sql(
            """
            select session_id, path, blob_id, revision, updated_at
            from session_file
            where session_id = :sessionId and starts_with(path, :pathPrefix)
            order by path asc
            """)
        .param("sessionId", sessionId)
        .param("pathPrefix", pathPrefix)
        .query(ROW_MAPPER)
        .list();
  }

  /** 插入新的文件元数据行；返回是否插入成功。 */
  public boolean insert(SessionFile file) {
    Objects.requireNonNull(file, "file must not be null");
    return jdbcClient
            .sql(
                """
                insert into session_file (session_id, path, blob_id, revision, updated_at)
                values (:sessionId, :path, :blobId, :revision, :updatedAt)
                """)
            .param("sessionId", file.sessionId())
            .param("path", file.path())
            .param("blobId", file.blobId())
            .param("revision", file.revision())
            .param("updatedAt", PostgresJdbcTypes.toOffsetDateTime(file.updatedAt()))
            .update()
        == 1;
  }

  /**
   * 乐观锁 CAS 更新：仅当当前版本等于 {@code expectedRevision} 时更新 blob_id 并将 revision + 1。
   *
   * @return 受影响的行数（1 表示更新成功，0 表示版本不匹配或文件不存在）
   */
  public int updateBlobCas(UUID sessionId, String path, UUID newBlobId, int expectedRevision) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(newBlobId, "newBlobId must not be null");
    return jdbcClient
        .sql(
            """
            update session_file
            set blob_id = :newBlobId,
                revision = revision + 1,
                updated_at = current_timestamp
            where session_id = :sessionId
              and path = :path
              and revision = :expectedRevision
            """)
        .param("sessionId", sessionId)
        .param("path", path)
        .param("newBlobId", newBlobId)
        .param("expectedRevision", expectedRevision)
        .update();
  }

  /** 删除单个文件元数据行；返回是否删除。 */
  public boolean delete(UUID sessionId, String path) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(path, "path must not be null");
    return jdbcClient
            .sql("delete from session_file where session_id = :sessionId and path = :path")
            .param("sessionId", sessionId)
            .param("path", path)
            .update()
        == 1;
  }

  /** 删除 Session 内全部文件，返回被删除的所有 blob ID。 */
  public List<UUID> deleteBySession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    return jdbcClient
        .sql(
            """
            delete from session_file
            where session_id = :sessionId
            returning blob_id
            """)
        .param("sessionId", sessionId)
        .query(UUID.class)
        .list();
  }

  /**
   * 获取按 Session 的事务级 advisory lock，串行化该 Session 的配额判断与写入。
   *
   * <p>锁键只由 Session ID 派生，落在独立命名空间，不触碰 Harness 引擎表；必须在读取配额与写入 {@code session_file} 之前、 在同一数据库事务内调用。
   */
  public void lockSessionQuota(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    jdbcClient
        .sql(
            "select true from pg_advisory_xact_lock("
                + "hashtextextended('session-file-quota:' || :sessionId, 0))")
        .param("sessionId", sessionId.toString())
        .query(Boolean.class)
        .single();
  }

  /** 获取指定 Session 内文件总数。 */
  public long countBySession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Long count =
        jdbcClient
            .sql("select count(*) from session_file where session_id = :sessionId")
            .param("sessionId", sessionId)
            .query(Long.class)
            .single();
    return count != null ? count : 0L;
  }

  /** 联合 storage_blob 统计 Session 内所有文件内容的总字节大小。 */
  public long sumSizeBySession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Long sum =
        jdbcClient
            .sql(
                """
                select coalesce(sum(b.size_bytes), 0)
                from session_file f
                join storage_blob b on b.id = f.blob_id
                where f.session_id = :sessionId
                """)
            .param("sessionId", sessionId)
            .query(Long.class)
            .single();
    return sum != null ? sum : 0L;
  }

  /** 检查 Session 内是否存在引用指定 blob ID 的文件。 */
  public boolean existsBlobReference(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(blobId, "blobId must not be null");
    return jdbcClient
        .sql(
            """
            select exists (
                select 1 from session_file
                where session_id = :sessionId and blob_id = :blobId
            )
            """)
        .param("sessionId", sessionId)
        .param("blobId", blobId)
        .query(Boolean.class)
        .single();
  }

  private static SessionFile mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new SessionFile(
        rs.getObject("session_id", UUID.class),
        rs.getString("path"),
        rs.getObject("blob_id", UUID.class),
        rs.getInt("revision"),
        PostgresJdbcTypes.toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
  }
}
