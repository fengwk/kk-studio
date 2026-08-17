package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.S3ObjectStream;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.core.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResource;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Execution context 只允许 frozen original/target，并验证对象长度与事务 checkpoint 委托。 */
class CanvasFunctionExecutionContextImplTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SOURCE_NODE = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID BLOB = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-000000000006");
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000007");

  private CanvasFunctionRunRepository runs;
  private CanvasFunctionRunTransactions transactions;
  private S3StorageService storage;
  private StorageBlobManager blobManager;
  private CanvasResourceMaterializer materializer;
  private CanvasFunctionFrozenRun frozen;

  @BeforeEach
  void setUp() {
    runs = mock(CanvasFunctionRunRepository.class);
    transactions = mock(CanvasFunctionRunTransactions.class);
    storage = mock(S3StorageService.class);
    blobManager = mock(StorageBlobManager.class);
    materializer = mock(CanvasResourceMaterializer.class);
    CanvasFunctionModel model =
        new CanvasFunctionModel(
            "test-image",
            "Test Image",
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
            List.of());
    CanvasFunctionFrozenReference reference =
        new CanvasFunctionFrozenReference(
            SOURCE_NODE,
            0,
            RESOURCE,
            BLOB,
            CanvasResourceKind.IMAGE,
            "source.png",
            "image/png",
            3L,
            1L,
            1L,
            null);
    frozen =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "output",
            REQUEST,
            model,
            new CanvasFunctionConfig(List.of(new TextSegment("prompt")), Map.of()),
            List.of(reference),
            "output.png",
            TARGET,
            "QUEUED",
            Map.of());
    when(runs.findByNodeId(NODE))
        .thenReturn(
            Optional.of(
                new CanvasFunctionRun(
                    NODE,
                    REQUEST,
                    CanvasFunctionRunStatus.RUNNING,
                    "QUEUED",
                    "{\"stage\":\"QUEUED\"}",
                    null,
                    Instant.EPOCH)));
  }

  @Test
  void opensOnlyFrozenOriginalAndClosesLengthMismatch() {
    TrackingInputStream mismatch = new TrackingInputStream(new byte[] {1, 2});
    when(storage.readObject(StorageObjectKeys.blobOriginal(BLOB)))
        .thenReturn(new S3ObjectStream(mismatch, new S3ObjectMetadata(2L, "image/png", null)));
    CanvasFunctionExecutionContextImpl context = context();

    assertThrows(
        IllegalArgumentException.class, () -> context.openOriginal(frozen.manifest().get(0)));
    assertTrue(mismatch.closed);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.openOriginal(
                new CanvasFunctionFrozenReference(
                    SOURCE_NODE,
                    0,
                    RESOURCE,
                    UUID.randomUUID(),
                    CanvasResourceKind.IMAGE,
                    "other.png",
                    "image/png",
                    3L,
                    1L,
                    1L,
                    null)));
  }

  @Test
  void materializesOnlyFrozenTargetAndOutputKind() {
    when(materializer.materialize(eq(CANVAS), eq(TARGET), anyString(), any(InputStream.class)))
        .thenReturn(
            new CanvasResource(
                TARGET, CANVAS, null, null, BLOB, "output.png", null, Instant.EPOCH));
    CanvasFunctionExecutionContextImpl context = context();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.materializeTarget(
                UUID.randomUUID(), new ByteArrayInputStream(new byte[] {1, 2, 3})));
    assertEquals(
        TARGET, context.materializeTarget(TARGET, new ByteArrayInputStream(new byte[] {1, 2, 3})));
    verify(materializer)
        .materialize(eq(CANVAS), eq(TARGET), eq("output.png"), any(InputStream.class));
  }

  @Test
  void presignsOnlyFrozenOriginalWhileRunIsRunning() {
    when(blobManager.presignOriginalUrl(BLOB))
        .thenReturn(StoragePresignedUrlDTO.builder().url("https://s3.example/object").build());
    CanvasFunctionExecutionContextImpl context = context();

    assertEquals(
        "https://s3.example/object", context.presignOriginal(frozen.manifest().get(0), 120L));
    verify(blobManager).presignOriginalUrl(BLOB);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.presignOriginal(
                new CanvasFunctionFrozenReference(
                    SOURCE_NODE,
                    0,
                    RESOURCE,
                    UUID.randomUUID(),
                    CanvasResourceKind.IMAGE,
                    "other.png",
                    "image/png",
                    3L,
                    1L,
                    1L,
                    null),
                120L));

    when(runs.findByNodeId(NODE)).thenReturn(Optional.empty());
    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> context.presignOriginal(frozen.manifest().get(0), 120L));
  }

  @Test
  void checkpointDelegatesToTransactionsAndUpdatesCurrent() {
    CanvasFunctionFrozenRun next =
        new CanvasFunctionFrozenRun(
            frozen.canvasId(),
            frozen.nodeId(),
            frozen.nodeName(),
            frozen.requestId(),
            frozen.model(),
            frozen.config(),
            frozen.manifest(),
            frozen.outputName(),
            frozen.targetResourceId(),
            "SUBMITTING",
            Map.of("jobId", "job"));
    when(transactions.checkpoint(
            eq(CANVAS),
            eq(NODE),
            eq(REQUEST.toString()),
            eq("SUBMITTING"),
            eq(Map.of("jobId", "job"))))
        .thenReturn(next);
    CanvasFunctionExecutionContextImpl context = context();

    context.checkpoint("SUBMITTING", Map.of("jobId", "job"));

    assertEquals(next, context.currentRun());
  }

  @Test
  void checkpointCasCancellationPropagatesWithoutUpdatingCurrent() {
    when(transactions.checkpoint(any(), any(), anyString(), anyString(), any()))
        .thenThrow(new CanvasFunctionInternalCancellation("no longer RUNNING"));
    CanvasFunctionExecutionContextImpl context = context();

    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> context.checkpoint("SUBMITTING", Map.of("jobId", "job")));

    assertEquals(
        frozen, context.currentRun(), "failed checkpoint must not write back the old frozen run");
  }

  private CanvasFunctionExecutionContextImpl context() {
    ObjectProvider<S3StorageService> storageProvider = provider(storage);
    ObjectProvider<StorageBlobManager> blobManagerProvider = provider(blobManager);
    ObjectProvider<CanvasResourceMaterializer> materializerProvider = provider(materializer);
    return new CanvasFunctionExecutionContextImpl(
        runs, transactions, storageProvider, blobManagerProvider, materializerProvider, frozen);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {
    private boolean closed;

    private TrackingInputStream(byte[] buffer) {
      super(buffer);
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
