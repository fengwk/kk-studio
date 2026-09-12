package fun.fengwk.kkstudio.platform.cloudfs.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.UUID;

/** 真实 PostgreSQL schema 级约束测试，直接通过原生 SQL 验证节点类型与版本约束。 */
class CloudFileSystemSchemaIntegrationTest extends PostgresSpringTestSupport {

  private static final String FK_PARENT = "fk_cloud_node_parent";
  private static final String FK_REVISION_NODE = "fk_cloud_text_revision_node";
  private static final String SAMPLE_SHA256 =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Autowired private JdbcTemplate jdbc;

  private UUID insertStorageBlob() {
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state, created_at, updated_at) "
            + "values (?, ?, 100, 'application/octet-stream', 1, 'ACTIVE', current_timestamp, current_timestamp)",
        blobId,
        SAMPLE_SHA256);
    return blobId;
  }

  private String extractConstraint(Throwable t) {
    while (t != null) {
      if (t instanceof PSQLException pe && pe.getServerErrorMessage() != null) {
        return pe.getServerErrorMessage().getConstraint();
      }
      t = t.getCause();
    }
    return "";
  }

  /** 证明 TEXT 节点不能作为父节点，受 fk_cloud_node_parent 约束拦截。 */
  @Test
  void testTextCannotBeParent() {
    UUID textId = UUID.randomUUID();
    jdbc.update(
        "insert into cloud_node (id, parent_id, name, kind, version) values (?, null, 'file.txt', 'TEXT', 0)",
        textId);

    UUID childId = UUID.randomUUID();
    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into cloud_node (id, parent_id, name, kind, version) values (?, ?, 'sub', 'DIRECTORY', 0)",
                    childId,
                    textId));
    assertEquals(FK_PARENT, extractConstraint(ex));
  }

  /** 证明 BLOB 节点不能作为父节点，受 fk_cloud_node_parent 约束拦截。 */
  @Test
  void testBlobCannotBeParent() {
    UUID blobStorageId = insertStorageBlob();
    UUID blobNodeId = UUID.randomUUID();
    jdbc.update(
        "insert into cloud_node (id, parent_id, name, kind, version, blob_id) values (?, null, 'data.bin', 'BLOB', 0, ?)",
        blobNodeId,
        blobStorageId);

    UUID childId = UUID.randomUUID();
    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into cloud_node (id, parent_id, name, kind, version) values (?, ?, 'sub', 'DIRECTORY', 0)",
                    childId,
                    blobNodeId));
    assertEquals(FK_PARENT, extractConstraint(ex));
  }

  /** 证明 DIRECTORY 节点不能关联 cloud_text_revision，受 fk_cloud_text_revision_node 约束拦截。 */
  @Test
  void testDirectoryCannotHaveTextRevision() {
    UUID dirId = UUID.randomUUID();
    jdbc.update(
        "insert into cloud_node (id, parent_id, name, kind, version) values (?, null, 'mydir', 'DIRECTORY', 0)",
        dirId);

    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into cloud_text_revision (node_id, revision, content, size_bytes, sha256, is_current) "
                        + "values (?, 1, 'content', 7, ?, true)",
                    dirId,
                    SAMPLE_SHA256));
    assertEquals(FK_REVISION_NODE, extractConstraint(ex));
  }

  /** 证明 BLOB 节点不能关联 cloud_text_revision，受 fk_cloud_text_revision_node 约束拦截。 */
  @Test
  void testBlobCannotHaveTextRevision() {
    UUID blobStorageId = insertStorageBlob();
    UUID blobNodeId = UUID.randomUUID();
    jdbc.update(
        "insert into cloud_node (id, parent_id, name, kind, version, blob_id) values (?, null, 'image.png', 'BLOB', 0, ?)",
        blobNodeId,
        blobStorageId);

    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into cloud_text_revision (node_id, revision, content, size_bytes, sha256, is_current) "
                        + "values (?, 1, 'content', 7, ?, true)",
                    blobNodeId,
                    SAMPLE_SHA256));
    assertEquals(FK_REVISION_NODE, extractConstraint(ex));
  }

  /** 证明拥有子节点的 DIRECTORY 节点不能将其 kind 更新为 TEXT 或 BLOB，受 fk_cloud_node_parent 约束拦截。 */
  @Test
  void testKindCannotBeChangedToBreakExistingParent() {
    UUID parentDirId = UUID.randomUUID();
    jdbc.update(
        "insert into cloud_node (id, parent_id, name, kind, version) values (?, null, 'parent_dir', 'DIRECTORY', 0)",
        parentDirId);

    UUID childId = UUID.randomUUID();
    jdbc.update(
        "insert into cloud_node (id, parent_id, name, kind, version) values (?, ?, 'child', 'DIRECTORY', 0)",
        childId,
        parentDirId);

    // Attempt updating DIRECTORY -> TEXT
    DataIntegrityViolationException exText =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("update cloud_node set kind = 'TEXT' where id = ?", parentDirId));
    assertEquals(FK_PARENT, extractConstraint(exText));

    // Attempt updating DIRECTORY -> BLOB
    UUID blobStorageId = insertStorageBlob();
    DataIntegrityViolationException exBlob =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "update cloud_node set kind = 'BLOB', blob_id = ? where id = ?",
                    blobStorageId,
                    parentDirId));
    assertEquals(FK_PARENT, extractConstraint(exBlob));
  }

  /** 证明挂载了文本版本的 TEXT 节点不能将其 kind 更新为 DIRECTORY 或 BLOB，受 fk_cloud_text_revision_node 约束拦截。 */
  @Test
  void testKindCannotBeChangedToBreakExistingRevision() {
    UUID textId = UUID.randomUUID();
    jdbc.update(
        "insert into cloud_node (id, parent_id, name, kind, version) values (?, null, 'doc.md', 'TEXT', 0)",
        textId);

    jdbc.update(
        "insert into cloud_text_revision (node_id, revision, content, size_bytes, sha256, is_current) "
            + "values (?, 1, '# Hello', 7, ?, true)",
        textId,
        SAMPLE_SHA256);

    // Attempt updating TEXT -> DIRECTORY
    DataIntegrityViolationException exDir =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> jdbc.update("update cloud_node set kind = 'DIRECTORY' where id = ?", textId));
    assertEquals(FK_REVISION_NODE, extractConstraint(exDir));

    // Attempt updating TEXT -> BLOB
    UUID blobStorageId = insertStorageBlob();
    DataIntegrityViolationException exBlob =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "update cloud_node set kind = 'BLOB', blob_id = ? where id = ?",
                    blobStorageId,
                    textId));
    assertEquals(FK_REVISION_NODE, extractConstraint(exBlob));
  }
}
