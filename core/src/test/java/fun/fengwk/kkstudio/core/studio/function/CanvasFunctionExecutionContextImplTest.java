package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.storage.S3ObjectMetadata;
import fun.fengwk.kkstudio.core.storage.S3ObjectStream;
import fun.fengwk.kkstudio.core.storage.S3PresignService;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.share.storage.S3PresignedResponseDTO;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Execution context 只允许 frozen original/target，并验证对象长度与 checkpoint CAS。 */
class CanvasFunctionExecutionContextImplTest {

  private CanvasFunctionRunRepository runs;
  private S3StorageService storage;
  private S3PresignService presign;
  private CanvasResourceMaterializer materializer;
  private CanvasFunctionRunStateCodec stateCodec;
  private CanvasFunctionFrozenRun frozen;

  @BeforeEach
  void setUp() {
    runs = mock(CanvasFunctionRunRepository.class);
    storage = mock(S3StorageService.class);
    presign = mock(S3PresignService.class);
    materializer = mock(CanvasResourceMaterializer.class);
    CanvasFunctionConfigCodec configCodec = new CanvasFunctionConfigCodec(new ObjectMapper());
    stateCodec = new CanvasFunctionRunStateCodec(new ObjectMapper(), configCodec);
    CanvasFunctionModel model =
        new CanvasFunctionModel(
            "test-image",
            "Test Image",
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
            List.of());
    CanvasFunctionFrozenReference reference =
        new CanvasFunctionFrozenReference(
            2L, 0, 3L, CanvasResourceKind.IMAGE, "source.png", "image/png", 3L, "{}");
    frozen =
        new CanvasFunctionFrozenRun(
            1L,
            4L,
            "output",
            "request",
            model,
            new CanvasFunctionConfig(List.of(new TextSegment("prompt")), Map.of()),
            List.of(reference),
            "output.png",
            5L,
            "QUEUED",
            Map.of());
    when(runs.findByNodeId(4L))
        .thenReturn(
            Optional.of(
                new CanvasFunctionRun(
                    4L,
                    "request",
                    CanvasFunctionRunStatus.RUNNING,
                    "QUEUED",
                    stateCodec.encode(frozen),
                    null,
                    Instant.EPOCH)));
  }

  @Test
  void opensOnlyFrozenOriginalAndClosesLengthMismatch() {
    TrackingInputStream mismatch = new TrackingInputStream(new byte[] {1, 2});
    when(storage.readObject("canvases/1/resources/3/original"))
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
                    9L, 0, 10L, CanvasResourceKind.IMAGE, "other.png", "image/png", 3L, "{}")));
  }

  @Test
  void materializesOnlyFrozenTargetAndOutputKind() {
    when(materializer.materialize(
            anyLong(),
            anyLong(),
            any(),
            anyString(),
            anyString(),
            anyLong(),
            any(InputStream.class)))
        .thenReturn(
            new CanvasResource(
                5L,
                1L,
                CanvasResourceKind.IMAGE,
                "image/png",
                "output.png",
                3L,
                null,
                "{}",
                Instant.EPOCH));
    CanvasFunctionExecutionContextImpl context = context();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.materializeTarget(
                6L, "image/png", 3L, new ByteArrayInputStream(new byte[] {1, 2, 3})));
    assertEquals(
        5L,
        context.materializeTarget(
            5L, "image/png", 3L, new ByteArrayInputStream(new byte[] {1, 2, 3})));
    verify(materializer)
        .materialize(
            eq(1L),
            eq(5L),
            eq(CanvasResourceKind.IMAGE),
            eq("output.png"),
            eq("image/png"),
            eq(3L),
            any(InputStream.class));
  }

  @Test
  void presignsOnlyFrozenOriginalWhileRunIsRunning() {
    when(presign.presignDownload("canvases/1/resources/3/original", 120L))
        .thenReturn(S3PresignedResponseDTO.builder().url("https://s3.example/object").build());
    CanvasFunctionExecutionContextImpl context = context();

    assertEquals(
        "https://s3.example/object", context.presignOriginal(frozen.manifest().get(0), 120L));
    verify(presign).presignDownload("canvases/1/resources/3/original", 120L);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.presignOriginal(
                new CanvasFunctionFrozenReference(
                    9L, 0, 10L, CanvasResourceKind.IMAGE, "other.png", "image/png", 3L, "{}"),
                120L));

    when(runs.findByNodeId(4L)).thenReturn(Optional.empty());
    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> context.presignOriginal(frozen.manifest().get(0), 120L));
  }

  @Test
  void checkpointCasStopsExecutionAndAdapterStateIsBounded() {
    CanvasFunctionExecutionContextImpl context = context();
    when(runs.checkpoint(anyLong(), anyString(), anyString(), anyString(), any()))
        .thenReturn(false);
    assertThrows(
        CanvasFunctionInternalCancellation.class,
        () -> context.checkpoint("SUBMITTING", Map.of("jobId", "job")));

    clearInvocations(runs);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            context.checkpoint(
                "SUBMITTING",
                Map.of(
                    "oversized", "x".repeat(CanvasFunctionRunStateCodec.MAX_ADAPTER_STATE_BYTES))));
    verify(runs, never()).checkpoint(anyLong(), anyString(), anyString(), anyString(), any());
  }

  private CanvasFunctionExecutionContextImpl context() {
    ObjectProvider<S3StorageService> storageProvider = provider(storage);
    ObjectProvider<S3PresignService> presignProvider = provider(presign);
    ObjectProvider<CanvasResourceMaterializer> materializerProvider = provider(materializer);
    return new CanvasFunctionExecutionContextImpl(
        runs,
        stateCodec,
        storageProvider,
        presignProvider,
        materializerProvider,
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        frozen);
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
