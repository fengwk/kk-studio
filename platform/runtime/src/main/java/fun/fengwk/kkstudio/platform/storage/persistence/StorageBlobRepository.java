package fun.fengwk.kkstudio.platform.storage.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code storage_blob} 的 JdbcClient 仓库：去重、引用计数与两阶段删除的原子操作。
 *
 * <p>引用计数与状态切换全部使用条件 UPDATE（{@code state = 'ACTIVE'} 或 {@code ref_count} 边界），由数据库行锁串行化并发
 * retain/release，应用层不持有长事务；去重插入使用部分唯一索引的 {@code on conflict do nothing}。
 */
@Repository
public class StorageBlobRepository {

  private static final String COLUMNS =
      "id, sha256, size_bytes, media_type, width, height, duration_ms, ref_count, state, "
          + "created_at as create_time, updated_at as update_time";

  private static final String INSERT_ACTIVE_SQL =
      """
      insert into storage_blob (
          id, sha256, size_bytes, media_type, width, height, duration_ms,
          ref_count, state, created_at, updated_at
      ) values (
          :id, :sha256, :sizeBytes, :mediaType, :width, :height, :durationMs,
          :refCount, 'ACTIVE', current_timestamp, current_timestamp
      )
      on conflict (sha256, size_bytes) where state = 'ACTIVE' do nothing
      """;

  private static final String INSERT_DELETING_SQL =
      """
      insert into storage_blob (
          id, sha256, size_bytes, media_type, width, height, duration_ms,
          ref_count, state, created_at, updated_at
      ) values (
          :id, :sha256, :sizeBytes, :mediaType, :width, :height, :durationMs,
          0, 'DELETING', current_timestamp, current_timestamp
      )
      on conflict (id) do nothing
      """;

  private static final RowMapper<StorageBlob> ROW_MAPPER = StorageBlobRepository::mapRow;

  private final JdbcClient jdbcClient;

  public StorageBlobRepository(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public StorageBlob getById(UUID id) {
    return jdbcClient
        .sql("select " + COLUMNS + " from storage_blob where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public StorageBlob getActiveByHashAndSize(String sha256, long sizeBytes) {
    return jdbcClient
        .sql(
            "select "
                + COLUMNS
                + " from storage_blob"
                + " where sha256 = :sha256 and size_bytes = :sizeBytes and state = 'ACTIVE'")
        .param("sha256", sha256)
        .param("sizeBytes", sizeBytes)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /**
   * 以完整事实插入 ACTIVE 候选行（{@code ref_count} 由调用方给出，complete 时调用）。 与 ACTIVE 行同内容的并发插入由部分唯一索引消解， 本方法返回
   * false 表示插入被消解，调用方应改读现有 ACTIVE 行并自行 retain。
   */
  public boolean insertActiveCandidate(StorageBlob blob) {
    return bindBlob(jdbcClient.sql(INSERT_ACTIVE_SQL), blob, true).update() == 1;
  }

  /** 为事务失败/去重落败后已存在的候选对象记录 DELETING 清理事实。 */
  public boolean insertDeletingCandidate(StorageBlob blob) {
    return bindBlob(jdbcClient.sql(INSERT_DELETING_SQL), blob, false).update() == 1;
  }

  /** 原子 retain：仅当行仍为 ACTIVE 时 {@code ref_count + 1}，否则返回 false。 */
  public boolean incrementRefCount(UUID id) {
    return jdbcClient
            .sql(
                """
                update storage_blob
                set ref_count = ref_count + 1, updated_at = current_timestamp
                where id = :id and state = 'ACTIVE'
                """)
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * 原子 release：仅当 ACTIVE 且 {@code ref_count > 0} 时减一；减到 0 的同一语句内切换到 DELETING
   * （状态与引用计数组合不变式在任何语句边界都成立），返回是否发生释放。
   */
  public boolean releaseOnce(UUID id) {
    return jdbcClient
            .sql(
                """
                update storage_blob
                set ref_count = ref_count - 1,
                    state = case when ref_count = 1 then 'DELETING' else state end,
                    updated_at = current_timestamp
                where id = :id and state = 'ACTIVE' and ref_count > 0
                """)
            .param("id", id)
            .update()
        == 1;
  }

  /** 条件删除 DELETING 行：仅在 S3 对象已删除后调用（{@code ref_count = 0} 兜底）。 */
  public boolean deleteDeleting(UUID id) {
    return jdbcClient
            .sql("delete from storage_blob where id = :id and state = 'DELETING' and ref_count = 0")
            .param("id", id)
            .update()
        == 1;
  }

  /** 启动恢复用：列出待回收的 DELETING 行 id（上限 {@code limit}）。 */
  public List<UUID> listDeletingIds(int limit) {
    return jdbcClient
        .sql(
            "select id from storage_blob where state = 'DELETING' order by updated_at limit :limit")
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  private JdbcClient.StatementSpec bindBlob(
      JdbcClient.StatementSpec statement, StorageBlob blob, boolean withRefCount) {
    statement =
        statement
            .param("id", blob.getId())
            .param("sha256", blob.getSha256())
            .param("sizeBytes", blob.getSizeBytes())
            .param("mediaType", blob.getMediaType())
            .param("width", blob.getWidth())
            .param("height", blob.getHeight())
            .param("durationMs", blob.getDurationMs());
    if (withRefCount) {
      statement = statement.param("refCount", blob.getRefCount());
    }
    return statement;
  }

  private static StorageBlob mapRow(ResultSet rs, int rowNum) throws SQLException {
    StorageBlob blob = new StorageBlob();
    blob.setId(rs.getObject("id", UUID.class));
    blob.setSha256(rs.getString("sha256"));
    blob.setSizeBytes(rs.getLong("size_bytes"));
    blob.setMediaType(rs.getString("media_type"));
    blob.setWidth(nullableLong(rs, "width"));
    blob.setHeight(nullableLong(rs, "height"));
    blob.setDurationMs(nullableLong(rs, "duration_ms"));
    blob.setRefCount(rs.getLong("ref_count"));
    blob.setState(StorageBlobState.valueOf(rs.getString("state")));
    blob.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    blob.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return blob;
  }

  /** {@code width}/{@code height}/{@code duration_ms} 可空，必须保留 NULL 语义而不是退化为 0。 */
  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
