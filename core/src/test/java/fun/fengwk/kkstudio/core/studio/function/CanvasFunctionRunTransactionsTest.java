package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.studio.realtime.CanvasRealtimeService;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasLinkMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasResourceMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.core.studio.resource.CanvasResourceLifecycle;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasNodePatch;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;

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
 * Transactional checkpoint 单元覆盖：成功一次事务内完成 run CAS、canvas version 前进并发布 node patch； run CAS 失败、run
 * 不再是该请求 RUNNING、节点消失、非法 stage/超大 adapterState 一律零版本变更且不发布。
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
  private CanvasRealtimeService realtimeService;
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
    realtimeService = mock(CanvasRealtimeService.class);
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
            realtimeService,
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
  void checkpointPersistsRunBumpsVersionAndPublishesNodePatch() {
    when(runRepository.checkpoint(eq(NODE), eq(REQUEST), any(), eq("SUBMITTING"), eq(NOW)))
        .thenReturn(true);
    when(documentMapper.compareAndSetVersion(CANVAS, 5L, 6L)).thenReturn(1);
    // bumpAndPublishNode 投影读到的是 CAS 已落库的 checkpoint 后 run。
    when(runRepository.findByNodeId(NODE))
        .thenReturn(
            Optional.of(
                new CanvasFunctionRun(
                    NODE,
                    REQUEST,
                    CanvasFunctionRunStatus.RUNNING,
                    "SUBMITTING",
                    stateCodec.encode(fixture.frozen("SUBMITTING", Map.of("jobId", "job"))),
                    null,
                    NOW)));

    CanvasFunctionFrozenRun next =
        transactions.checkpoint(
            CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of("jobId", "job"));

    assertEquals("SUBMITTING", next.stage());
    assertEquals(Map.of("jobId", "job"), next.adapterState());
    verify(runRepository).checkpoint(eq(NODE), eq(REQUEST), any(), eq("SUBMITTING"), eq(NOW));
    verify(documentMapper).compareAndSetVersion(CANVAS, 5L, 6L);
    verify(realtimeService)
        .publish(
            eq(CANVAS),
            argThat(
                patch -> {
                  return patch.baseVersion() == 5L
                      && patch.version() == 6L
                      && patch.nodes().size() == 1
                      && patch.nodes().get(0) instanceof CanvasNodePatch.Upsert upsert
                      && upsert.node().id().equals(NODE)
                      && upsert.node().run() != null
                      && upsert.node().run().status() == CanvasFunctionRunStatus.RUNNING
                      && "SUBMITTING".equals(upsert.node().run().stage());
                }));
  }

  @Test
  void checkpointCasFailureThrowsInternalCancellationWithoutVersionOrPublish() {
    when(runRepository.checkpoint(any(), any(), any(), any(), any())).thenReturn(false);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
    verify(realtimeService, never()).publish(any(), any());
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
    verify(realtimeService, never()).publish(any(), any());
  }

  @Test
  void checkpointMissingNodeThrowsInternalCancellationWithoutPublish() {
    when(nodeMapper.getByIdForUpdate(CANVAS, NODE)).thenReturn(null);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    verify(runRepository, never()).findByNodeIdForUpdate(any());
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
    verify(realtimeService, never()).publish(any(), any());
  }

  @Test
  void checkpointMissingDocumentThrowsInternalCancellationWithoutWriteOrPublish() {
    when(documentMapper.getByIdForUpdate(CANVAS)).thenReturn(null);

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "SUBMITTING", Map.of()));

    // 零写：不触碰 node/run 行；零发布：不前进 version、不发布 patch。
    verify(nodeMapper, never()).getByIdForUpdate(any(), any());
    verify(runRepository, never()).findByNodeIdForUpdate(any());
    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
    verify(realtimeService, never()).publish(any(), any());
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
    verify(realtimeService, never()).publish(any(), any());
  }

  @Test
  void checkpointRejectsInvalidStageBeforeAnyWrite() {
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.checkpoint(CANVAS, NODE, REQUEST.toString(), "lower case", Map.of()));

    verify(runRepository, never()).checkpoint(any(), any(), any(), any(), any());
    verify(documentMapper, never()).compareAndSetVersion(any(), anyLong(), anyLong());
    verify(realtimeService, never()).publish(any(), any());
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
