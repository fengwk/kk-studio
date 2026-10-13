package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model.StorageUploadDO;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code storage_upload} 的 JdbcClient 入口。
 *
 * <p>状态由 {@code blob_id} 表达：null = PENDING，非 null = READY；清理候选先被列出，再以 cleanup lease 精确 claim
 * 单行，最后把对象 I/O 移到事务外。过期没有持久化权威列，由调用方换算 {@code expiryCutoff}。
 */
@Repository
public class StorageUploadJdbcStore {

  private static final String COLUMNS =
      "id, candidate_blob_id, blob_id, filename, declared_media_type, declared_size, "
          + "declared_sha256, cleanup_requested_at, cleanup_token, cleanup_until, "
          + "created_at as create_time";

  private static final String INSERT_SQL =
      """
      insert into storage_upload (
          id, candidate_blob_id, blob_id, filename, declared_media_type,
          declared_size, declared_sha256, created_at
      ) values (
          :id, :candidateBlobId, :blobId, :filename, :declaredMediaType,
          :declaredSize, :declaredSha256, :createTime
      )
      """;

  private static final String CLAIM_BY_ID_SQL =
      """
      update storage_upload
      set cleanup_token = :cleanupToken, cleanup_until = :leaseUntil
      where id = :id
        and (cleanup_token is null or cleanup_until <= :now)
      returning
          id, candidate_blob_id, blob_id, filename, declared_media_type,
          declared_size, declared_sha256, cleanup_requested_at, cleanup_token, cleanup_until,
          created_at as create_time
      """;

  private static final RowMapper<StorageUploadDO> ROW_MAPPER = StorageUploadJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public StorageUploadJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public int insert(StorageUploadDO upload) {
    return jdbcClient
        .sql(INSERT_SQL)
        .param("id", upload.getId())
        .param("candidateBlobId", upload.getCandidateBlobId())
        .param("blobId", upload.getBlobId())
        .param("filename", upload.getFilename())
        .param("declaredMediaType", upload.getDeclaredMediaType())
        .param("declaredSize", upload.getDeclaredSize())
        .param("declaredSha256", upload.getDeclaredSha256())
        .param("createTime", PostgresJdbcTypes.toOffsetDateTime(upload.getCreateTime()))
        .update();
  }

  public StorageUploadDO getById(UUID id) {
    return findOne("select " + COLUMNS + " from storage_upload where id = :id", id);
  }

  public StorageUploadDO getByIdForUpdate(UUID id) {
    return findOne("select " + COLUMNS + " from storage_upload where id = :id for update", id);
  }

  public int markCleanupRequested(UUID id, Instant requestedAt) {
    return jdbcClient
        .sql(
            """
            update storage_upload
            set cleanup_requested_at = :requestedAt
            where id = :id
              and cleanup_requested_at is null
              and cleanup_token is null
            """)
        .param("id", id)
        .param("requestedAt", PostgresJdbcTypes.toOffsetDateTime(requestedAt))
        .update();
  }

  public int setBlobIdIfNull(UUID id, UUID blobId, Instant expiryCutoff) {
    return jdbcClient
        .sql(
            """
            update storage_upload
            set blob_id = :blobId
            where id = :id
              and blob_id is null
              and cleanup_requested_at is null
              and cleanup_token is null
              and created_at > :expiryCutoff
            """)
        .param("blobId", blobId)
        .param("id", id)
        .param("expiryCutoff", PostgresJdbcTypes.toOffsetDateTime(expiryCutoff))
        .update();
  }

  public List<UUID> listCleanupCandidateIds(int limit, Instant now, Instant expiryCutoff) {
    return jdbcClient
        .sql(
            """
            select id
            from storage_upload
            where (cleanup_requested_at is not null
                   or cleanup_token is not null
                   or created_at <= :expiryCutoff)
              and (cleanup_token is null or cleanup_until <= :now)
            order by coalesce(cleanup_requested_at, created_at), id
            limit :limit
            """)
        .param("limit", limit)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .param("expiryCutoff", PostgresJdbcTypes.toOffsetDateTime(expiryCutoff))
        .query(UUID.class)
        .list();
  }

  public StorageUploadDO claimById(UUID id, Instant now, Instant leaseUntil, String cleanupToken) {
    return jdbcClient
        .sql(CLAIM_BY_ID_SQL)
        .param("cleanupToken", cleanupToken)
        .param("leaseUntil", PostgresJdbcTypes.toOffsetDateTime(leaseUntil))
        .param("id", id)
        .param("now", PostgresJdbcTypes.toOffsetDateTime(now))
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public int finalizePending(UUID id, String cleanupToken) {
    return jdbcClient
        .sql(
            """
            delete from storage_upload
            where id = :id and blob_id is null and cleanup_token = :cleanupToken
            """)
        .param("id", id)
        .param("cleanupToken", cleanupToken)
        .update();
  }

  public int finalizeReady(UUID id, UUID blobId, String cleanupToken) {
    return jdbcClient
        .sql(
            """
            delete from storage_upload
            where id = :id and blob_id = :blobId and cleanup_token = :cleanupToken
            """)
        .param("id", id)
        .param("blobId", blobId)
        .param("cleanupToken", cleanupToken)
        .update();
  }

  public int releaseCleanupClaim(UUID id, String cleanupToken) {
    return jdbcClient
        .sql(
            """
            update storage_upload
            set cleanup_token = null, cleanup_until = null
            where id = :id and cleanup_token = :cleanupToken
            """)
        .param("id", id)
        .param("cleanupToken", cleanupToken)
        .update();
  }

  private StorageUploadDO findOne(String sql, UUID id) {
    return jdbcClient.sql(sql).param("id", id).query(ROW_MAPPER).optional().orElse(null);
  }

  private static StorageUploadDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    StorageUploadDO row = new StorageUploadDO();
    row.setId(rs.getObject("id", UUID.class));
    row.setCandidateBlobId(rs.getObject("candidate_blob_id", UUID.class));
    row.setBlobId(rs.getObject("blob_id", UUID.class));
    row.setFilename(rs.getString("filename"));
    row.setDeclaredMediaType(rs.getString("declared_media_type"));
    row.setDeclaredSize(rs.getLong("declared_size"));
    row.setDeclaredSha256(rs.getString("declared_sha256"));
    row.setCleanupRequestedAt(
        PostgresJdbcTypes.toInstant(rs.getObject("cleanup_requested_at", OffsetDateTime.class)));
    row.setCleanupToken(rs.getString("cleanup_token"));
    row.setCleanupUntil(
        PostgresJdbcTypes.toInstant(rs.getObject("cleanup_until", OffsetDateTime.class)));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    return row;
  }
}
