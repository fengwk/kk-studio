package fun.fengwk.kkstudio.platform.canvas.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.storage.InMemoryS3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobPreviewService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 真实 PG/Storage 事务，仅 S3 使用内存假件；确定性交错 stage 与 lease 接管，验证输出围栏与引用守恒。 */
@Import(StorageS3TestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CanvasBlobResourceMaterializerIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private CanvasResourceMaterializer materializer;
  @Autowired private CanvasCommandService commands;
  @Autowired private CanvasFunctionWorkStore workStore;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InMemoryS3StorageService s3;
  @MockitoBean private StorageMaintenance maintenance;
  @MockitoBean private StorageBlobPreviewService previews;

  private UUID canvasId;
  private UUID nodeId;
  private UUID requestId;
  private UUID resourceId;
  private ClaimedRun claim;

  @BeforeEach
  void createRunningRequest() {
    s3.clear();
    canvasId = commands.createCanvas("materialize-fence").id();
    nodeId = UUID.randomUUID();
    requestId = UUID.randomUUID();
    resourceId = UUID.randomUUID();
    jdbc.update(
        "insert into canvas_node (id, canvas_id, name, x, y, width, height)"
            + " values (?, ?, 'function', 0, 0, 100, 100)",
        nodeId,
        canvasId);
    jdbc.update(
        "insert into canvas_function_run"
            + " (node_id, request_id, status, available_at, state_json)"
            + " values (?, ?, 'READY', clock_timestamp() - interval '1 second',"
            + " '{\"stage\":\"QUEUED\"}')",
        nodeId,
        requestId);
    claim = workStore.claimNext(databaseNow(), Duration.ofMinutes(5), "owner-a").orElseThrow();
  }

  /** A 已进入读流、尚未提交；B 真实 claim 同 request，A 必须失败并释放 upload，B 随后正常物化。 */
  @Test
  void takeoverDuringStageRejectsOldOwnerAndCleansUpload() throws Exception {
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var old = executor.submit(() -> blob(blockingStream(reading, resume)));
      try {
        assertTrue(reading.await(10, TimeUnit.SECONDS));
        expireLease();
        ClaimedRun replacement =
            workStore.claimNext(databaseNow(), Duration.ofMinutes(5), "owner-b").orElseThrow();
        assertEquals(requestId, replacement.requestId());
        resume.countDown();
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> old.get(20, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertNoOutputAndDiscardedUpload();
        claim = replacement;
        CanvasResource resource = blob(new ByteArrayInputStream(new byte[] {2}));
        assertEquals(resourceId, resource.id());
        assertEquals(1L, count("canvas_resource"));
        assertEquals(1L, count("canvas_function_resource_pin"));
        assertEquals(
            1L,
            jdbc.queryForObject(
                "select ref_count from storage_blob where id = ?", Long.class, resource.blobId()));
        assertTrue(
            s3.networkCalls().stream()
                .noneMatch(InMemoryS3StorageService.NetworkCall::transactionActive));
      } finally {
        resume.countDown();
      }
    }
  }

  private CanvasResource blob(InputStream stream) {
    return materializer.materializeBlob(
        canvasId, nodeId, requestId, claim.leaseToken(), resourceId, "output.bin", stream);
  }

  /** 未被接管的过期 lease、取消及新 request 替换同样必须在 stage 后拒绝，并释放实际 upload 引用。 */
  @ParameterizedTest
  @ValueSource(strings = {"expired", "cancelled", "replaced"})
  void invalidationDuringStageDiscardsUpload(String change) throws Exception {
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var old = executor.submit(() -> blob(blockingStream(reading, resume)));
      try {
        assertTrue(reading.await(10, TimeUnit.SECONDS));
        invalidate(change);
        resume.countDown();
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> old.get(20, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertNoOutputAndDiscardedUpload();
      } finally {
        resume.countDown();
      }
    }
  }

  /** TEXT 写入和 winner 返回均不能绕过过期、取消、replacement 与 canvas 归属围栏。 */
  @ParameterizedTest
  @ValueSource(strings = {"expired", "cancelled", "replaced", "wrong-canvas"})
  void textAndBlobWinnerRequireLiveOwnership(String change) {
    text("cached");
    if (change.equals("wrong-canvas")) {
      canvasId = commands.createCanvas("other-canvas").id();
    } else {
      invalidate(change);
    }
    assertThrows(IllegalStateException.class, () -> text("new"));
    assertThrows(IllegalStateException.class, () -> blob(new ByteArrayInputStream(new byte[] {2})));
    assertEquals(1L, count("canvas_resource"));
    assertEquals(change.equals("replaced") ? 0L : 1L, count("canvas_function_resource_pin"));
    assertEquals(0L, count("storage_upload"));
  }

  /** 同 request 新 lease 可消费旧 lease 已完成的资源；旧 lease 即便命中 winner 也必须拒绝。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void newLeaseConsumesExistingWinnerWithoutStagingOrDuplicatePins(boolean media) {
    CanvasResource winner = media ? blob(new ByteArrayInputStream(new byte[] {1})) : text("cached");
    String oldToken = claim.leaseToken();
    expireLease();
    claim = workStore.claimNext(databaseNow(), Duration.ofMinutes(5), "owner-b").orElseThrow();
    assertThrows(
        IllegalStateException.class,
        () ->
            materializer.materializeText(
                canvasId, nodeId, requestId, oldToken, resourceId, "old", "old"));
    InputStream unreadable =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("winner must not read content");
          }
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            materializer.materializeBlob(
                canvasId, nodeId, requestId, oldToken, resourceId, "old", unreadable));
    assertEquals(winner.blobId(), blob(unreadable).blobId());
    assertEquals(winner.name(), text("ignored").name());
    assertEquals(winner.textContent(), text("ignored").textContent());
    assertEquals(1L, count("canvas_function_resource_pin"));
    assertEquals(media ? 1L : 0L, count("storage_upload"));
    if (media) {
      assertEquals(1L, jdbc.queryForObject("select ref_count from storage_blob", Long.class));
    } else {
      assertEquals(0L, count("storage_blob"));
    }
  }

  /** 没有既有 Resource 时 TEXT 也必须先检查 ownership，不能写 Resource 或占位 pin。 */
  @ParameterizedTest
  @ValueSource(strings = {"expired", "cancelled", "replaced"})
  void textCreationRejectsInvalidOwnership(String change) {
    invalidate(change);
    assertThrows(IllegalStateException.class, () -> text("new"));
    assertEquals(0L, count("canvas_resource"));
    assertEquals(0L, count("canvas_function_resource_pin"));
    assertEquals(0L, count("storage_upload"));
  }

  private CanvasResource text(String content) {
    return materializer.materializeText(
        canvasId, nodeId, requestId, claim.leaseToken(), resourceId, "report", content);
  }

  private void invalidate(String change) {
    switch (change) {
      case "expired" -> expireLease();
      case "cancelled" -> jdbc.update(
          "update canvas_function_run set status = 'CANCELLED', lease_token = null,"
              + " lease_until = null where node_id = ?",
          nodeId);
      case "replaced" -> {
        // 真实 FK 不允许替换仍有 pin 的 run；模拟 lifecycle 先清旧 pin，再替换 request。
        jdbc.update("delete from canvas_function_resource_pin where node_id = ?", nodeId);
        jdbc.update(
            "update canvas_function_run set request_id = ? where node_id = ?",
            UUID.randomUUID(),
            nodeId);
      }
      default -> throw new IllegalArgumentException(change);
    }
  }

  private void expireLease() {
    jdbc.update(
        "update canvas_function_run set lease_until = clock_timestamp() - interval '1 second'"
            + " where node_id = ?",
        nodeId);
  }

  private Instant databaseNow() {
    return jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  private void assertNoOutputAndDiscardedUpload() {
    assertEquals(0L, count("canvas_resource"));
    assertEquals(0L, count("canvas_function_resource_pin"));
    assertEquals(1L, count("storage_upload"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from storage_upload where cleanup_requested_at is not null",
            Long.class));
    assertEquals(0L, jdbc.queryForObject("select sum(ref_count) from storage_blob", Long.class));
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private static InputStream blockingStream(CountDownLatch reading, CountDownLatch resume) {
    return new InputStream() {
      private boolean consumed;

      @Override
      public int read() throws IOException {
        if (consumed) {
          return -1;
        }
        assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());
        reading.countDown();
        try {
          if (!resume.await(15, TimeUnit.SECONDS)) {
            throw new IOException("stage was not resumed");
          }
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IOException(failure);
        }
        consumed = true;
        return 1;
      }
    };
  }
}
