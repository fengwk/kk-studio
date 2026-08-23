package fun.fengwk.kkstudio.platform.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfigCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionConfigCodec;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRunStateCodec;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.studio.resource.CanvasResourceLifecycle;

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
  private CanvasFixture fixture;

  @BeforeEach
  void setUp() {
    canvasStore = mock(CanvasStore.class);
    resourceRepository = mock(CanvasResourceRepository.class);
    runRepository = mock(CanvasFunctionRunRepository.class);
    CanvasFunctionResourcePinRepository refRepository =
        mock(CanvasFunctionResourcePinRepository.class);
    CanvasFunctionModelRegistry registry = mock(CanvasFunctionModelRegistry.class);
    when(registry.require(MODEL.key()))
        .thenReturn(
            new CanvasFunctionModelRegistry.RegisteredModel(
                MODEL, mock(CanvasFunctionAdapter.class)));
    CanvasFunctionConfigCodecPort configCodec = new CanvasFunctionConfigCodec(new ObjectMapper());
    stateCodec = new CanvasFunctionRunStateCodec(new ObjectMapper(), configCodec);
    CanvasResourceLifecycle resourceLifecycle = mock(CanvasResourceLifecycle.class);
    ObjectProvider<StorageBlobManager> blobManagers = provider(mock(StorageBlobManager.class));
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
            blobManagers,
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

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
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
              "{}");
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
