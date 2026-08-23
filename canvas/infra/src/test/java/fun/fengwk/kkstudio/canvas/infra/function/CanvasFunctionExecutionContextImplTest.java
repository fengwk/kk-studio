package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;

import java.io.ByteArrayInputStream;
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
  private CanvasFunctionBlobAccess blobAccess;
  private CanvasResourceMaterializer materializer;
  private CanvasFunctionFrozenRun frozen;

  @BeforeEach
  void setUp() {
    runs = mock(CanvasFunctionRunRepository.class);
    transactions = mock(CanvasFunctionRunTransactions.class);
    blobAccess = mock(CanvasFunctionBlobAccess.class);
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
  void opensOnlyFrozenOriginalWithFrozenLength() {
    CanvasFunctionResourceStream stream =
        new CanvasFunctionResourceStream(
            new ByteArrayInputStream(new byte[] {1, 2, 3}),
            3L,
            new ByteArrayInputStream(new byte[0]));
    when(blobAccess.openOriginal(BLOB, 3L)).thenReturn(stream);
    CanvasFunctionExecutionContextImpl context = context();

    assertEquals(stream, context.openOriginal(frozen.manifest().get(0)));
    verify(blobAccess).openOriginal(BLOB, 3L);
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
    when(blobAccess.originalUrl(BLOB, 120L)).thenReturn("https://s3.example/object");
    CanvasFunctionExecutionContextImpl context = context();

    assertEquals(
        "https://s3.example/object", context.presignOriginal(frozen.manifest().get(0), 120L));
    verify(blobAccess).originalUrl(BLOB, 120L);
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
    ObjectProvider<CanvasResourceMaterializer> materializerProvider = provider(materializer);
    return new CanvasFunctionExecutionContextImpl(
        runs, transactions, blobAccess, materializerProvider, frozen);
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
