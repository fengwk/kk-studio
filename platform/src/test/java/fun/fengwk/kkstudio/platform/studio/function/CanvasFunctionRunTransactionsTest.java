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

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.platform.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.platform.studio.resource.CanvasResourceLifecycle;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          "test-image",
          "Test Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of());

  private CanvasNodeMapper nodeMapper;
  private CanvasResourceMapper resourceMapper;
  private CanvasLinkMapper linkMapper;
  private CanvasDocumentMapper documentMapper;
  private CanvasFunctionRunRepository runRepository;
  private CanvasFunctionRunTransactions transactions;
  private CanvasFunctionRunStateCodec stateCodec;
  private CanvasFixture fixture;

  @BeforeEach
  void setUp() {
    nodeMapper = mock(CanvasNodeMapper.class);
    resourceMapper = mock(CanvasResourceMapper.class);
    linkMapper = mock(CanvasLinkMapper.class);
    documentMapper = mock(CanvasDocumentMapper.class);
    runRepository = mock(CanvasFunctionRunRepository.class);
    CanvasFunctionResourcePinRepository refRepository =
        mock(CanvasFunctionResourcePinRepository.class);
    CanvasFunctionModelRegistry registry = mock(CanvasFunctionModelRegistry.class);
    when(registry.require(MODEL.key()))
        .thenReturn(
            new CanvasFunctionModelRegistry.RegisteredModel(
                MODEL, mock(CanvasFunctionAdapter.class)));
    CanvasFunctionConfigCodec configCodec = new CanvasFunctionConfigCodec(new ObjectMapper());
    stateCodec = new CanvasFunctionRunStateCodec(new ObjectMapper(), configCodec);
    CanvasResourceLifecycle resourceLifecycle = mock(CanvasResourceLifecycle.class);
    ObjectProvider<StorageBlobManager> blobManagers = provider(mock(StorageBlobManager.class));
    transactions =
        new CanvasFunctionRunTransactions(
            nodeMapper,
            resourceMapper,
            linkMapper,
            documentMapper,
            runRepository,
            refRepository,
            registry,
            configCodec,
            stateCodec,
            resourceLifecycle,
            blobManagers,
            Clock.fixed(NOW, ZoneOffset.UTC));
    fixture = new CanvasFixture(stateCodec);
    when(documentMapper.getByIdForUpdate(CANVAS)).thenReturn(fixture.document);
    when(nodeMapper.getByIdForUpdate(CANVAS, NODE)).thenReturn(fixture.node);
    when(nodeMapper.getById(CANVAS, NODE)).thenReturn(fixture.node);
    when(resourceMapper.listByOwnerNode(CANVAS, NODE)).thenReturn(List.of());
    when(runRepository.findByNodeIdForUpdate(NODE)).thenReturn(fixture.runningRun());
    when(runRepository.findByNodeId(NODE)).thenReturn(fixture.runningRun());
  }

  @Test
  void checkpointPersistsRunAndBumpsVersion() {
    when(runRepository.checkpoint(eq(NODE), eq(REQUEST), any(), eq("SUBMITTING"), eq(NOW)))
        .thenReturn(true);
    when(documentMapper.compareAndSetVersion(CANVAS, 5L, 6L)).thenReturn(1);

    CanvasFunctionFrozenRun next =
        transactions.checkpoint(
            CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of("jobId", "job"));

    assertEquals("SUBMITTING", next.stage());
    assertEquals(Map.of("jobId", "job"), next.adapterState());
    verify(runRepository).checkpoint(eq(NODE), eq(REQUEST), any(), eq("SUBMITTING"), eq(NOW));
    verify(documentMapper).compareAndSetVersion(CANVAS, 5L, 6L);
  }

  @Test
  void checkpointCasFailureThrowsInternalCancellationWithoutVersionChange() {
    when(runRepository.checkpoint(any(), any(), any(), any(), any())).thenReturn(false);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
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
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointMissingNodeThrowsInternalCancellationWithoutVersionChange() {
    when(nodeMapper.getByIdForUpdate(CANVAS, NODE)).thenReturn(null);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    verify(runRepository, never()).findByNodeIdForUpdate(any());
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointMissingDocumentThrowsInternalCancellationWithoutWrite() {
    when(documentMapper.getByIdForUpdate(CANVAS)).thenReturn(null);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    // 零写：不触碰 node/run 行，也不前进 version。
    verify(nodeMapper, never()).getByIdForUpdate(any(), any());
    verify(runRepository, never()).findByNodeIdForUpdate(any());
    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointRejectsOversizedAdapterStateBeforeAnyWrite() {
    Map<String, Object> oversized =
        Map.of("payload", "x".repeat(CanvasFunctionRunStateCodec.MAX_ADAPTER_STATE_BYTES + 1));

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", oversized));

    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
  }

  @Test
  void checkpointRejectsInvalidStageBeforeAnyWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "lower case", Map.of()));

    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  private static final class CanvasFixture {

    private final CanvasDocumentDO document;
    private final CanvasNodeDO node;
    private final CanvasFunctionRunStateCodec stateCodec;

    private CanvasFixture(CanvasFunctionRunStateCodec stateCodec) {
      this.stateCodec = stateCodec;
      document = new CanvasDocumentDO();
      document.setId(CANVAS);
      document.setTitle("canvas");
      document.setVersion(5L);
      document.setCreatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
      document.setUpdatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
      node = new CanvasNodeDO();
      node.setId(NODE);
      node.setCanvasId(CANVAS);
      node.setName("output");
      node.setX(0.0);
      node.setY(0.0);
      node.setWidth(100.0);
      node.setHeight(80.0);
      node.setModelKey(MODEL.key());
      node.setFunctionConfigJson("{}");
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
