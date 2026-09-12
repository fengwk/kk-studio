package fun.fengwk.kkstudio.platform.cloudfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudDirectoryNotEmptyException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeAlreadyExistsException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeKindConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.storage.S3PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link CloudFileSystemService} 并发与事务锁集成测试。 */
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
class CloudFileSystemConcurrencyIntegrationTest extends S3PostgresSpringTestSupport {

  @Autowired private CloudFileSystemService fileSystemService;
  @Autowired private JdbcTemplate jdbc;

  private UUID seedActiveBlob(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256Hex(bytes);
    UUID blobId = UUID.randomUUID();
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state, created_at, updated_at) "
            + "values (?, ?, ?, 'text/plain', 1, 'ACTIVE', current_timestamp, current_timestamp)",
        blobId,
        sha256,
        bytes.length);
    return blobId;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 验证并发两个线程向相同路径以 expectedRevision=0 执行初次文本创建时，恰有一线程成功，败者收到领域版本冲突异常。 */
  @Test
  void testConcurrentCreateTextExpectedRevisionZero() throws Exception {
    CloudPath path = CloudPath.of("/concurrent/test.txt");
    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
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

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one write should succeed");
      assertEquals(
          1, conflictCount.get(), "Losing thread must receive CloudRevisionConflictException");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证两线程执行互反方向的移动时，基于 CloudPath 字典序锁定路径，杜绝相反加锁顺序产生的死锁。 */
  @Test
  void testConcurrentReverseMovesNoDeadlock() throws Exception {
    fileSystemService.mkdir(CloudPath.of("/dirA"), false);
    fileSystemService.mkdir(CloudPath.of("/dirB"), false);
    fileSystemService.writeText(CloudPath.of("/dirA/fileA.txt"), "A", 0L);
    fileSystemService.writeText(CloudPath.of("/dirB/fileB.txt"), "B", 0L);

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      // Thread 1: move /dirA/fileA.txt -> /dirB/fileA.txt
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(
                      CloudPath.of("/dirA/fileA.txt"), CloudPath.of("/dirB/fileA.txt"), 0L);
                  successCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
      // Thread 2: move /dirB/fileB.txt -> /dirA/fileB.txt
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(
                      CloudPath.of("/dirB/fileB.txt"), CloudPath.of("/dirA/fileB.txt"), 0L);
                  successCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(2, successCount.get(), "Both reverse moves should succeed without deadlock");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证两线程并发移动不同源节点到同一目标路径时，恰有一线程成功，另一线程捕获 CloudNodeAlreadyExistsException。 */
  @Test
  void testConcurrentMoveCollisionOnSameTarget() throws Exception {
    fileSystemService.mkdir(CloudPath.of("/src1"), false);
    fileSystemService.mkdir(CloudPath.of("/src2"), false);
    fileSystemService.mkdir(CloudPath.of("/target"), false);
    fileSystemService.writeText(CloudPath.of("/src1/item.txt"), "item1", 0L);
    fileSystemService.writeText(CloudPath.of("/src2/item.txt"), "item2", 0L);

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger existsCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(
                      CloudPath.of("/src1/item.txt"), CloudPath.of("/target/item.txt"), 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeAlreadyExistsException e) {
                  existsCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(
                      CloudPath.of("/src2/item.txt"), CloudPath.of("/target/item.txt"), 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeAlreadyExistsException e) {
                  existsCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one move should succeed");
      assertEquals(
          1, existsCount.get(), "Losing thread must receive CloudNodeAlreadyExistsException");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证在父目录下创建子节点与删除父目录并发时，通过父节点排他锁有效避免半成品或删除非空目录。 */
  @Test
  void testConcurrentChildCreateVsParentDirectoryDelete() throws Exception {
    fileSystemService.mkdir(CloudPath.of("/pdir"), false);

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger createSuccess = new AtomicInteger(0);
      AtomicInteger deleteSuccess = new AtomicInteger(0);
      AtomicInteger notEmptyCount = new AtomicInteger(0);
      AtomicInteger notFoundCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      // Thread 1: create child under /pdir (non-recursive mkdir locks parent and requires it to
      // exist)
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.mkdir(CloudPath.of("/pdir/child_dir"), false);
                  createSuccess.incrementAndGet();
                } catch (CloudNodeNotFoundException e) {
                  // If delete happened first, parent directory is not found
                  notFoundCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
      // Thread 2: delete /pdir
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.deleteNode(CloudPath.of("/pdir"), 0L);
                  deleteSuccess.incrementAndGet();
                } catch (CloudDirectoryNotEmptyException e) {
                  // If create happened first, directory is non-empty
                  notEmptyCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      // Either (create succeeded and delete failed with CloudDirectoryNotEmptyException)
      // or (delete succeeded and create failed with CloudNodeNotFoundException)
      boolean createFirst = (createSuccess.get() == 1 && notEmptyCount.get() == 1);
      boolean deleteFirst = (deleteSuccess.get() == 1 && notFoundCount.get() == 1);
      assertTrue(
          createFirst || deleteFirst,
          () ->
              String.format(
                  "Expected mutually exclusive success, got createSuccess=%d, deleteSuccess=%d, notEmpty=%d, notFound=%d",
                  createSuccess.get(),
                  deleteSuccess.get(),
                  notEmptyCount.get(),
                  notFoundCount.get()));
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证根级别并发两线程以 expectedRevision=0 创建相同文本节点时，恰有一线程成功，另一线程捕获领域版本冲突异常。 */
  @Test
  void testConcurrentRootCreateTextExpectedRevisionZero() throws Exception {
    CloudPath path = CloudPath.of("/root_concurrent.txt");
    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
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
                    fileSystemService.writeText(path, "root content from thread " + idx, 0L);
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

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one root text write should succeed");
      assertEquals(
          1, conflictCount.get(), "Losing thread must receive CloudRevisionConflictException");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证根级别并发两线程创建同名目录时，恰有一线程成功，另一线程稳定抛出领域冲突异常 CloudNodeAlreadyExistsException。 */
  @Test
  void testConcurrentRootMkdirCollision() throws Exception {
    CloudPath path = CloudPath.of("/root_concurrent_dir");
    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger existsCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            executor.submit(
                () -> {
                  try {
                    barrier.await();
                    fileSystemService.mkdir(path, false);
                    successCount.incrementAndGet();
                  } catch (CloudNodeAlreadyExistsException e) {
                    existsCount.incrementAndGet();
                  } catch (Throwable t) {
                    unexpectedErrors.add(t);
                  }
                }));
      }

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one root mkdir should succeed");
      assertEquals(
          1, existsCount.get(), "Losing thread must receive CloudNodeAlreadyExistsException");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证根级别并发两线程创建同名 BLOB 节点时，恰有一线程成功，另一线程稳定抛出领域冲突异常 CloudNodeAlreadyExistsException。 */
  @Test
  void testConcurrentRootCreateBlobCollision() throws Exception {
    UUID blobId = seedActiveBlob("root concurrent blob data");
    CloudPath path = CloudPath.of("/root_concurrent_blob.png");
    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger existsCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            executor.submit(
                () -> {
                  try {
                    barrier.await();
                    fileSystemService.createBlobNode(path, blobId);
                    successCount.incrementAndGet();
                  } catch (CloudNodeAlreadyExistsException e) {
                    existsCount.incrementAndGet();
                  } catch (Throwable t) {
                    unexpectedErrors.add(t);
                  }
                }));
      }

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one root blob create should succeed");
      assertEquals(
          1, existsCount.get(), "Losing thread must receive CloudNodeAlreadyExistsException");
    } finally {
      executor.shutdownNow();
    }
  }

  /** 验证两个不同的根级别源节点并发移动到同一个根级别目标路径时，恰有一线程成功，另一线程稳定收到 CloudNodeAlreadyExistsException。 */
  @Test
  void testConcurrentRootMoveCollisionOnSameTarget() throws Exception {
    CloudPath src1 = CloudPath.of("/root_src1.txt");
    CloudPath src2 = CloudPath.of("/root_src2.txt");
    CloudPath target = CloudPath.of("/root_target.txt");
    fileSystemService.writeText(src1, "src1 content", 0L);
    fileSystemService.writeText(src2, "src2 content", 0L);

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger existsCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(src1, target, 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeAlreadyExistsException e) {
                  existsCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(src2, target, 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeAlreadyExistsException e) {
                  existsCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one root move should succeed");
      assertEquals(
          1, existsCount.get(), "Losing thread must receive CloudNodeAlreadyExistsException");
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * 验证交叉祖先移动并发时（初始 /a/d 与 /c/b，并发尝试 /a -> /c/b/a 与 /c -> /a/d/c）， 依靠逐段 root-to-leaf 获取行锁与端点
   * canonical 字典序比较，全局一致加锁杜绝死锁，且最终树不形成环并按串行化语义收敛。
   */
  @Test
  void testConcurrentCrossAncestorMovesNoDeadlockAndNoCycle() throws Exception {
    fileSystemService.mkdir(CloudPath.of("/a/d"), true);
    fileSystemService.mkdir(CloudPath.of("/c/b"), true);

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger notFoundCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      // 线程 1 尝试 /a -> /c/b/a
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(CloudPath.of("/a"), CloudPath.of("/c/b/a"), 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeNotFoundException e) {
                  notFoundCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
      // 线程 2 尝试 /c -> /a/d/c
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(CloudPath.of("/c"), CloudPath.of("/a/d/c"), 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeNotFoundException e) {
                  notFoundCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one cross-ancestor move must succeed");
      assertEquals(
          1, notFoundCount.get(), "Losing thread must fail with CloudNodeNotFoundException");

      // 树不变量断言：遍历 cloud_node 树全图，严格无环
      assertTreeAcyclic();

      // 串行化收敛断言：恰好收敛为一条合法链（/c/b/a/d 或 /a/d/c/b），另一根目录已被移走
      boolean path1Exists = fileSystemService.findNode(CloudPath.of("/c/b/a/d")).isPresent();
      boolean path2Exists = fileSystemService.findNode(CloudPath.of("/a/d/c/b")).isPresent();
      assertTrue(
          path1Exists ^ path2Exists, "Tree must converge to exactly one serialized linear chain");

      if (path1Exists) {
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/a")).isEmpty(),
            "/a should no longer exist at root");
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/c")).isPresent(), "/c must exist at root");
      } else {
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/c")).isEmpty(),
            "/c should no longer exist at root");
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/a")).isPresent(), "/a must exist at root");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * 针对标准 canonical 字符串字典序反例（/a! 与 /a/b 并发移动）的真实 PG 并发回归测试：
   *
   * <p>在标准 ASCII 字符串序中，'!' (33) < '/' (47)，导致 /a! < /a/b； 若以此字符串序决定加锁顺序， 并发尝试 move1 (/a! ->
   * /a/b/a!) 与 move2 (/a -> /a!/a) 时， T1 持有 /a! 并等待 /a，T2 持有 /a 并等待 /a!，导致经典 AB-BA 死锁。
   *
   * <p>通过分段序列层级全序（/a < /a/b < /a!），加锁顺序全局一致， 证明有界超时内绝不死锁，胜者单调收敛，落败方抛出
   * CloudNodeNotFoundException，最终树严格无环且合法串行收敛。
   */
  @Test
  void testConcurrentPunctuationSegmentMovesNoDeadlockAndNoCycle() throws Exception {
    fileSystemService.mkdir(CloudPath.of("/a/b"), true);
    fileSystemService.mkdir(CloudPath.of("/a!"), false);

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger notFoundCount = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      // 线程 1 尝试 /a! -> /a/b/a!
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(CloudPath.of("/a!"), CloudPath.of("/a/b/a!"), 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeNotFoundException e) {
                  notFoundCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
      // 线程 2 尝试 /a -> /a!/a
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.moveNode(CloudPath.of("/a"), CloudPath.of("/a!/a"), 0L);
                  successCount.incrementAndGet();
                } catch (CloudNodeNotFoundException e) {
                  notFoundCount.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      assertEquals(1, successCount.get(), "Exactly one cross-ancestor move must succeed");
      assertEquals(
          1, notFoundCount.get(), "Losing thread must fail with CloudNodeNotFoundException");

      // 树不变量断言：遍历 cloud_node 树全图，严格无环
      assertTreeAcyclic();

      // 串行化收敛断言：恰好收敛为一条合法链（/a/b/a! 或 /a!/a/b），另一被移走的节点不再位于根节点
      boolean path1Exists = fileSystemService.findNode(CloudPath.of("/a/b/a!")).isPresent();
      boolean path2Exists = fileSystemService.findNode(CloudPath.of("/a!/a/b")).isPresent();
      assertTrue(
          path1Exists ^ path2Exists, "Tree must converge to exactly one serialized linear chain");

      if (path1Exists) {
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/a!")).isEmpty(),
            "/a! should no longer exist at root");
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/a")).isPresent(), "/a must exist at root");
      } else {
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/a")).isEmpty(),
            "/a should no longer exist at root");
        assertTrue(
            fileSystemService.findNode(CloudPath.of("/a!")).isPresent(), "/a! must exist at root");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * 验证并发创建文本（expectedRevision=0）与目录时，若目录创建成功，落败的文本创建稳定抛出 CloudNodeKindConflictException 而非 revision
   * conflict。
   */
  @Test
  void testConcurrentWriteTextExpectedRevisionZeroVsDirectoryCreate() throws Exception {
    CloudPath path = CloudPath.of("/concurrent_node");

    int threadCount = 2;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    try {
      CyclicBarrier barrier = new CyclicBarrier(threadCount);
      AtomicInteger dirSuccess = new AtomicInteger(0);
      AtomicInteger textSuccess = new AtomicInteger(0);
      AtomicInteger kindConflict = new AtomicInteger(0);
      AtomicInteger alreadyExists = new AtomicInteger(0);
      List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

      List<Future<?>> futures = new ArrayList<>();
      // Thread 1: mkdir
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.mkdir(path, false);
                  dirSuccess.incrementAndGet();
                } catch (CloudNodeAlreadyExistsException e) {
                  alreadyExists.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));
      // Thread 2: writeText(expectedRevision=0)
      futures.add(
          executor.submit(
              () -> {
                try {
                  barrier.await();
                  fileSystemService.writeText(path, "text content", 0L);
                  textSuccess.incrementAndGet();
                } catch (CloudNodeKindConflictException e) {
                  assertEquals(CloudNodeKind.TEXT, e.getExpectedKind());
                  assertEquals(CloudNodeKind.DIRECTORY, e.getActualKind());
                  kindConflict.incrementAndGet();
                } catch (Throwable t) {
                  unexpectedErrors.add(t);
                }
              }));

      for (Future<?> f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertTrue(unexpectedErrors.isEmpty(), () -> "Unexpected errors: " + unexpectedErrors);
      if (dirSuccess.get() == 1) {
        assertEquals(
            1,
            kindConflict.get(),
            "When directory wins, writeText(expectedRevision=0) must throw CloudNodeKindConflictException");
      } else {
        assertEquals(1, textSuccess.get(), "When writeText wins, textSuccess must be 1");
        assertEquals(
            1,
            alreadyExists.get(),
            "When writeText wins, mkdir must throw CloudNodeAlreadyExistsException");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private void assertTreeAcyclic() {
    List<Map<String, Object>> rows = jdbc.queryForList("select id, parent_id from cloud_node");
    Map<UUID, UUID> parentMap = new HashMap<>();
    for (Map<String, Object> row : rows) {
      UUID id = (UUID) row.get("id");
      UUID parentId = (UUID) row.get("parent_id");
      parentMap.put(id, parentId);
    }

    for (UUID id : parentMap.keySet()) {
      Set<UUID> visited = new HashSet<>();
      UUID curr = id;
      while (curr != null) {
        UUID node = curr;
        assertTrue(visited.add(node), () -> "Detected cycle in cloud_node hierarchy at id " + node);
        curr = parentMap.get(curr);
      }
    }
  }
}
