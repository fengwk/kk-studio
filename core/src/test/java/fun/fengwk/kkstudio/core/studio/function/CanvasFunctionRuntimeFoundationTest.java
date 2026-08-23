package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasCommand;
import fun.fengwk.kkstudio.canvas.CanvasCommandService;
import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasQueryService;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceNode;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasSnapshot;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionParameterDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.impl.PostgresqlStorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.core.storage.service.model.StorageBlobState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** PostgreSQL 上验证 start freeze、每节点串行、CAS terminal apply 与 document version 前进。 */
@Import(CanvasFunctionRuntimeFoundationTest.TestAdapterConfiguration.class)
@TestPropertySource(properties = "kk-studio.test.canvas-function-foundation=true")
class CanvasFunctionRuntimeFoundationTest extends PostgresSpringTestSupport {

  private static final String REQUEST_1 = "00000000-0000-0000-0000-000000000101";
  private static final String REQUEST_2 = "00000000-0000-0000-0000-000000000102";
  private static final String REQUEST_3 = "00000000-0000-0000-0000-000000000103";
  private static final String REQUEST_4 = "00000000-0000-0000-0000-000000000104";
  private static final CanvasTransform TRANSFORM = new CanvasTransform(0, 0, 100, 80);
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          "test-image",
          "Test Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of(
              CanvasFunctionParameterDefinition.enumParameter(
                  "ratio", "Ratio", false, "AUTO", List.of("AUTO", "1:1"))));
  private static final CanvasFunctionModel REJECT_MODEL =
      new CanvasFunctionModel(
          "test-reject",
          "Test Reject",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of());

  @Autowired private CanvasCommandService commandService;
  @Autowired private CanvasQueryService queryService;
  @Autowired private CanvasResourceRepository resourceRepository;
  @Autowired private CanvasFunctionRunRepository runRepository;
  @Autowired private CanvasFunctionResourcePinRepository refRepository;
  @Autowired private StorageBlobRepository blobRepository;
  @Autowired private CanvasFunctionRunTransactions transactions;
  @Autowired private CanvasFunctionModelRegistry registry;
  @Autowired private CanvasFunctionRunStateCodec stateCodec;
  @Autowired private PlatformTransactionManager transactionManager;

  /** 相同 requestId exact replay；不同并发 request 只有一个成功；终态允许新 request 覆盖；每次状态前进 bump version。 */
  @Test
  void serializesStartsAndKeepsRequestIdIdempotent() throws Exception {
    CanvasDocument canvas = commandService.createCanvas("starts");
    UUID nodeId = createFunction(canvas, "function", config("prompt", "{}"));
    long versionAfterCreate = version(canvas);

    CanvasFunctionRun first = transactions.start(canvas.id(), nodeId, REQUEST_1).run();
    assertEquals(CanvasFunctionRunStatus.RUNNING, first.status());
    assertEquals(versionAfterCreate + 1L, version(canvas));
    assertEquals(
        1,
        refRepository.findByRun(canvas.id(), nodeId, first.requestId()).stream()
            .filter(ref -> ref.role() == CanvasFunctionResourcePin.Role.OUTPUT)
            .count());

    CanvasFunctionRun replay = transactions.start(canvas.id(), nodeId, REQUEST_1).run();
    assertEquals(first.requestId(), replay.requestId());
    assertEquals(versionAfterCreate + 1L, version(canvas), "exact replay must not bump version");
    assertThrows(
        CanvasFunctionRunException.class, () -> transactions.start(canvas.id(), nodeId, REQUEST_2));

    CanvasFunctionRun cancelled = transactions.cancel(canvas.id(), nodeId, REQUEST_1);
    assertEquals(CanvasFunctionRunStatus.CANCELLED, cancelled.status());
    assertEquals(versionAfterCreate + 2L, version(canvas));
    CanvasFunctionRun terminalReplay = transactions.start(canvas.id(), nodeId, REQUEST_1).run();
    assertEquals(CanvasFunctionRunStatus.CANCELLED, terminalReplay.status());
    CanvasFunctionRun replacement = transactions.start(canvas.id(), nodeId, REQUEST_2).run();
    assertNotEquals(first.requestId(), replacement.requestId());
    assertEquals(versionAfterCreate + 3L, version(canvas));
    assertTrue(
        runRepository.findByNodeId(nodeId).orElseThrow().status()
            == CanvasFunctionRunStatus.RUNNING);

    CanvasDocument raceCanvas = commandService.createCanvas("race");
    UUID raceNode = createFunction(raceCanvas, "race-function", config("prompt", "{}"));
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> left =
          executor.submit(() -> raceStart(start, raceCanvas.id(), raceNode, REQUEST_3));
      Future<Object> right =
          executor.submit(() -> raceStart(start, raceCanvas.id(), raceNode, REQUEST_4));
      start.countDown();
      List<Object> results = List.of(left.get(), right.get());
      assertEquals(
          1L, results.stream().filter(CanvasFunctionStartResult.class::isInstance).count());
      assertEquals(
          1L, results.stream().filter(CanvasFunctionRunException.class::isInstance).count());
    } finally {
      executor.shutdownNow();
    }
  }

  /** 参数/模型/适配器 preflight 在命令或 start 阶段 fail-closed。 */
  @Test
  void rejectsInvalidParametersModelsAndPreflight() {
    CanvasDocument canvas = commandService.createCanvas("validation");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                canvas,
                new CanvasCommand.CreateFunctionNode(
                    UUID.randomUUID(),
                    "bad-params",
                    MODEL.key(),
                    "{\"ratio\":\"wide\"}",
                    TRANSFORM)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            apply(
                canvas,
                new CanvasCommand.CreateFunctionNode(
                    UUID.randomUUID(), "unknown-model", "no-such-model", "{}", TRANSFORM)));
    UUID rejectNode =
        createFunction(canvas, "preflight-reject", config("prompt", "{}"), REJECT_MODEL);
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.start(canvas.id(), rejectNode, REQUEST_1));
  }

  /** checkpoint/cancel/失败/迟到结果/节点删除均只按 node+request+RUNNING CAS 收敛；终态转换 bump version。 */
  @Test
  void terminalApplyIsCasAndBumpsVersion() {
    CanvasDocument canvas = commandService.createCanvas("terminal");
    UUID nodeId = createFunction(canvas, "output", config("prompt", "{}"));
    long versionAfterCreate = version(canvas);

    CanvasFunctionRun first = transactions.start(canvas.id(), nodeId, REQUEST_1).run();
    CanvasFunctionFrozenRun firstFrozen = decode(first);
    transactions.cancel(canvas.id(), nodeId, REQUEST_1);
    assertFalse(transactions.completeSuccess(firstFrozen, List.of(firstFrozen.targetResourceId())));
    assertEquals(
        CanvasFunctionRunStatus.CANCELLED,
        runRepository.findByNodeId(nodeId).orElseThrow().status());

    CanvasFunctionRun failed = transactions.start(canvas.id(), nodeId, REQUEST_2).run();
    assertTrue(transactions.failIfRunning(nodeId, REQUEST_2, "safe failure"));
    assertEquals(
        CanvasFunctionRunStatus.FAILED, runRepository.findByNodeId(nodeId).orElseThrow().status());
    assertFalse(
        transactions.completeSuccess(decode(failed), List.of(decode(failed).targetResourceId())));
    assertEquals(
        versionAfterCreate + 4L,
        version(canvas),
        "start + cancel + replacement start + fail each bump once");

    CanvasFunctionRun deleted = transactions.start(canvas.id(), nodeId, REQUEST_3).run();
    CanvasFunctionFrozenRun deletedFrozen = decode(deleted);
    apply(canvas, new CanvasCommand.DeleteNode(nodeId));
    assertFalse(
        transactions.completeSuccess(deletedFrozen, List.of(deletedFrozen.targetResourceId())));
    assertTrue(runRepository.findByNodeId(nodeId).isEmpty());
  }

  /** INPUT pin 在源节点删除后保留无 owner Resource；删除最后一个引用 Run 后 Resource 与 Blob 一并回收。 */
  @Test
  void inputPinKeepsDeletedSourceResourceUntilRunIsReleased() {
    CanvasDocument canvas = commandService.createCanvas("input-pins");
    UUID sourceNodeId = createFunction(canvas, "source", config("source", "{}"));
    CanvasResource source = addBlobResource(canvas.id(), sourceNodeId, 0, UUID.randomUUID());
    UUID targetNodeId =
        createFunction(canvas, "target", configWithReference("target", sourceNodeId, 0, "{}"));
    apply(canvas, new CanvasCommand.CreateLink(sourceNodeId, targetNodeId));
    CanvasFunctionRun run = transactions.start(canvas.id(), targetNodeId, REQUEST_1).run();

    apply(canvas, new CanvasCommand.DeleteNode(sourceNodeId));

    CanvasResource pinned = resourceRepository.findById(canvas.id(), source.id()).orElseThrow();
    assertEquals(null, pinned.ownerNodeId());
    assertEquals(null, pinned.resourceIndex());
    assertEquals(1L, blobRepository.getById(source.blobId()).getRefCount());
    assertEquals(
        1,
        refRepository.findByRun(canvas.id(), targetNodeId, run.requestId()).stream()
            .filter(ref -> ref.role() == CanvasFunctionResourcePin.Role.INPUT)
            .count());

    apply(canvas, new CanvasCommand.DeleteNode(targetNodeId));

    assertTrue(resourceRepository.findById(canvas.id(), source.id()).isEmpty());
    assertEquals(null, blobRepository.getById(source.blobId()));
  }

  /** 失败保留旧输出并清理未挂接目标；成功才原子替换 owner，同时释放旧 Blob。 */
  @Test
  void outputTargetIsAttachedOnlyBySuccessfulTerminalSwap() {
    CanvasDocument canvas = commandService.createCanvas("output-swap");
    UUID nodeId = createFunction(canvas, "output", config("prompt", "{}"));
    CanvasResource old = addBlobResource(canvas.id(), nodeId, 0, UUID.randomUUID());

    CanvasFunctionRun failed = transactions.start(canvas.id(), nodeId, REQUEST_1).run();
    CanvasFunctionFrozenRun failedFrozen = decode(failed);
    CanvasResource discarded =
        addBlobResource(canvas.id(), null, null, failedFrozen.targetResourceId());
    assertTrue(transactions.failIfRunning(nodeId, REQUEST_1, "safe failure"));

    assertEquals(
        List.of(old.id()),
        snapshot(canvas.id()).nodes().stream()
            .filter(node -> node.id().equals(nodeId))
            .findFirst()
            .orElseThrow()
            .resources()
            .stream()
            .map(CanvasResource::id)
            .toList());
    assertTrue(resourceRepository.findById(canvas.id(), discarded.id()).isEmpty());
    assertEquals(null, blobRepository.getById(discarded.blobId()));

    CanvasFunctionRun succeeded = transactions.start(canvas.id(), nodeId, REQUEST_2).run();
    CanvasFunctionFrozenRun succeededFrozen = decode(succeeded);
    CanvasResource generated =
        addBlobResource(canvas.id(), null, null, succeededFrozen.targetResourceId());
    assertTrue(
        transactions.completeSuccess(succeededFrozen, List.of(succeededFrozen.targetResourceId())));

    CanvasResource attached =
        resourceRepository.findById(canvas.id(), generated.id()).orElseThrow();
    assertEquals(nodeId, attached.ownerNodeId());
    assertEquals(0, attached.resourceIndex());
    assertTrue(resourceRepository.findById(canvas.id(), old.id()).isEmpty());
    assertEquals(null, blobRepository.getById(old.blobId()));
    assertEquals(1L, blobRepository.getById(generated.blobId()).getRefCount());
  }

  /** checkpoint 成功：run stage/state 与 document version 在同一事务提交，并可由权威 Snapshot 读取。 */
  @Test
  void checkpointAdvancesRunVersionAndUpdatesSnapshotInOneTransaction() {
    CanvasDocument canvas = commandService.createCanvas("checkpoint-transaction");
    UUID nodeId = createFunction(canvas, "output", config("prompt", "{}"));
    transactions.start(canvas.id(), nodeId, REQUEST_1);
    long versionAfterStart = version(canvas);

    CanvasFunctionFrozenRun next =
        transactions.checkpoint(
            canvas.id(), nodeId, REQUEST_1, "CHECKPOINTED", Map.of("jobId", "job"));

    assertEquals("CHECKPOINTED", next.stage());
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.RUNNING, current.status());
    CanvasFunctionFrozenRun decoded = decode(current);
    assertEquals("CHECKPOINTED", decoded.stage());
    assertEquals(Map.of("jobId", "job"), decoded.adapterState());
    assertEquals(versionAfterStart + 1L, version(canvas));
    CanvasResourceNode projected =
        queryService.findSnapshot(canvas.id()).orElseThrow().nodes().stream()
            .filter(node -> node.id().equals(nodeId))
            .findFirst()
            .orElseThrow();
    assertEquals(nodeId, projected.id());
    assertEquals("CHECKPOINTED", projected.run().stage());
  }

  /** 已取消 run 的迟到 checkpoint：内部取消信号且不产生任何版本变更。 */
  @Test
  void checkpointOnCancelledRunLeavesVersionUntouched() {
    CanvasDocument canvas = commandService.createCanvas("checkpoint-cancelled");
    UUID nodeId = createFunction(canvas, "output", config("prompt", "{}"));
    transactions.start(canvas.id(), nodeId, REQUEST_1);
    transactions.cancel(canvas.id(), nodeId, REQUEST_1);
    long versionAfterCancel = version(canvas);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () ->
            transactions.checkpoint(
                canvas.id(), nodeId, REQUEST_1, "LATE", Map.of("jobId", "job")));

    assertEquals(versionAfterCancel, version(canvas));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals(CanvasFunctionRunStatus.CANCELLED, current.status());
    assertEquals("CANCELLED", current.stage());
  }

  /** 外层事务异常回滚：checkpoint 内的 run 写入与 version 前进全部不生效。 */
  @Test
  void checkpointRollbackDoesNotBumpVersion() {
    CanvasDocument canvas = commandService.createCanvas("checkpoint-rollback");
    UUID nodeId = createFunction(canvas, "output", config("prompt", "{}"));
    transactions.start(canvas.id(), nodeId, REQUEST_1);
    long versionAfterStart = version(canvas);

    TransactionTemplate template = new TransactionTemplate(transactionManager);
    try {
      template.executeWithoutResult(
          status -> {
            CanvasFunctionFrozenRun next =
                transactions.checkpoint(
                    canvas.id(), nodeId, REQUEST_1, "INNER", Map.of("step", 1L));
            assertEquals("INNER", next.stage());
            throw new IllegalStateException("rollback checkpoint along with outer transaction");
          });
    } catch (IllegalStateException expected) {
      // 预期：rollback 驱动断言。
    }

    assertEquals(versionAfterStart, version(canvas));
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElseThrow();
    assertEquals("QUEUED", current.stage());
    assertFalse(current.stateJson().contains("\"step\""));
  }

  private Object raceStart(CountDownLatch start, UUID canvasId, UUID nodeId, String requestId)
      throws InterruptedException {
    start.await();
    try {
      return transactions.start(canvasId, nodeId, requestId);
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private CanvasFunctionFrozenRun decode(CanvasFunctionRun run) {
    return stateCodec.decode(
        run.stateJson(), registry.require(stateCodec.modelKey(run.stateJson())).model());
  }

  private UUID createFunction(CanvasDocument canvas, String name, String config) {
    return createFunction(canvas, name, config, MODEL);
  }

  private UUID createFunction(
      CanvasDocument canvas, String name, String config, CanvasFunctionModel model) {
    UUID nodeId = UUID.randomUUID();
    apply(
        canvas, new CanvasCommand.CreateFunctionNode(nodeId, name, model.key(), config, TRANSFORM));
    return nodeId;
  }

  private CanvasResource addBlobResource(
      UUID canvasId, UUID ownerNodeId, Integer resourceIndex, UUID resourceId) {
    UUID blobId = UUID.randomUUID();
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSha256(String.format("%064x", blobId.getLeastSignificantBits() & Long.MAX_VALUE));
    blob.setSizeBytes(3L);
    blob.setMediaType("image/png");
    blob.setWidth(1L);
    blob.setHeight(1L);
    blob.setRefCount(1L);
    blob.setState(StorageBlobState.ACTIVE);
    assertTrue(blobRepository.insertActiveCandidate(blob));
    CanvasResource resource =
        new CanvasResource(
            resourceId,
            canvasId,
            ownerNodeId,
            resourceIndex,
            blobId,
            "resource.png",
            null,
            Instant.now());
    resourceRepository.add(resource);
    return resource;
  }

  private long version(CanvasDocument canvas) {
    return snapshot(canvas.id()).document().version();
  }

  private CanvasSnapshot snapshot(UUID canvasId) {
    return queryService.findSnapshot(canvasId).orElseThrow();
  }

  private CanvasSnapshot apply(CanvasDocument canvas, CanvasCommand command) {
    CanvasSnapshot before = snapshot(canvas.id());
    commandService.applyCommands(
        canvas.id(), before.document().version(), UUID.randomUUID(), List.of(command));
    return snapshot(canvas.id());
  }

  private static String config(String promptText, String parameters) {
    return "{\"prompt\":{\"segments\":[{\"type\":\"TEXT\",\"text\":\""
        + promptText
        + "\"}]},\"parameters\":"
        + parameters
        + "}";
  }

  private static String configWithReference(
      String promptText, UUID sourceNodeId, int resourceIndex, String parameters) {
    return "{\"prompt\":{\"segments\":[{\"type\":\"TEXT\",\"text\":\""
        + promptText
        + "\"},{\"type\":\"REFERENCE\",\"nodeId\":\""
        + sourceNodeId
        + "\",\"index\":"
        + resourceIndex
        + "}]},\"parameters\":"
        + parameters
        + "}";
  }

  @TestConfiguration
  @ConditionalOnProperty(name = "kk-studio.test.canvas-function-foundation", havingValue = "true")
  static class TestAdapterConfiguration {

    @Bean
    CanvasFunctionAdapter testFoundationAdapter() {
      return new CanvasFunctionAdapter() {
        @Override
        public List<CanvasFunctionModel> models() {
          return List.of(MODEL, REJECT_MODEL);
        }

        @Override
        public boolean enabled() {
          return true;
        }

        @Override
        public String unavailableReason() {
          return null;
        }

        @Override
        public void preflight(CanvasFunctionFrozenRun run) {
          if (REJECT_MODEL.key().equals(run.model().key())) {
            throw new IllegalArgumentException("test preflight rejects this model");
          }
        }

        @Override
        public List<UUID> execute(
            CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
          throw new UnsupportedOperationException("foundation test never executes");
        }
      };
    }

    @Bean
    @Primary
    StorageBlobManager testCanvasStorageBlobManager(StorageBlobRepository blobRepository) {
      return new PostgresqlStorageBlobManager(
          blobRepository, mock(S3StorageService.class), mock(S3PresignService.class));
    }
  }
}
