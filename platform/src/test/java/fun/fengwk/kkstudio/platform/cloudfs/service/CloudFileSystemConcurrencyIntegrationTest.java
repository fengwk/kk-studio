package fun.fengwk.kkstudio.platform.cloudfs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudDirectoryNotEmptyException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeAlreadyExistsException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.storage.S3PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
}
