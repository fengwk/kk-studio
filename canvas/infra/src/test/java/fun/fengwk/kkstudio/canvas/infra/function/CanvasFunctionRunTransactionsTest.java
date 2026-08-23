package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfigCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Transactional checkpoint 单元覆盖：成功一次事务内完成 run CAS 与 canvas version 前进；run CAS 失败、run 不再是该请求
 * RUNNING、节点消失、非法 stage/超大 adapterState 一律零版本变更。
 */
class CanvasFunctionRunTransactionsTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID OTHER_REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
  private static final int MAX_ADAPTER_STATE_BYTES = 64 * 1024;
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          "test-image",
          "Test Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of());

  private CanvasStore canvasStore;
  private CanvasResourceRepository resourceRepository;
  private CanvasFunctionRunRepository runRepository;
  private CanvasFunctionRunTransactions transactions;
  private CanvasFunctionRunStateCodecPort stateCodec;
  private CanvasFunctionBlobAccess blobAccess;
  private CanvasResourceLifecycle resourceLifecycle;
  private CanvasFunctionModelRegistry registry;
  private CanvasFunctionAdapter adapter;
  private CanvasFixture fixture;

  @BeforeEach
  void setUp() {
    canvasStore = mock(CanvasStore.class);
    resourceRepository = mock(CanvasResourceRepository.class);
    runRepository = mock(CanvasFunctionRunRepository.class);
    CanvasFunctionResourcePinRepository refRepository =
        mock(CanvasFunctionResourcePinRepository.class);
    registry = mock(CanvasFunctionModelRegistry.class);
    adapter = mock(CanvasFunctionAdapter.class);
    when(registry.require(MODEL.key()))
        .thenReturn(new CanvasFunctionCatalog.RegisteredModel(MODEL, adapter));
    CanvasFunctionConfigCodecPort configCodec = new CanvasFunctionConfigCodec(new ObjectMapper());
    stateCodec = new CanvasFunctionRunStateCodec(new ObjectMapper(), configCodec);
    resourceLifecycle = mock(CanvasResourceLifecycle.class);
    blobAccess = mock(CanvasFunctionBlobAccess.class);
    transactions =
        new CanvasFunctionRunTransactions(
            canvasStore,
            resourceRepository,
            runRepository,
            refRepository,
            registry,
            configCodec,
            stateCodec,
            resourceLifecycle,
            blobAccess,
            Clock.fixed(NOW, ZoneOffset.UTC));
    fixture = new CanvasFixture(stateCodec);
    when(canvasStore.lockDocument(CANVAS)).thenReturn(Optional.of(fixture.document));
    when(canvasStore.lockNode(CANVAS, NODE)).thenReturn(Optional.of(fixture.node));
    when(canvasStore.findNode(CANVAS, NODE)).thenReturn(Optional.of(fixture.node));
    when(resourceRepository.findByOwnerNode(CANVAS, NODE)).thenReturn(List.of());
    when(runRepository.findByNodeIdForUpdate(NODE)).thenReturn(fixture.runningRun());
    when(runRepository.findByNodeId(NODE)).thenReturn(fixture.runningRun());
  }

  @Test
  void checkpointPersistsRunAndBumpsVersion() {
    when(runRepository.checkpoint(eq(NODE), eq(REQUEST), any(), eq("SUBMITTING"), eq(NOW)))
        .thenReturn(true);
    when(canvasStore.advanceDocumentVersion(CANVAS, 5L, 6L)).thenReturn(true);

    CanvasFunctionFrozenRun next =
        transactions.checkpoint(
            CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of("jobId", "job"));

    assertEquals("SUBMITTING", next.stage());
    assertEquals(Map.of("jobId", "job"), next.adapterState());
    verify(runRepository).checkpoint(eq(NODE), eq(REQUEST), any(), eq("SUBMITTING"), eq(NOW));
    verify(canvasStore).advanceDocumentVersion(CANVAS, 5L, 6L);
  }

  @Test
  void checkpointCasFailureThrowsInternalCancellationWithoutVersionChange() {
    when(runRepository.checkpoint(any(), any(), any(), any(), any())).thenReturn(false);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    verify(canvasStore, never()).advanceDocumentVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointAfterTerminalOrDifferentRequestThrowsWithoutAnyChange() {
    CanvasFunctionRun terminal =
        new CanvasFunctionRun(
            NODE,
            OTHER_REQUEST,
            CanvasFunctionRunStatus.CANCELLED,
            "CANCELLED",
            stateCodec.encode(fixture.frozen("CANCELLED", Map.of())),
            null,
            NOW);
    when(runRepository.findByNodeIdForUpdate(NODE)).thenReturn(Optional.of(terminal));

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "LATE", Map.of()));
    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, OTHER_REQUEST.toString(), "LATE", Map.of()));

    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(canvasStore, never()).advanceDocumentVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointMissingNodeThrowsInternalCancellationWithoutVersionChange() {
    when(canvasStore.lockNode(CANVAS, NODE)).thenReturn(Optional.empty());

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    verify(runRepository, never()).findByNodeIdForUpdate(any());
    verify(canvasStore, never()).advanceDocumentVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointMissingDocumentThrowsInternalCancellationWithoutWrite() {
    when(canvasStore.lockDocument(CANVAS)).thenReturn(Optional.empty());

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    // 零写：不触碰 node/run 行，也不前进 version。
    verify(canvasStore, never()).lockNode(any(), any());
    verify(runRepository, never()).findByNodeIdForUpdate(any());
    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(canvasStore, never()).advanceDocumentVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointRejectsOversizedAdapterStateBeforeAnyWrite() {
    Map<String, Object> oversized = Map.of("payload", "x".repeat(MAX_ADAPTER_STATE_BYTES + 1));

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", oversized));

    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(canvasStore, never()).advanceDocumentVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointRejectsInvalidStageBeforeAnyWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "lower case", Map.of()));

    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(canvasStore, never()).advanceDocumentVersion(any(), anyLong(), anyLong());
  }

  /** failure 只对同 request 的 RUNNING 生效，并在 terminal CAS 后清理未挂接目标与前进 version。 */
  @Test
  void failIfRunningTransitionsMatchingRunAndIgnoresStaleObservation() {
    when(runRepository.transitionTerminal(any())).thenReturn(true);
    when(canvasStore.advanceDocumentVersion(CANVAS, 5L, 6L)).thenReturn(true);

    assertTrue(transactions.failIfRunning(NODE, REQUEST.toString(), "safe failure"));

    verify(runRepository).transitionTerminal(any());
    verify(canvasStore).advanceDocumentVersion(CANVAS, 5L, 6L);

    when(runRepository.findByNodeId(NODE)).thenReturn(Optional.empty());
    assertFalse(transactions.failIfRunning(NODE, REQUEST.toString(), "ignored"));

    when(runRepository.findByNodeId(NODE)).thenReturn(fixture.runningRun());
    when(canvasStore.lockNode(CANVAS, NODE)).thenReturn(Optional.empty());
    assertFalse(transactions.failIfRunning(NODE, REQUEST.toString(), "missing node"));
  }

  /** success 必须精确接受预分配目标，且 Blob kind 与 frozen output kind 一致。 */
  @Test
  void completeSuccessRejectsWrongTargetAndBlobKind() {
    CanvasFunctionFrozenRun frozen = fixture.frozen("QUEUED", Map.of());
    assertThrows(
        IllegalArgumentException.class, () -> transactions.completeSuccess(frozen, List.of()));

    UUID blobId = UUID.randomUUID();
    CanvasResource output =
        new CanvasResource(TARGET, CANVAS, null, null, blobId, "output.png", null, NOW);
    when(resourceRepository.findById(CANVAS, TARGET)).thenReturn(Optional.of(output));
    when(blobAccess.findFacts(blobId))
        .thenReturn(
            Optional.of(
                new CanvasFunctionBlobAccess.BlobFacts(blobId, "video/mp4", 3L, 1L, 1L, null)));

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.completeSuccess(frozen, List.of(TARGET)));
  }

  /** start/cancel 必须拒绝非 Function、不可用 adapter 与不匹配 request，并幂等返回既有终态。 */
  @Test
  void startAndCancelValidateRuntimeIdentityBeforeWriting() {
    NodeRecord plain =
        new NodeRecord(NODE, CANVAS, "plain", fixture.node.transform(), null, null, null);
    when(canvasStore.lockNode(CANVAS, NODE)).thenReturn(Optional.of(plain));
    assertThrows(
        IllegalArgumentException.class, () -> transactions.start(CANVAS, NODE, REQUEST.toString()));

    when(canvasStore.lockNode(CANVAS, NODE)).thenReturn(Optional.of(fixture.node));
    when(runRepository.findByNodeIdForUpdate(NODE)).thenReturn(Optional.empty());
    when(adapter.enabled()).thenReturn(false);
    when(adapter.unavailableReason()).thenReturn("disabled");
    assertThrows(
        IllegalArgumentException.class, () -> transactions.start(CANVAS, NODE, REQUEST.toString()));

    CanvasFunctionRun terminal =
        new CanvasFunctionRun(
            NODE,
            REQUEST,
            CanvasFunctionRunStatus.CANCELLED,
            "CANCELLED",
            stateCodec.encode(fixture.frozen("CANCELLED", Map.of())),
            null,
            NOW);
    when(runRepository.findByNodeIdForUpdate(NODE)).thenReturn(Optional.of(terminal));
    assertThrows(
        CanvasFunctionRunException.class,
        () -> transactions.cancel(CANVAS, NODE, OTHER_REQUEST.toString()));
    assertEquals(terminal, transactions.cancel(CANVAS, NODE, REQUEST.toString()));
  }

  /** 迟到 success 必须清理孤立目标并返回 false；未物化目标不能进入 terminal swap。 */
  @Test
  void completeSuccessRejectsStaleOrUnmaterializedTarget() {
    CanvasFunctionFrozenRun frozen = fixture.frozen("QUEUED", Map.of());
    when(canvasStore.lockNode(CANVAS, NODE)).thenReturn(Optional.empty());
    assertFalse(transactions.completeSuccess(frozen, List.of(TARGET)));
    verify(resourceLifecycle).discardUnownedTarget(CANVAS, TARGET);

    when(canvasStore.lockNode(CANVAS, NODE)).thenReturn(Optional.of(fixture.node));
    when(runRepository.findByNodeIdForUpdate(NODE)).thenReturn(Optional.empty());
    assertFalse(transactions.completeSuccess(frozen, List.of(TARGET)));

    when(runRepository.findByNodeIdForUpdate(NODE)).thenReturn(fixture.runningRun());
    when(resourceRepository.findById(CANVAS, TARGET)).thenReturn(Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.completeSuccess(frozen, List.of(TARGET)));
  }

  private static final class CanvasFixture {

    private final CanvasDocument document;
    private final NodeRecord node;
    private final CanvasFunctionRunStateCodecPort stateCodec;

    private CanvasFixture(CanvasFunctionRunStateCodecPort stateCodec) {
      this.stateCodec = stateCodec;
      document = new CanvasDocument(CANVAS, "canvas", 5L, NOW, NOW);
      node =
          new NodeRecord(
              NODE,
              CANVAS,
              "output",
              new CanvasTransform(0.0, 0.0, 100.0, 80.0),
              null,
              MODEL.key(),
              "{\"prompt\":{\"segments\":[{\"type\":\"TEXT\",\"text\":\"prompt\"}]},"
                  + "\"parameters\":{}}");
    }

    private Optional<CanvasFunctionRun> runningRun() {
      return Optional.of(
          new CanvasFunctionRun(
              NODE,
              REQUEST,
              CanvasFunctionRunStatus.RUNNING,
              "QUEUED",
              stateCodec.encode(frozen("QUEUED", Map.of())),
              null,
              NOW));
    }

    private CanvasFunctionFrozenRun frozen(String stage, Map<String, Object> adapterState) {
      return new CanvasFunctionFrozenRun(
          CANVAS,
          NODE,
          "output",
          REQUEST,
          MODEL,
          new CanvasFunctionConfig(List.of(new TextSegment("prompt")), Map.of()),
          List.of(),
          "output.png",
          TARGET,
          stage,
          adapterState);
    }
  }
}
