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
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeAlreadyExistsException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeKindConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudVersionConflictException;
import fun.fengwk.kkstudio.platform.storage.S3PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
  void setUpTransactionTemplate() {
    tx = new TransactionTemplate(transactionManager);
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

  /** 验证预置根目录存在性，以及 listChildren 仅在根目录精确隐藏 .artifacts，而用户创建的根级与子级 dot 文件均正常暴露。 */
  @Test
  void testPreseededDirectoriesAndListChildrenWithDotFiles() {
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
  }

  /** 验证 findNode 对 null 路径参数进行严格的 Objects.requireNonNull 约束校验。 */
  @Test
  void testFindNodeRequiresNonNull() {
    assertThrows(NullPointerException.class, () -> fileSystemService.findNode(null));
  }

  /** 验证递归与非递归创建目录，以及重复创建、缺失父目录和对保留目录写操作的校验拦截。 */
  @Test
  void testMkdirSuccessAndConflict() {
    CloudPath dirPath = CloudPath.of("/workspace/src");
    CloudNode node = fileSystemService.mkdir(dirPath, true);
    assertNotNull(node);
    assertEquals("src", node.getName());
    assertTrue(node.isDirectory());

    // Non-recursive duplicate fails
    assertThrows(
        CloudNodeAlreadyExistsException.class, () -> fileSystemService.mkdir(dirPath, false));

    // Non-recursive with missing parent fails
    assertThrows(
        CloudNodeNotFoundException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/other/missing/dir"), false));

    // Mutating root or .artifacts forbidden
    assertThrows(
        CloudPathForbiddenException.class, () -> fileSystemService.mkdir(CloudPath.of("/"), false));
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/.artifacts/forbidden"), true));
  }

  /** 验证文本文件的 CAS 写入、历史版本不可变归档读取与保留目录写入拦截。 */
  @Test
  void testWriteAndReadCurrentAndHistoricalText() {
    CloudPath path = CloudPath.of("/docs/readme.md");
    String textV1 = "# Readme\nVersion 1\n";

    // ExpectedRevision=0 creates file and missing parent /docs
    CloudTextRevision rev1 = fileSystemService.writeText(path, textV1, 0L);
    assertEquals(1L, rev1.getRevision());
    assertEquals(textV1, rev1.getContent());
    assertTrue(rev1.isCurrent());

    // Read current
    CloudTextRevision cur = fileSystemService.readCurrentText(path);
    assertEquals(1L, cur.getRevision());
    assertEquals(textV1, cur.getContent());

    // Stale expectedRevision=0 fails
    assertThrows(
        CloudRevisionConflictException.class,
        () -> fileSystemService.writeText(path, "stale create", 0L));

    // CAS conflict with wrong expectedRevision
    assertThrows(
        CloudRevisionConflictException.class,
        () -> fileSystemService.writeText(path, "wrong revision", 99L));

    // ExpectedRevision=1 updates to revision 2
    String textV2 = "# Readme\nVersion 2 updated\n";
    CloudTextRevision rev2 = fileSystemService.writeText(path, textV2, 1L);
    assertEquals(2L, rev2.getRevision());
    assertEquals(textV2, rev2.getContent());
    assertTrue(rev2.isCurrent());

    // Current is now v2
    assertEquals(2L, fileSystemService.readCurrentText(path).getRevision());

    // Historical v1 is preserved
    CloudTextRevision hist1 = fileSystemService.readTextRevision(path, 1L);
    assertEquals(1L, hist1.getRevision());
    assertEquals(textV1, hist1.getContent());
    assertFalse(hist1.isCurrent());

    // Writing under /.artifacts is fail-closed
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.writeText(CloudPath.of("/.artifacts/readme.txt"), "forbidden", 0L));
  }

  /** 验证文本内容中的孤立 surrogate 字符在写入和编辑时被快速失败拦截，杜绝编码损坏。 */
  @Test
  void testTextStrictSurrogateRejection() {
    CloudPath path = CloudPath.of("/docs/surrogate.txt");

    // Writing text containing lone high surrogate fails
    assertThrows(
        CloudPathValidationException.class,
        () -> fileSystemService.writeText(path, "bad text \uD800 end", 0L));

    // Writing valid text succeeds
    fileSystemService.writeText(path, "good text prefix", 0L);

    // Editing text with replacement containing lone low surrogate fails
    assertThrows(
        CloudPathValidationException.class,
        () -> fileSystemService.editText(path, "prefix", "\uDC00", 1L, false));
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

    // Replace all with replaceAll=true
    CloudTextRevision rev2 = fileSystemService.editText(path, "count = 1", "count = 2", 1L, true);
    assertEquals(2L, rev2.getRevision());
    assertEquals("count = 2\nprint(count)\ncount = 2\n", rev2.getContent());

    // Single replace with replaceAll=false
    CloudTextRevision rev3 =
        fileSystemService.editText(path, "print(count)", "print('done')", 2L, false);
    assertEquals(3L, rev3.getRevision());
    assertEquals("count = 2\nprint('done')\ncount = 2\n", rev3.getContent());

    // Revision conflict
    assertThrows(
        CloudRevisionConflictException.class,
        () -> fileSystemService.editText(path, "done", "finished", 1L, false));
  }

  /** 验证节点重命名与跨目录移动，断言仅目录移动触发子目录循环检查（CloudCycleException），以及目标已存在与 CAS 版本校验。 */
  @Test
  void testMoveNodeAndCycleDetection() {
    fileSystemService.mkdir(CloudPath.of("/tree/sub1/sub2"), true);
    fileSystemService.mkdir(CloudPath.of("/target_dir"), true);
    fileSystemService.writeText(CloudPath.of("/tree/sub1/sub2/file.txt"), "content", 0L);

    // Move file to target_dir
    CloudNode movedFile =
        fileSystemService.moveNode(
            CloudPath.of("/tree/sub1/sub2/file.txt"), CloudPath.of("/target_dir/moved.txt"), 0L);
    assertEquals("moved.txt", movedFile.getName());
    assertEquals(1L, movedFile.getVersion());
    assertTrue(fileSystemService.findNode(CloudPath.of("/target_dir/moved.txt")).isPresent());
    assertFalse(fileSystemService.findNode(CloudPath.of("/tree/sub1/sub2/file.txt")).isPresent());

    // Cycle detection: cannot move /tree into /tree/sub1/sub2
    assertThrows(
        CloudCycleException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/tree"), CloudPath.of("/tree/sub1/sub2/tree_cycle"), 0L));

    // Target already exists
    assertThrows(
        CloudNodeAlreadyExistsException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/tree/sub1"), CloudPath.of("/target_dir"), 0L));

    // CAS version conflict
    assertThrows(
        CloudVersionConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/target_dir/moved.txt"),
                CloudPath.of("/target_dir/moved2.txt"),
                99L));
  }

  /** 验证删除非空目录被拦截、删除空目录成功，以及删除文本文件时清理其所有历史修订版本记录。 */
  @Test
  void testDeleteNodeAndRevisionCleanup() {
    fileSystemService.mkdir(CloudPath.of("/nested/dir"), true);

    // Non-empty directory delete fails
    assertThrows(
        CloudDirectoryNotEmptyException.class,
        () -> fileSystemService.deleteNode(CloudPath.of("/nested"), 0L));

    // Delete empty directory succeeds
    fileSystemService.deleteNode(CloudPath.of("/nested/dir"), 0L);
    assertFalse(fileSystemService.findNode(CloudPath.of("/nested/dir")).isPresent());

    // Create text file and delete, cleaning up text revisions
    CloudPath filePath = CloudPath.of("/nested/sample.txt");
    fileSystemService.writeText(filePath, "hello", 0L);
    fileSystemService.writeText(filePath, "world", 1L);
    CloudNode textNode = fileSystemService.getNode(filePath);

    Integer revCountBefore =
        jdbc.queryForObject(
            "select count(*) from cloud_text_revision where node_id = ?",
            Integer.class,
            textNode.getId());
    assertEquals(2, revCountBefore);

    fileSystemService.deleteNode(filePath, 0L);
    assertFalse(fileSystemService.findNode(filePath).isPresent());

    Integer revCountAfter =
        jdbc.queryForObject(
            "select count(*) from cloud_text_revision where node_id = ?",
            Integer.class,
            textNode.getId());
    assertEquals(0, revCountAfter);

    // Public delete on /.artifacts fails
    assertThrows(
        CloudPathForbiddenException.class,
        () -> fileSystemService.deleteNode(CloudPath.of("/.artifacts"), 0L));
  }

  /** 验证创建 BLOB 节点时递增底层 storage_blob 引用，删除 BLOB 节点时事务内释放引用。 */
  @Test
  void testBlobNodeCreationAndRelease() {
    UUID blobId = seedActiveBlob("binary blob data");

    // Initially blob ref_count is 1
    StorageBlob initialBlob = storageBlobManager.getBlob(blobId);
    assertNotNull(initialBlob);
    assertEquals(1L, initialBlob.getRefCount());

    // Create BLOB node
    CloudPath blobPath = CloudPath.of("/uploads/picture.png");
    CloudNode blobNode = fileSystemService.createBlobNode(blobPath, blobId);
    assertEquals(CloudNodeKind.BLOB, blobNode.getKind());
    assertEquals(blobId, blobNode.getBlobId());

    // underlying blob ref_count incremented to 2
    StorageBlob retainedBlob = storageBlobManager.getBlob(blobId);
    assertEquals(2L, retainedBlob.getRefCount());

    // Delete BLOB node
    fileSystemService.deleteNode(blobPath, 0L);
    assertFalse(fileSystemService.findNode(blobPath).isPresent());

    // underlying blob ref_count decremented back to 1
    StorageBlob releasedBlob = storageBlobManager.getBlob(blobId);
    assertEquals(1L, releasedBlob.getRefCount());
  }

  /** 验证在 TEXT 或 BLOB 节点下尝试创建子目录、文件或将其作为移动目标父节点时均被严格拒绝。 */
  @Test
  void testParentKindConflictWhenParentIsTextOrBlob() {
    UUID blobId = seedActiveBlob("sample blob");
    fileSystemService.writeText(CloudPath.of("/text_parent"), "content", 0L);
    fileSystemService.createBlobNode(CloudPath.of("/blob_parent"), blobId);

    // Cannot create directory under TEXT parent
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/text_parent/child"), false));
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/text_parent/child/deep"), true));

    // Cannot create text file under TEXT parent
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.writeText(CloudPath.of("/text_parent/child.txt"), "abc", 0L));

    // Cannot create blob under TEXT parent
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.createBlobNode(CloudPath.of("/text_parent/child.bin"), blobId));

    // Cannot create directory under BLOB parent
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/blob_parent/child"), false));
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.mkdir(CloudPath.of("/blob_parent/child/deep"), true));

    // Cannot create text file under BLOB parent
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.writeText(CloudPath.of("/blob_parent/child.txt"), "abc", 0L));

    // Cannot create blob under BLOB parent
    assertThrows(
        CloudNodeKindConflictException.class,
        () -> fileSystemService.createBlobNode(CloudPath.of("/blob_parent/child.bin"), blobId));

    // Move target parent is TEXT
    fileSystemService.writeText(CloudPath.of("/src.txt"), "hello", 0L);
    assertThrows(
        CloudNodeKindConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/src.txt"), CloudPath.of("/text_parent/moved.txt"), 0L));

    // Move target parent is BLOB
    assertThrows(
        CloudNodeKindConflictException.class,
        () ->
            fileSystemService.moveNode(
                CloudPath.of("/src.txt"), CloudPath.of("/blob_parent/moved.txt"), 0L));
  }

  /** 验证并发两个线程向相同路径以 expectedRevision=0 执行初次文本创建时，恰有一线程成功，败者收到领域版本冲突异常。 */
  @Test
  void testConcurrentCreateTextExpectedRevisionZero() throws Exception {
    CloudPath path = CloudPath.of("/concurrent/test.txt");
    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);
    List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int idx = i;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.writeText(path, "content from thread " + idx, 0L);
                  successCount.incrementAndGet();
                } catch (CloudRevisionConflictException e) {
                  conflictCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
    assertEquals(
        1, successCount.get(), "Exactly one thread must succeed creating with expectedRevision=0");
    assertEquals(
        1, conflictCount.get(), "Losing thread must receive CloudRevisionConflictException");

    CloudTextRevision current = fileSystemService.readCurrentText(path);
    assertNotNull(current);
    assertEquals(1L, current.getRevision());
  }

  /** 验证多线程并发进行递归父目录自愈创建时安全收敛，不抛出唯一约束冲突或死锁。 */
  @Test
  void testConcurrentRecursiveEnsureDirectories() throws Exception {
    int threadCount = 4;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CyclicBarrier barrier = new CyclicBarrier(threadCount);

    AtomicInteger successCount = new AtomicInteger(0);
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int idx = i;
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.writeText(
                      CloudPath.of("/concurrent/shared/tree/file_" + idx + ".txt"),
                      "data " + idx,
                      0L);
                  successCount.incrementAndGet();
                } catch (Throwable t) {
                  errors.add(t);
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(10, TimeUnit.SECONDS);
    }
    executor.shutdown();

    assertTrue(errors.isEmpty(), () -> "Errors in concurrent ensureDirectories: " + errors);
    assertEquals(threadCount, successCount.get());

    List<CloudNode> children =
        fileSystemService.listChildren(CloudPath.of("/concurrent/shared/tree"));
    assertEquals(threadCount, children.size());
  }
}
