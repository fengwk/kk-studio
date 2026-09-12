package fun.fengwk.kkstudio.platform.cloudfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudCycleException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudDirectoryNotEmptyException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditAmbiguousException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditPatternNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeAlreadyExistsException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeKindConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudVersionConflictException;
import fun.fengwk.kkstudio.platform.storage.S3PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link CloudFileSystemService} PostgreSQL 集成测试。 */
@Import({StorageS3TestConfiguration.class})
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class CloudFileSystemServiceIntegrationTest extends S3PostgresSpringTestSupport {

  @Autowired private CloudFileSystemService fileSystemService;
  @Autowired private StorageBlobManager storageBlobManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;

  @BeforeEach
  void setUp() {
    tx = new TransactionTemplate(transactionManager);
    tx.execute(
        status -> {
          jdbc.update(
              "delete from cloud_node where id not in ("
                  + "'c0000000-0000-0000-0000-000000000001',"
                  + "'c0000000-0000-0000-0000-000000000002',"
                  + "'c0000000-0000-0000-0000-000000000003',"
                  + "'c0000000-0000-0000-0000-000000000004')");
          return null;
        });
  }

  private UUID seedActiveBlob(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256Hex(bytes);
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state, created_at, updated_at) "
            + "values (?, ?, ?, 'text/plain', 1, 'ACTIVE', current_timestamp, current_timestamp)",
        blobId,
        sha256,
        (long) bytes.length);
    return blobId;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** 验证预置根目录存在性，以及 listChildren 仅在根目录精确隐藏 .artifacts，而用户创建的根级与子级 dot 文件均正常暴露；对非目录调用抛出类型冲突。 */
  @Test
  void testPreseededDirectoriesAndListChildrenWithDotFilesAndKindConflict() {
    Optional<CloudNode> root = fileSystemService.findNode(CloudPath.of("/"));
    assertTrue(root.isPresent());
    assertTrue(root.get().isDirectory());

    Optional<CloudNode> knowledge = fileSystemService.findNode(CloudPath.of("/knowledge"));
    assertTrue(knowledge.isPresent());
    assertTrue(knowledge.get().isDirectory());

    Optional<CloudNode> uploads = fileSystemService.findNode(CloudPath.of("/uploads"));
    assertTrue(uploads.isPresent());
    assertTrue(uploads.get().isDirectory());

    Optional<CloudNode> artifacts = fileSystemService.findNode(CloudPath.of("/.artifacts"));
    assertTrue(artifacts.isPresent());
    assertTrue(artifacts.get().isDirectory());

    // Create user dot file at root
    fileSystemService.writeText(CloudPath.of("/.gitignore"), "*.log\n", 0L);

    // listChildren on root excludes EXACT .artifacts, but includes user dot files like .gitignore
    List<CloudNode> rootList = fileSystemService.listChildren(CloudPath.of("/"));
    assertTrue(rootList.stream().noneMatch(n -> ".artifacts".equals(n.getName())));
    assertTrue(rootList.stream().anyMatch(n -> ".gitignore".equals(n.getName())));
    assertTrue(rootList.stream().anyMatch(n -> "knowledge".equals(n.getName())));
    assertTrue(rootList.stream().anyMatch(n -> "uploads".equals(n.getName())));

    // Create nested dot file under workspace
    fileSystemService.mkdir(CloudPath.of("/workspace"), true);
    fileSystemService.writeText(CloudPath.of("/workspace/.env"), "KEY=VALUE\n", 0L);

    // Nested listChildren includes dot files
    List<CloudNode> workspaceList = fileSystemService.listChildren(CloudPath.of("/workspace"));
    assertTrue(workspaceList.stream().anyMatch(n -> ".env".equals(n.getName())));

    // listChildren on non-directory TEXT node throws CloudNodeKindConflictException
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.listChildren(CloudPath.of("/.gitignore")));
  }

  /** 验证 findNode 与 getNode 的非空校验与节点不存在异常。 */
  @Test
  void testFindNodeAndGetNodeSemantics() {
    assertThrows(NullPointerException.class, () -> fileSystemService.findNode(null));
    assertThrows(NullPointerException.class, () -> fileSystemService.getNode(null));
    assertThrows(
        CloudNodeNotFoundException.class,
        () -> fileSystemService.getNode(CloudPath.of("/not/exists")));

    // Intermediate segment is not a directory in findNode
    fileSystemService.writeText(CloudPath.of("/leaf.txt"), "leaf", 0L);
    assertFalse(fileSystemService.findNode(CloudPath.of("/leaf.txt/sub")).isPresent());
  }

  /** 验证递归与非递归创建目录，以及重复创建、缺失父目录和对保留目录写操作的校验拦截。 */
  @Test
  void testMkdirSuccessAndConflict() {
    // Non-recursive mkdir at root
    CloudPath topDir = CloudPath.of("/top_dir");
    CloudNode createdTop = fileSystemService.mkdir(topDir, false);
    assertNotNull(createdTop);
    assertTrue(createdTop.isDirectory());

    // Non-recursive duplicate fails
    assertThrows(
        CloudNodeAlreadyExistsException.class, () -> fileSystemService.mkdir(topDir, false));

    // Non-recursive missing parent fails
    assertThrows(
        CloudNodeNotFoundException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/missing_parent/sub"), false));

    // Non-recursive under existing parent succeeds
    CloudPath subDir = CloudPath.of("/top_dir/sub");
    CloudNode createdSub = fileSystemService.mkdir(subDir, false);
    assertNotNull(createdSub);

    // Recursive mkdir
    CloudPath dirPath = CloudPath.of("/workspace/src");
    CloudNode dir = fileSystemService.mkdir(dirPath, true);
    assertNotNull(dir);
    assertTrue(dir.isDirectory());
    assertEquals("src", dir.getName());

    // Recursive duplicate mkdir is idempotent
    CloudNode dir2 = fileSystemService.mkdir(dirPath, true);
    assertEquals(dir.getId(), dir2.getId());

    // Root directory cannot be created
    assertThrows(
        CloudPathForbiddenException.class, () -> fileSystemService.mkdir(CloudPath.root(), true));

    // Cannot mkdir under /.artifacts
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/.artifacts/forbidden"), true));
  }

  /** 验证文本文件的 CAS 写入、历史版本不可变归档读取与保留目录写入拦截。 */
  @Test
  void testWriteAndReadCurrentAndHistoricalText() {
    CloudPath path = CloudPath.of("/docs/readme.md");
    String textV1 = "Hello, CloudFS!\nLine 2\n";

    // Initial write with expectedRevision = 0
    CloudTextRevision rev1 = fileSystemService.writeText(path, textV1, 0L);
    assertEquals(1L, rev1.getRevision());
    assertEquals(textV1, rev1.getContent());
    assertTrue(rev1.isCurrent());

    // Duplicate initial write fails with CAS conflict
    assertThrows(
        CloudRevisionConflictException.class, () -> fileSystemService.writeText(path, "bad", 0L));

    // Update with wrong expectedRevision fails
    assertThrows(
        CloudRevisionConflictException.class,
        () -> fileSystemService.writeText(path, "updated", 99L));

    // Update with correct expectedRevision = 1 -> rev 2
    String textV2 = "Updated Content\n";
    CloudTextRevision rev2 = fileSystemService.writeText(path, textV2, 1L);
    assertEquals(2L, rev2.getRevision());
    assertEquals(textV2, rev2.getContent());
    assertTrue(rev2.isCurrent());

    // Read current text
    CloudTextRevision current = fileSystemService.readCurrentText(path);
    assertEquals(2L, current.getRevision());
    assertEquals(textV2, current.getContent());

    // Read historical rev 1
    CloudTextRevision historical1 = fileSystemService.readTextRevision(path, 1L);
    assertEquals(1L, historical1.getRevision());
    assertEquals(textV1, historical1.getContent());
    assertFalse(historical1.isCurrent());
  }

  /** 验证 writeText 的参数边界校验（root、artifact、大小超限、非法 revision、非文本节点覆盖及 surrogate 拒绝）。 */
  @Test
  void testWriteTextValidations() {
    CloudPath path = CloudPath.of("/docs/valid.txt");

    // Write to root
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.writeText(CloudPath.root(), "c", 0L));

    // Write to artifact
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.writeText(CloudPath.of("/.artifacts/doc.txt"), "c", 0L));

    // Negative revision
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.writeText(path, "c", -1L));

    // Oversize text (> 1 MiB = 1048576 bytes)
    String oversized = "a".repeat(1048577);
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.writeText(path, oversized, 0L));

    // Surrogate rejection
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.writeText(path, "bad \uD800 text", 0L));

    // Write to directory node with revision > 0
    fileSystemService.mkdir(CloudPath.of("/somedir"), false);
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.writeText(CloudPath.of("/somedir"), "content", 1L));

    // Intermediate segment is not a directory in writeText
    fileSystemService.writeText(path, "valid file", 0L);
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.writeText(CloudPath.of("/docs/valid.txt/child.txt"), "c", 0L));
  }

  /** 验证文本模式串替换编辑（单次匹配与全量匹配）、未命中异常信息固定且不回显原串、多重匹配歧义拦截与 CAS 版本控制。 */
  @Test
  void testEditText() {
    CloudPath path = CloudPath.of("/src/main.py");
    String initial = "count = 1\nprint(count)\ncount = 1\n";
    fileSystemService.writeText(path, initial, 0L);

    // Old string not found: verify fixed message "Could not find old_string in <path>"
    CloudEditPatternNotFoundException ex =
        assertThrows(
            CloudEditPatternNotFoundException.class,
            () -> fileSystemService.editText(path, "missing_pattern", "foo", 1L, false));
    assertEquals("Could not find old_string in " + path, ex.getMessage());

    // Ambiguous without replaceAll
    assertThrows(
        CloudEditAmbiguousException.class,
        () -> fileSystemService.editText(path, "count = 1", "count = 2", 1L, false));

    // Success with replaceAll = true
    CloudTextRevision edited = fileSystemService.editText(path, "count = 1", "count = 2", 1L, true);
    assertNotNull(edited);
    assertEquals(2L, edited.getRevision());
    CloudTextRevision rev2 = fileSystemService.readCurrentText(path);
    assertEquals("count = 2\nprint(count)\ncount = 2\n", rev2.getContent());
    assertEquals(2L, rev2.getRevision());

    // Single occurrence edit with replaceAll = false
    fileSystemService.editText(path, "print(count)", "print('done')", 2L, false);
    CloudTextRevision rev3 = fileSystemService.readCurrentText(path);
    assertEquals("count = 2\nprint('done')\ncount = 2\n", rev3.getContent());
    assertEquals(3L, rev3.getRevision());

    // CAS version conflict
    assertThrows(
        CloudRevisionConflictException.class,
        () -> fileSystemService.editText(path, "done", "finished", 1L, false));
  }

  /** 验证 editText 的参数边界校验（空模式串、root、artifact、非文本、非法 revision、大小超限及 surrogate 拒绝）。 */
  @Test
  void testEditTextValidations() {
    CloudPath path = CloudPath.of("/src/sample.txt");
    fileSystemService.writeText(path, "initial content", 0L);

    // Null parameters
    assertThrows(
        NullPointerException.class,
        () -> fileSystemService.editText(null, "old", "new", 1L, false));
    assertThrows(
        NullPointerException.class, () -> fileSystemService.editText(path, null, "new", 1L, false));
    assertThrows(
        NullPointerException.class, () -> fileSystemService.editText(path, "old", null, 1L, false));

    // Empty oldString
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.editText(path, "", "new", 1L, false));

    // Root and artifact forbidden
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.editText(CloudPath.root(), "old", "new", 1L, false));
    assertThrows(
        CloudPathForbiddenException.class,
        () ->
            fileSystemService.editText(
                CloudPath.of("/.artifacts/file.txt"), "old", "new", 1L, false));

    // Invalid revision (<= 0)
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.editText(path, "old", "new", 0L, false));
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.editText(path, "old", "new", -1L, false));

    // Edit non-text node
    fileSystemService.mkdir(CloudPath.of("/dir_not_text"), false);
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.editText(CloudPath.of("/dir_not_text"), "old", "new", 1L, false));

    // Replacement surrogate rejection
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.editText(path, "initial", "\uD800", 1L, false));

    // OldString surrogate rejection
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.editText(path, "\uDC00", "new", 1L, false));

    // Replacement resulting in oversized content (> 1 MiB)
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.editText(path, "initial", "a".repeat(1048577), 1L, false));
  }

  /** 验证节点重命名与跨目录移动，断言仅目录移动触发子目录循环检查（CloudCycleException），以及目标已存在与 CAS 版本校验。 */
  @Test
  void testMoveNodeAndCycleDetection() {
    fileSystemService.mkdir(CloudPath.of("/tree/sub1/sub2"), true);

    // Moving directory into its descendant triggers CloudCycleException with node message
    CloudCycleException ex =
        assertThrows(
            CloudCycleException.class,
            () ->
                fileSystemService.moveNode(
                    CloudPath.of("/tree"), CloudPath.of("/tree/sub1/sub2/tree"), 0L));
    assertTrue(ex.getMessage().contains("Cannot move node /tree into itself or its descendant"));

    // Successful directory move
    CloudNode movedDir =
        fileSystemService.moveNode(
            CloudPath.of("/tree/sub1/sub2"), CloudPath.of("/tree/moved_sub2"), 0L);
    assertEquals("moved_sub2", movedDir.getName());
    assertEquals(1L, movedDir.getVersion());

    // Stale version CAS conflict
    assertThrows(
        CloudVersionConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/tree/moved_sub2"), CloudPath.of("/tree/sub2_again"), 0L));
  }

  /** 验证 moveNode 的边界校验（root、artifact、相同路径、缺失源、冲突目标、负版本等）。 */
  @Test
  void testMoveNodeValidations() {
    fileSystemService.mkdir(CloudPath.of("/movetest"), false);
    fileSystemService.writeText(CloudPath.of("/movetest/file1.txt"), "f1", 0L);
    fileSystemService.writeText(CloudPath.of("/movetest/file2.txt"), "f2", 0L);

    // Null parameters
    assertThrows(
        NullPointerException.class,
        () -> fileSystemService.moveNode(null, CloudPath.of("/dest"), 0L));
    assertThrows(
        NullPointerException.class,
        () -> fileSystemService.moveNode(CloudPath.of("/src"), null, 0L));

    // Root forbidden
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.moveNode(CloudPath.root(), CloudPath.of("/dest"), 0L));
    assertThrows(
        CloudPathForbiddenException.class,
        () ->
            fileSystemService.moveNode(CloudPath.of("/movetest/file1.txt"), CloudPath.root(), 0L));

    // Artifact forbidden
    assertThrows(
        CloudPathForbiddenException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/.artifacts/file.txt"), CloudPath.of("/dest"), 0L));
    assertThrows(
        CloudPathForbiddenException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/movetest/file1.txt"), CloudPath.of("/.artifacts/file.txt"), 0L));

    // Same path
    assertThrows(
        CloudPathValidationException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/movetest/file1.txt"), CloudPath.of("/movetest/file1.txt"), 0L));

    // Missing source node
    assertThrows(
        CloudNodeNotFoundException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/movetest/missing.txt"), CloudPath.of("/movetest/target.txt"), 0L));

    // Target already exists
    assertThrows(
        CloudNodeAlreadyExistsException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/movetest/file1.txt"), CloudPath.of("/movetest/file2.txt"), 0L));

    // Negative version
    assertThrows(
        CloudFileSystemValidationException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/movetest/file1.txt"), CloudPath.of("/movetest/file1_new.txt"), -1L));

    // Stale version CAS conflict
    assertThrows(
        CloudVersionConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/movetest/file1.txt"), CloudPath.of("/movetest/file1_new.txt"), 99L));
  }

  /** 验证删除非空目录被拦截、删除空目录成功，以及删除文本文件时清理其所有历史修订版本记录、删除 BLOB 释放 storage_blob。 */
  @Test
  void testDeleteNodeAndRevisionCleanup() {
    fileSystemService.mkdir(CloudPath.of("/nested/dir"), true);

    // Non-empty directory delete fails
    assertThrows(
        CloudDirectoryNotEmptyException.class,
        () -> fileSystemService.deleteNode(CloudPath.of("/nested"), 0L));

    // Delete empty leaf directory
    fileSystemService.deleteNode(CloudPath.of("/nested/dir"), 0L);
    assertFalse(fileSystemService.findNode(CloudPath.of("/nested/dir")).isPresent());

    // Create text file and delete, cleaning up text revisions
    CloudPath filePath = CloudPath.of("/nested/sample.txt");
    fileSystemService.writeText(filePath, "hello", 0L);
    fileSystemService.writeText(filePath, "world", 1L);

    fileSystemService.deleteNode(filePath, 0L);
    assertFalse(fileSystemService.findNode(filePath).isPresent());

    // Root forbidden
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.deleteNode(CloudPath.root(), 0L));

    // Artifact forbidden
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.deleteNode(CloudPath.of("/.artifacts"), 0L));

    // Negative version
    assertThrows(
        CloudFileSystemValidationException.class,
        () -> fileSystemService.deleteNode(CloudPath.of("/nested"), -1L));

    // Stale version CAS conflict on directory
    assertThrows(
        CloudVersionConflictException.class,
        () -> fileSystemService.deleteNode(CloudPath.of("/nested"), 99L));

    // Stale version CAS conflict on text file
    CloudPath textConflict = CloudPath.of("/nested/text_conflict.txt");
    fileSystemService.writeText(textConflict, "sample", 0L);
    assertThrows(
        CloudVersionConflictException.class, () -> fileSystemService.deleteNode(textConflict, 99L));
  }

  /** 验证创建 BLOB 节点时递增底层 storage_blob 引用，删除 BLOB 节点时事务内释放引用。 */
  @Test
  void testBlobNodeCreationAndRelease() {
    UUID blobId = seedActiveBlob("binary blob data");
    CloudPath blobPath = CloudPath.of("/assets/images/logo.png");

    // Create BLOB node
    CloudNode blobNode = fileSystemService.createBlobNode(blobPath, blobId);
    assertNotNull(blobNode);
    assertEquals(CloudNodeKind.BLOB, blobNode.getKind());
    assertEquals(blobId, blobNode.getBlobId());

    // Duplicate creation fails
    assertThrows(
        CloudNodeAlreadyExistsException.class,
        () -> fileSystemService.createBlobNode(blobPath, blobId));

    // Storage blob retain called (1 -> 2)
    StorageBlob currentBlob = storageBlobManager.getBlob(blobId);
    assertEquals(2L, currentBlob.getRefCount());

    // Stale version CAS conflict on BLOB delete
    assertThrows(
        CloudVersionConflictException.class, () -> fileSystemService.deleteNode(blobPath, 99L));

    // Delete BLOB node releases storage_blob (2 -> 1)
    fileSystemService.deleteNode(blobPath, 0L);
    assertFalse(fileSystemService.findNode(blobPath).isPresent());

    StorageBlob releasedBlob = storageBlobManager.getBlob(blobId);
    assertEquals(1L, releasedBlob.getRefCount());
  }

  /** 验证 createBlobNode 的参数边界校验（root、artifact、缺失 blob、非空入参等）。 */
  @Test
  void testCreateBlobNodeValidations() {
    UUID dummyBlob = seedActiveBlob("blob content");

    assertThrows(
        NullPointerException.class, () -> fileSystemService.createBlobNode(null, dummyBlob));
    assertThrows(
        NullPointerException.class,
        () -> fileSystemService.createBlobNode(CloudPath.of("/b"), null));
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.createBlobNode(CloudPath.root(), dummyBlob));
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.createBlobNode(CloudPath.of("/.artifacts/b"), dummyBlob));
    assertThrows(
        StorageResourceNotFoundException.class,
        () -> fileSystemService.createBlobNode(CloudPath.of("/missing_blob"), UUID.randomUUID()));
  }

  /** 验证读取文本方法（readCurrentText / readTextRevision / readText）在非文本节点上的类型冲突拦截。 */
  @Test
  void testReadTextKindAndRevisionValidations() {
    CloudPath dirPath = CloudPath.of("/readdir");
    fileSystemService.mkdir(dirPath, false);

    assertThrows(
        CloudNodeKindConflictException.class, () -> fileSystemService.readCurrentText(dirPath));
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.readTextRevision(dirPath, 1L));

    CloudPath textPath = CloudPath.of("/readfile.txt");
    fileSystemService.writeText(textPath, "line 1\nline 2\n", 0L);
    assertThrows(
        CloudNodeNotFoundException.class, () -> fileSystemService.readTextRevision(textPath, 999L));
  }

  /** 验证在 TEXT 或 BLOB 节点下尝试创建子目录、文件或将其作为移动目标父节点时均被严格拒绝。 */
  @Test
  void testParentKindConflictWhenParentIsTextOrBlob() {
    UUID blobId = seedActiveBlob("sample blob");
    CloudPath blobPath = CloudPath.of("/blob_parent");
    fileSystemService.createBlobNode(blobPath, blobId);

    CloudPath textPath = CloudPath.of("/text_parent");
    fileSystemService.writeText(textPath, "sample text", 0L);

    // mkdir under BLOB parent fails
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/blob_parent/child_dir"), true));

    // mkdir under TEXT parent fails
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/text_parent/child_dir"), true));

    // writeText under BLOB parent fails
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.writeText(CloudPath.of("/blob_parent/child.txt"), "abc", 0L));

    // createBlobNode under TEXT parent fails
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.createBlobNode(CloudPath.of("/text_parent/child.png"), blobId));

    // moveNode to target parent that is BLOB fails
    fileSystemService.writeText(CloudPath.of("/src.txt"), "hello", 0L);
    assertThrows(
        CloudNodeKindConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/src.txt"), CloudPath.of("/blob_parent/moved.txt"), 0L));

    // moveNode to target parent that is TEXT fails (lexical order cmp <= 0)
    assertThrows(
        CloudNodeKindConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/src.txt"), CloudPath.of("/text_parent/moved.txt"), 0L));

    // moveNode with source > targetParent (lexical order cmp > 0)
    fileSystemService.writeText(CloudPath.of("/z_src.txt"), "hello", 0L);
    assertThrows(
        CloudNodeKindConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/z_src.txt"), CloudPath.of("/text_parent/moved.txt"), 0L));

    // moveNode to target that already exists throws CloudNodeAlreadyExistsException
    fileSystemService.writeText(CloudPath.of("/dest_exists.txt"), "existing", 0L);
    assertThrows(
        CloudNodeAlreadyExistsException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/src.txt"), CloudPath.of("/dest_exists.txt"), 0L));

    // createBlobNode when target already exists throws CloudNodeAlreadyExistsException
    assertThrows(
        CloudNodeAlreadyExistsException.class,
        () -> fileSystemService.createBlobNode(CloudPath.of("/src.txt"), blobId));
  }
}
