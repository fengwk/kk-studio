package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.applyBaseline;
import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * {@code storage_blob}/{@code storage_upload} 的 schema 约束与注释完整性。
 *
 * @author fengwk
 */
class PostgresqlStorageSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  /** blob 事实列必须同时满足格式、非负与成对约束，状态与引用计数必须满足组合不变式。 */
  @Test
  void storageBlobRejectsInvalidFacts() throws SQLException {
    String sha256 = "a".repeat(64);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_sha256",
          () -> insertBlob(conn, UUID.randomUUID(), "xyz", 1L, "image/png", 1L, "ACTIVE"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_size_nonneg",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, -1L, "image/png", 1L, "ACTIVE"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_media_type_nonblank",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, 1L, "  ", 1L, "ACTIVE"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_dimensions_pair",
          () ->
              insertBlob(
                  conn, UUID.randomUUID(), sha256, 1L, "image/png", 1L, "ACTIVE", 640, null));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_dimensions_positive",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", 1L, "ACTIVE", 0, 0));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_duration_positive",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", 1L, "ACTIVE", 0L));
    }
  }

  /** 状态与引用计数必须满足恰好 (ACTIVE ∧ ref_count>0) ∨ (DELETING ∧ ref_count=0)。 */
  @Test
  void storageBlobStateAndRefCountMustMatchInvariant() throws SQLException {
    String sha256 = "a".repeat(64);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_state_ref_count",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", -1L, "ACTIVE"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_state_ref_count",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", 0L, "ACTIVE"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_state_ref_count",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", 1L, "DELETING"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_blob_state_ref_count",
          () -> insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", 0L, "PENDING"));
      // 合法组合必须可插入。
      insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", 1L, "ACTIVE");
      insertBlob(conn, UUID.randomUUID(), sha256, 1L, "image/png", 0L, "DELETING");
    }
  }

  /** 部分唯一索引：同一 (sha256, size_bytes) 只允许一个 ACTIVE 行，DELETING 行不参与去重。 */
  @Test
  void storageBlobDedupAppliesOnlyToActiveRows() throws SQLException {
    String sha256 = "b".repeat(64);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID deleting = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      insertBlob(conn, first, sha256, 10L, "image/png", 1L, "ACTIVE");
      assertTransactionConstraintViolation(
          conn,
          "uk_storage_blob_active_hash",
          () -> insertBlob(conn, second, sha256, 10L, "image/png", 1L, "ACTIVE"));
      // 同一内容的 DELETING 行允许存在：释放后同内容可立即重新上传。
      insertBlob(conn, deleting, sha256, 10L, "image/png", 0L, "DELETING");
      assertEquals(
          2L, queryLong(conn, "select count(*) from storage_blob where sha256 = '" + sha256 + "'"));
    }
  }

  /** 上传行的声明字段与过期约束必须被数据库拒绝。 */
  @Test
  void storageUploadRejectsInvalidDeclarations() throws SQLException {
    String sha256 = "c".repeat(64);
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_upload_filename_nonblank",
          () -> insertUpload(conn, UUID.randomUUID(), "  ", "image/png", 1L, sha256));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_upload_media_type_nonblank",
          () -> insertUpload(conn, UUID.randomUUID(), "a.png", "", 1L, sha256));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_upload_size_nonneg",
          () -> insertUpload(conn, UUID.randomUUID(), "a.png", "image/png", -1L, sha256));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_upload_sha256",
          () -> insertUpload(conn, UUID.randomUUID(), "a.png", "image/png", 1L, "not-a-hash"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_upload_expiry",
          () -> insertUpload(conn, UUID.randomUUID(), "a.png", "image/png", 1L, sha256, true));
      insertUpload(conn, UUID.randomUUID(), "lease.png", "image/png", 1L, sha256);
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_upload_cleanup_pair",
          () ->
              execute(
                  conn,
                  "update storage_upload set cleanup_token = 'token'"
                      + " where filename = 'lease.png'"));
      assertTransactionConstraintViolation(
          conn,
          "ck_storage_upload_cleanup_token",
          () ->
              execute(
                  conn,
                  "update storage_upload set cleanup_token = ' bad ',"
                      + " cleanup_until = current_timestamp + interval '1 minute'"
                      + " where filename = 'lease.png'"));
    }
  }

  /** 计数 FK 必须为 RESTRICT：被 READY 上传引用的 blob 不可删除，且不会级联删除上传行。 */
  @Test
  void storageUploadBlobForeignKeyRestrictsNotCascades() throws SQLException {
    String sha256 = "d".repeat(64);
    UUID blobId = UUID.randomUUID();
    UUID uploadId = UUID.randomUUID();
    try (Connection conn = newConnection()) {
      insertBlob(conn, blobId, sha256, 5L, "image/png", 1L, "ACTIVE");
      insertUpload(conn, uploadId, "a.png", "image/png", 5L, sha256, blobId);

      assertTransactionConstraintViolation(
          conn, "fk_storage_upload_blob", () -> deleteBlob(conn, blobId));

      assertEquals(
          1L,
          queryLong(conn, "select count(*) from storage_upload where id = '" + uploadId + "'"),
          "RESTRICT must never cascade-delete the referencing upload row");
      assertEquals(
          1L,
          queryLong(conn, "select count(*) from storage_blob where id = '" + blobId + "'"),
          "RESTRICT must keep the referenced blob row");
    }
  }

  /** 表、每一列与业务索引都必须带非空注释。 */
  @Test
  void storageTablesAreFullyCommented() throws SQLException {
    try (Connection conn = newConnection()) {
      assertEquals(
          1L,
          queryLong(conn, commentCount("storage_blob", 0)),
          "storage_blob table comment is missing");
      assertEquals(
          11L,
          queryLong(conn, commentCount("storage_blob", 1)),
          "every storage_blob column must be commented");
      assertEquals(
          1L,
          queryLong(conn, commentCount("storage_upload", 0)),
          "storage_upload table comment is missing");
      assertEquals(
          12L,
          queryLong(conn, commentCount("storage_upload", 1)),
          "every storage_upload column must be commented");
    }
    for (String index :
        new String[] {
          "uk_storage_blob_active_hash",
          "uk_storage_upload_candidate",
          "idx_storage_upload_cleanup_claim"
        }) {
      try (Connection conn = newConnection()) {
        assertEquals(
            1L,
            queryLong(
                conn,
                "select count(*) from pg_description d"
                    + " join pg_class c on c.oid = d.objoid"
                    + " join pg_namespace n on n.oid = c.relnamespace"
                    + " where n.nspname = 'public' and c.relname = '"
                    + index
                    + "' and d.objsubid = 0"),
            "index comment is missing: " + index);
      }
    }
  }

  private static String commentCount(String table, int columnComment) {
    return "select count(*) from pg_description d"
        + " join pg_class c on c.oid = d.objoid"
        + " join pg_namespace n on n.oid = c.relnamespace"
        + " where n.nspname = 'public' and c.relname = '"
        + table
        + "' and d.objsubid "
        + (columnComment == 0 ? "= 0" : "> 0")
        + " and d.description is not null and btrim(d.description) <> ''";
  }

  private static void insertBlob(
      Connection conn,
      UUID id,
      String sha256,
      long size,
      String mediaType,
      long refCount,
      String state)
      throws SQLException {
    insertBlob(
        conn,
        id,
        sha256,
        size,
        mediaType,
        refCount,
        state,
        (Integer) null,
        (Integer) null,
        (Long) null);
  }

  private static void insertBlob(
      Connection conn,
      UUID id,
      String sha256,
      long size,
      String mediaType,
      long refCount,
      String state,
      Integer width,
      Integer height)
      throws SQLException {
    insertBlob(conn, id, sha256, size, mediaType, refCount, state, width, height, (Long) null);
  }

  private static void insertBlob(
      Connection conn,
      UUID id,
      String sha256,
      long size,
      String mediaType,
      long refCount,
      String state,
      Long durationMs)
      throws SQLException {
    insertBlob(
        conn,
        id,
        sha256,
        size,
        mediaType,
        refCount,
        state,
        (Integer) null,
        (Integer) null,
        durationMs);
  }

  private static void insertBlob(
      Connection conn,
      UUID id,
      String sha256,
      long size,
      String mediaType,
      long refCount,
      String state,
      Integer width,
      Integer height,
      Long durationMs)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into storage_blob (id, sha256, size_bytes, media_type, width, height,"
                + " duration_ms, ref_count, state) values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, sha256);
      ps.setLong(3, size);
      ps.setString(4, mediaType);
      ps.setObject(5, width);
      ps.setObject(6, height);
      ps.setObject(7, durationMs);
      ps.setLong(8, refCount);
      ps.setString(9, state);
      ps.executeUpdate();
    }
  }

  private static void insertUpload(
      Connection conn, UUID id, String filename, String mediaType, long size, String sha256)
      throws SQLException {
    insertUpload(conn, id, filename, mediaType, size, sha256, null, false);
  }

  private static void insertUpload(
      Connection conn,
      UUID id,
      String filename,
      String mediaType,
      long size,
      String sha256,
      UUID blobId)
      throws SQLException {
    insertUpload(conn, id, filename, mediaType, size, sha256, blobId, false);
  }

  private static void insertUpload(
      Connection conn,
      UUID id,
      String filename,
      String mediaType,
      long size,
      String sha256,
      boolean expired)
      throws SQLException {
    insertUpload(conn, id, filename, mediaType, size, sha256, null, expired);
  }

  private static void insertUpload(
      Connection conn,
      UUID id,
      String filename,
      String mediaType,
      long size,
      String sha256,
      UUID blobId,
      boolean expired)
      throws SQLException {
    // expires_at <= created_at 的行触发 ck_storage_upload_expiry；表达式直接内联以保持参数占位符稳定。
    String expiresAt =
        expired ? "current_timestamp - interval '1 hour'" : "current_timestamp + interval '1 hour'";
    String createdSql =
        "insert into storage_upload (id, candidate_blob_id, blob_id, filename,"
            + " declared_media_type, declared_size, declared_sha256, expires_at, created_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, "
            + expiresAt
            + ", current_timestamp)";
    try (PreparedStatement ps = conn.prepareStatement(createdSql)) {
      ps.setObject(1, id);
      ps.setObject(2, UUID.randomUUID());
      ps.setObject(3, blobId);
      ps.setString(4, filename);
      ps.setString(5, mediaType);
      ps.setLong(6, size);
      ps.setString(7, sha256);
      ps.executeUpdate();
    }
  }

  private static void deleteBlob(Connection conn, UUID id) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("delete from storage_blob where id = ?")) {
      ps.setObject(1, id);
      ps.executeUpdate();
    }
  }

  private static void execute(Connection conn, String sql) throws SQLException {
    try (Statement statement = conn.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private static long queryLong(Connection conn, String sql) throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
