package fun.fengwk.kkstudio.platform.storage.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code storage_upload} 的 JdbcClient 仓库：预约、complete 绑定与过期回收。
 *
 * <p>状态由 {@code blob_id} 表达：null = PENDING，非 null = READY；清理候选先被列出，再以 cleanup lease 精确 claim
 * 单行，最后把对象 I/O 移到事务外。过期没有持久化权威列，由调用方换算 {@code expiryCutoff}。
 */
@Repository
public class StorageUploadRepository {

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

  private static final RowMapper<StorageUpload> ROW_MAPPER = StorageUploadRepository::mapRow;

  private final JdbcClient jdbcClient;

  public StorageUploadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public boolean insert(StorageUpload upload) {
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
            .update()
        == 1;
  }

  public StorageUpload getById(UUID id) {
    return findOne("select " + COLUMNS + " from storage_upload where id = :id", id);
  }

  /** 行级锁定读取（{@code for update}），用于 complete 绑定与 READY 消费。 */
  public StorageUpload getByIdForUpdate(UUID id) {
    return findOne("select " + COLUMNS + " from storage_upload where id = :id for update", id);
  }

  /** CAS 标记显式清理请求；调用方必须在同一事务内按 READY 状态恰好 release 一次。 */
  public boolean markCleanupRequested(UUID id, Instant requestedAt) {
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
            .update()
        == 1;
  }

  /** 仅当仍未被清理请求/claim、且未过期（{@code created_at > expiryCutoff}）的 PENDING 时绑定 blob。 */
  public boolean setBlobIdIfNull(UUID id, UUID blobId, Instant expiryCutoff) {
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
            .update()
        == 1;
  }

  /**
   * 列出当下可清理的 upload id（显式 cleanup request 或已过期，且没有有效 cleanup lease）：只读快照，不写入、不加行锁； 调用方必须在持有该 upload
   * 操作锁后重新读取当下事实再 claim，绝不据旧快照处理。
   *
   * <p>{@code expiryCutoff} 是同一份 upload TTL 快照换算出的 {@code now - TTL}；{@code created_at <=
   * expiryCutoff} 即过期。
   */
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

  /** 按 id claim 指定清理事实；不能抢占有效 lease。 */
  public StorageUpload claimById(UUID id, Instant now, Instant leaseUntil, String cleanupToken) {
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

  /** token-fenced 删除 PENDING 清理事实。 */
  public boolean finalizePending(UUID id, String cleanupToken) {
    return jdbcClient
            .sql(
                """
                delete from storage_upload
                where id = :id and blob_id is null and cleanup_token = :cleanupToken
                """)
            .param("id", id)
            .param("cleanupToken", cleanupToken)
            .update()
        == 1;
  }

  /** token/blob-fenced 删除 READY 清理事实；成功后调用方在同一短事务 release blob。 */
  public boolean finalizeReady(UUID id, UUID blobId, String cleanupToken) {
    return jdbcClient
            .sql(
                """
                delete from storage_upload
                where id = :id and blob_id = :blobId and cleanup_token = :cleanupToken
                """)
            .param("id", id)
            .param("blobId", blobId)
            .param("cleanupToken", cleanupToken)
            .update()
        == 1;
  }

  /** token-fenced 主动释放 cleanup lease。 */
  public boolean releaseCleanupClaim(UUID id, String cleanupToken) {
    return jdbcClient
            .sql(
                """
                update storage_upload
                set cleanup_token = null, cleanup_until = null
                where id = :id and cleanup_token = :cleanupToken
                """)
            .param("id", id)
            .param("cleanupToken", cleanupToken)
            .update()
        == 1;
  }

  private StorageUpload findOne(String sql, UUID id) {
    return jdbcClient.sql(sql).param("id", id).query(ROW_MAPPER).optional().orElse(null);
  }

  private static StorageUpload mapRow(ResultSet rs, int rowNum) throws SQLException {
    StorageUpload upload = new StorageUpload();
    upload.setId(rs.getObject("id", UUID.class));
    upload.setCandidateBlobId(rs.getObject("candidate_blob_id", UUID.class));
    upload.setBlobId(rs.getObject("blob_id", UUID.class));
    upload.setFilename(rs.getString("filename"));
    upload.setDeclaredMediaType(rs.getString("declared_media_type"));
    upload.setDeclaredSize(rs.getLong("declared_size"));
    upload.setDeclaredSha256(rs.getString("declared_sha256"));
    upload.setCleanupRequestedAt(
        PostgresJdbcTypes.toInstant(rs.getObject("cleanup_requested_at", OffsetDateTime.class)));
    upload.setCleanupToken(rs.getString("cleanup_token"));
    upload.setCleanupUntil(
        PostgresJdbcTypes.toInstant(rs.getObject("cleanup_until", OffsetDateTime.class)));
    upload.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    return upload;
  }
}
