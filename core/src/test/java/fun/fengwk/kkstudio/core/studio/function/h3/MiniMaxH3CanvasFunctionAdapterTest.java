package fun.fengwk.kkstudio.core.studio.function.h3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.ai.runtime.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.core.storage.service.StorageBlobIngestService;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** adapter stage machine 覆盖主链、每项上传 checkpoint/reuse、双 SUBMITTING 防重放、cancel 与 materialize。 */
class MiniMaxH3CanvasFunctionAdapterTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private static final UUID THREAD_ID = new UUID(0L, 55L);
  private HarnessOneShotService oneShot;

  @SuppressWarnings("rawtypes")
  private ObjectProvider ingestServices;

  private StandardComfyuiClient comfy;
  private MiniMaxH3CanvasFunctionAdapter adapter;
  private MiniMaxH3Properties properties;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    oneShot = mock(HarnessOneShotService.class);
    comfy = mock(StandardComfyuiClient.class);
    ObjectProvider<StandardComfyuiClient> clients = mock(ObjectProvider.class);
    when(clients.getIfAvailable()).thenReturn(comfy);
    properties = new MiniMaxH3Properties();
    properties.setEnabled(true);
    properties.setPromptAgentName("h3-agent");
    properties.setPromptEnvironmentName("h3-prompt");
    properties.setComfyPollInterval(Duration.ofMillis(1));
    properties.setComfyMaxWait(Duration.ofSeconds(1));
    ingestServices = mock(ObjectProvider.class);
    when(ingestServices.getIfAvailable()).thenReturn(mock(StorageBlobIngestService.class));
    adapter =
        new MiniMaxH3CanvasFunctionAdapter(
            properties,
            new H3MediaPreflight(mapper),
            new H3PromptRequestBuilder(),
            oneShot,
            new H3WorkflowBuilder(mapper),
            clients,
            ingestServices,
            mapper);
  }

  @Test
  void executesAllStagesAndMaterializesOnlyTarget() {
    RecordingContext context = new RecordingContext();
    when(oneShot.submit(anyString(), any(), anyString(), any(), any())).thenReturn(THREAD_ID);
    when(oneShot.await(any(), any(), any())).thenReturn("enhanced prompt");
    when(comfy.upload(anyString(), anyString(), anyLong(), any(), anyLong()))
        .thenReturn(new H3UploadedFile("11.png", "kk-studio/7", "input"));
    when(comfy.submit(any(), anyString())).thenReturn("p1");
    when(comfy.history("p1"))
        .thenReturn(
            H3ComfyHistory.success(new H3OutputDescriptor("result.mp4", "video", "output")));
    when(comfy.download(any()))
        .thenReturn(
            new H3ComfyDownload(new ByteArrayInputStream(new byte[] {9, 8, 7}), 3L, "video/mp4"));

    assertEquals(List.of(99L), adapter.execute(context, run("QUEUED", Map.of())));
    assertEquals(
        List.of(
            MiniMaxH3CanvasFunctionAdapter.INITIALIZED,
            MiniMaxH3CanvasFunctionAdapter.PROMPT_SUBMITTING,
            MiniMaxH3CanvasFunctionAdapter.PROMPT_WAITING,
            MiniMaxH3CanvasFunctionAdapter.PROMPT_READY,
            MiniMaxH3CanvasFunctionAdapter.COMFY_UPLOADING,
            MiniMaxH3CanvasFunctionAdapter.COMFY_UPLOADING,
            MiniMaxH3CanvasFunctionAdapter.COMFY_SUBMITTING,
            MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING,
            MiniMaxH3CanvasFunctionAdapter.COMFY_READY,
            MiniMaxH3CanvasFunctionAdapter.COMPLETE),
        context.stages);
    // 媒体外部化已移入入队 preflight（one-shot submit 被 mock，不执行）：prompt 阶段只构建 preflight，
    // 绝不 presign；ingest 端口在构建时探测（S3 缺失时确定性失败）。
    verify(ingestServices).getIfAvailable();
    assertNull(context.lastPresign);
    assertEquals(99L, context.materializedTarget);
    assertEquals("video/mp4", context.materializedMediaType);
    assertEquals(List.of(9, 8, 7), context.materializedBytes);
  }

  @Test
  void refusesBothSubmittingCrashWindowsWithoutResubmission() {
    IllegalStateException prompt =
        assertThrows(
            IllegalStateException.class,
            () ->
                adapter.execute(
                    new RecordingContext(),
                    run(
                        MiniMaxH3CanvasFunctionAdapter.PROMPT_SUBMITTING,
                        H3AdapterState.empty().withSeed(1L).encode())));
    assertFalse(prompt.getMessage().isBlank());
    verify(oneShot, never()).submit(anyString(), any(), anyString(), any(), any());

    IllegalStateException comfyError =
        assertThrows(
            IllegalStateException.class,
            () ->
                adapter.execute(
                    new RecordingContext(),
                    run(
                        MiniMaxH3CanvasFunctionAdapter.COMFY_SUBMITTING,
                        H3AdapterState.empty()
                            .withSeed(1L)
                            .withEnhancedPrompt("enhanced")
                            .encode())));
    assertFalse(comfyError.getMessage().isBlank());
    verify(comfy, never()).submit(any(), anyString());
  }

  @Test
  void reusesCheckpointedUploadAndCancelAttemptsBothSystems() {
    H3UploadedFile uploaded = new H3UploadedFile("11.png", "kk-studio/7", "input");
    H3AdapterState state =
        H3AdapterState.empty()
            .withSeed(1L)
            .withEnhancedPrompt("enhanced")
            .withUpload(11L, uploaded);
    when(comfy.submit(any(), anyString())).thenReturn("p1");
    when(comfy.history("p1"))
        .thenReturn(
            H3ComfyHistory.success(new H3OutputDescriptor("result.mp4", "video", "output")));
    when(comfy.download(any()))
        .thenReturn(new H3ComfyDownload(new ByteArrayInputStream(new byte[] {1}), 1L, "video/mp4"));

    adapter.execute(
        new RecordingContext(),
        run(MiniMaxH3CanvasFunctionAdapter.COMFY_UPLOADING, state.encode()));
    verify(comfy, never()).upload(anyString(), anyString(), anyLong(), any(), anyLong());

    H3AdapterState cancelState = state.withHarnessThreadId(THREAD_ID).withPromptId("p1");
    doThrow(new IllegalStateException("stop failed")).when(oneShot).stop(THREAD_ID);
    adapter.cancel(run(MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING, cancelState.encode()));
    verify(oneShot).stop(THREAD_ID);
    verify(comfy).cancelPending("p1");
  }

  @Test
  void disabledModelRemainsRegisteredAsUnavailableAndCompleteNeedsNoClient() {
    properties.setEnabled(false);
    assertFalse(adapter.enabled());
    assertEquals(MiniMaxH3CanvasFunctionAdapter.MODEL_KEY, adapter.models().get(0).key());
    assertFalse(adapter.unavailableReason().isBlank());
    assertEquals(
        List.of(99L),
        adapter.execute(
            new RecordingContext(),
            run(
                MiniMaxH3CanvasFunctionAdapter.COMPLETE,
                H3AdapterState.empty().withSeed(1L).encode())));
  }

  @Test
  void preflightPollingFailuresAndStoppedRunsFailClosed() {
    assertDoesNotThrow(() -> adapter.preflight(run("QUEUED", Map.of())));

    H3AdapterState waiting =
        H3AdapterState.empty().withSeed(1L).withEnhancedPrompt("enhanced").withPromptId("p1");
    when(comfy.history("p1")).thenReturn(H3ComfyHistory.error("remote boom"));
    assertThrows(
        IllegalStateException.class,
        () ->
            adapter.execute(
                new RecordingContext(),
                run(MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING, waiting.encode())));

    when(comfy.history("p1")).thenReturn(H3ComfyHistory.pending());
    properties.setComfyMaxWait(Duration.ofNanos(1));
    assertThrows(
        IllegalStateException.class,
        () ->
            adapter.execute(
                new RecordingContext(),
                run(MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING, waiting.encode())));

    RecordingContext stopped = new RecordingContext();
    stopped.running = false;
    H3AdapterState ready =
        waiting.withOutput(new H3OutputDescriptor("result.mp4", "video", "output"));
    assertThrows(
        IllegalStateException.class,
        () ->
            adapter.execute(
                stopped, run(MiniMaxH3CanvasFunctionAdapter.COMFY_READY, ready.encode())));
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.execute(new RecordingContext(), run("H3_UNKNOWN", Map.of())));
  }

  @Test
  void cancelIgnoresMalformedStateAndIndependentComfyFailure() {
    adapter.cancel(
        run(MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING, Map.of("future", "unsupported")));

    H3AdapterState state =
        H3AdapterState.empty().withSeed(1L).withHarnessThreadId(THREAD_ID).withPromptId("p1");
    doThrow(new IllegalStateException("delete failed")).when(comfy).cancelPending("p1");
    assertDoesNotThrow(
        () -> adapter.cancel(run(MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING, state.encode())));
    verify(oneShot).stop(THREAD_ID);
  }

  private CanvasFunctionFrozenRun run(String stage, Map<String, Object> state) {
    CanvasFunctionFrozenReference image =
        new CanvasFunctionFrozenReference(
            2L,
            0,
            11L,
            CanvasResourceKind.IMAGE,
            "source.png",
            "image/png",
            3L,
            "{\"container\":\"png\",\"codec\":\"png\",\"width\":512,\"height\":512}");
    return new CanvasFunctionFrozenRun(
        7L,
        8L,
        "h3",
        "request",
        adapter.models().get(0),
        new CanvasFunctionConfig(
            List.of(new TextSegment("animate it")), Map.of("ratio", "16:9", "duration", 5)),
        List.of(image),
        "result",
        99L,
        stage,
        state);
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {

    private final List<String> stages = new ArrayList<>();
    private boolean running = true;
    private String lastPresign;
    private long materializedTarget;
    private String materializedMediaType;
    private List<Integer> materializedBytes;

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      stages.add(stage);
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      InputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
      return new CanvasFunctionResourceStream(input, 3L, input::close);
    }

    @Override
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      lastPresign = "https://signed/" + reference.resourceId();
      return lastPresign;
    }

    @Override
    public long materializeTarget(
        long targetResourceId, String mediaType, long size, InputStream content) {
      materializedTarget = targetResourceId;
      materializedMediaType = mediaType;
      materializedBytes = new ArrayList<>();
      try {
        for (byte value : content.readAllBytes()) {
          materializedBytes.add(Byte.toUnsignedInt(value));
        }
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
      return targetResourceId;
    }
  }
}
