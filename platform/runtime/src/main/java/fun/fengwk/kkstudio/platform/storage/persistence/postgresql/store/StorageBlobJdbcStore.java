package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.persistence.PostgresJdbcTypes;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model.StorageBlobDO;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code storage_blob} 的 JdbcClient 入口。
 *
 * <p>引用计数与状态切换全部使用条件 UPDATE（{@code state = 'ACTIVE'} 或 {@code ref_count} 边界），由数据库行锁串行化并发
 * retain/release，应用层不持有长事务；去重插入使用部分唯一索引的 {@code on conflict do nothing}。
 */
@Repository
public class StorageBlobJdbcStore {

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

  private static final RowMapper<StorageBlobDO> ROW_MAPPER = StorageBlobJdbcStore::mapRow;

  private final JdbcClient jdbcClient;

  public StorageBlobJdbcStore(JdbcClient jdbcClient) {
    this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
  }

  public StorageBlobDO getById(UUID id) {
    return jdbcClient
        .sql("select " + COLUMNS + " from storage_blob where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public StorageBlobDO getActiveByHashAndSize(String sha256, long sizeBytes) {
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

  public int insertActiveCandidate(StorageBlobDO blob) {
    return bindBlob(jdbcClient.sql(INSERT_ACTIVE_SQL), blob, true).update();
  }

  public int insertDeletingCandidate(StorageBlobDO blob) {
    return bindBlob(jdbcClient.sql(INSERT_DELETING_SQL), blob, false).update();
  }

  public int incrementRefCount(UUID id) {
    return jdbcClient
        .sql(
            """
            update storage_blob
            set ref_count = ref_count + 1, updated_at = current_timestamp
            where id = :id and state = 'ACTIVE'
            """)
        .param("id", id)
        .update();
  }

  public int releaseOnce(UUID id) {
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
        .update();
  }

  public int deleteDeleting(UUID id) {
    return jdbcClient
        .sql("delete from storage_blob where id = :id and state = 'DELETING' and ref_count = 0")
        .param("id", id)
        .update();
  }

  public List<UUID> listDeletingIds(int limit) {
    return jdbcClient
        .sql(
            "select id from storage_blob where state = 'DELETING' order by updated_at limit :limit")
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  private JdbcClient.StatementSpec bindBlob(
      JdbcClient.StatementSpec statement, StorageBlobDO blob, boolean withRefCount) {
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

  private static StorageBlobDO mapRow(ResultSet rs, int rowNum) throws SQLException {
    StorageBlobDO row = new StorageBlobDO();
    row.setId(rs.getObject("id", UUID.class));
    row.setSha256(rs.getString("sha256"));
    row.setSizeBytes(rs.getLong("size_bytes"));
    row.setMediaType(rs.getString("media_type"));
    row.setWidth(nullableLong(rs, "width"));
    row.setHeight(nullableLong(rs, "height"));
    row.setDurationMs(nullableLong(rs, "duration_ms"));
    row.setRefCount(rs.getLong("ref_count"));
    row.setState(rs.getString("state"));
    row.setCreateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("create_time", OffsetDateTime.class)));
    row.setUpdateTime(
        PostgresJdbcTypes.toInstant(rs.getObject("update_time", OffsetDateTime.class)));
    return row;
  }

  /** {@code width}/{@code height}/{@code duration_ms} 可空，必须保留 NULL 语义而不是退化为 0。 */
  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
