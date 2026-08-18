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
import fun.fengwk.kkstudio.core.systemsettings.SystemSettings;
import fun.fengwk.kkstudio.core.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionResourceStream;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** adapter stage machine 覆盖主链、每项上传 checkpoint/reuse、双 SUBMITTING 防重放、cancel 与 materialize。 */
class MiniMaxH3CanvasFunctionAdapterTest {

  private static final UUID CANVAS = new UUID(0L, 7L);
  private static final UUID NODE = new UUID(0L, 8L);
  private static final UUID REQUEST = new UUID(0L, 9L);
  private static final UUID TARGET = new UUID(0L, 99L);
  private static final UUID SOURCE_NODE = new UUID(0L, 2L);
  private static final UUID RESOURCE = new UUID(0L, 11L);
  private static final UUID THREAD_ID = new UUID(0L, 55L);

  private final ObjectMapper mapper = new ObjectMapper();
  private HarnessOneShotService oneShot;
  private ObjectProvider<StorageBlobIngestService> ingestServices;

  private StandardComfyuiClient comfy;
  private MiniMaxH3CanvasFunctionAdapter adapter;
  private SystemSettingsSnapshot snapshot;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    oneShot = mock(HarnessOneShotService.class);
    comfy = mock(StandardComfyuiClient.class);
    snapshot = new SystemSettingsSnapshot(settings(h3Settings(true, 1L, 1_000L)));
    adapter = newAdapter(snapshot);
  }

  private MiniMaxH3CanvasFunctionAdapter newAdapter(SystemSettingsSnapshot snapshot) {
    ObjectProvider<StandardComfyuiClient> clients = mock(ObjectProvider.class);
    when(clients.getIfAvailable()).thenReturn(comfy);
    ingestServices = mock(ObjectProvider.class);
    when(ingestServices.getIfAvailable()).thenReturn(mock(StorageBlobIngestService.class));
    return new MiniMaxH3CanvasFunctionAdapter(
        snapshot,
        new H3MediaPreflight(),
        new H3PromptRequestBuilder(),
        oneShot,
        new H3WorkflowBuilder(mapper),
        clients,
        ingestServices,
        mapper);
  }

  private static SystemSettings settings(SystemSettings.MiniMaxH3 minimaxH3) {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        SystemSettings.AiRuntime.DEFAULT,
        SystemSettings.Environment.DEFAULT,
        new SystemSettings.Integrations(
            SystemSettings.Comfyui.DEFAULT,
            SystemSettings.OpenCliHub.DEFAULT,
            SystemSettings.Seedance.DEFAULT,
            SystemSettings.GptImage2.DEFAULT,
            minimaxH3),
        SystemSettings.StorageMedia.DEFAULT,
        SystemSettings.Advanced.DEFAULT);
  }

  private static SystemSettings.MiniMaxH3 h3Settings(
      boolean enabled, long comfyPollIntervalMillis, long comfyMaxWaitMillis) {
    return new SystemSettings.MiniMaxH3(
        enabled,
        "h3-agent",
        "h3-prompt",
        600_000L,
        "http://127.0.0.1:8188",
        10_000L,
        30_000L,
        comfyPollIntervalMillis,
        comfyMaxWaitMillis);
  }

  @Test
  void executesAllStagesAndMaterializesOnlyTarget() {
    RecordingContext context = new RecordingContext();
    when(oneShot.submit(anyString(), any(), anyString(), any(), any())).thenReturn(THREAD_ID);
    when(oneShot.await(any(), any(), any())).thenReturn("enhanced prompt");
    when(comfy.upload(anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(new H3UploadedFile("11.png", "kk-studio/7", "input"));
    when(comfy.submit(any(), anyString())).thenReturn("p1");
    when(comfy.history("p1"))
        .thenReturn(
            H3ComfyHistory.success(new H3OutputDescriptor("result.mp4", "video", "output")));
    when(comfy.download(any()))
        .thenReturn(
            new H3ComfyDownload(new ByteArrayInputStream(new byte[] {9, 8, 7}), 3L, "video/mp4"));

    assertEquals(List.of(TARGET), adapter.execute(context, run("QUEUED", Map.of())));
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
    assertEquals(TARGET, context.materializedTarget);
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
            .withUpload(RESOURCE, uploaded);
    when(comfy.submit(any(), anyString())).thenReturn("p1");
    when(comfy.history("p1"))
        .thenReturn(
            H3ComfyHistory.success(new H3OutputDescriptor("result.mp4", "video", "output")));
    when(comfy.download(any()))
        .thenReturn(new H3ComfyDownload(new ByteArrayInputStream(new byte[] {1}), 1L, "video/mp4"));

    adapter.execute(
        new RecordingContext(),
        run(MiniMaxH3CanvasFunctionAdapter.COMFY_UPLOADING, state.encode()));
    verify(comfy, never()).upload(anyString(), anyString(), anyLong(), any(), any());

    H3AdapterState cancelState = state.withHarnessThreadId(THREAD_ID).withPromptId("p1");
    doThrow(new IllegalStateException("stop failed")).when(oneShot).stop(THREAD_ID);
    adapter.cancel(run(MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING, cancelState.encode()));
    verify(oneShot).stop(THREAD_ID);
    verify(comfy).cancelPending("p1");
  }

  @Test
  void disabledModelRemainsRegisteredAsUnavailableAndCompleteNeedsNoClient() {
    MiniMaxH3CanvasFunctionAdapter disabledAdapter =
        newAdapter(new SystemSettingsSnapshot(settings(h3Settings(false, 1L, 1_000L))));
    assertFalse(disabledAdapter.enabled());
    assertEquals(MiniMaxH3CanvasFunctionAdapter.MODEL_KEY, disabledAdapter.models().get(0).key());
    assertFalse(disabledAdapter.unavailableReason().isBlank());
    assertEquals(
        List.of(TARGET),
        disabledAdapter.execute(
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
    MiniMaxH3CanvasFunctionAdapter tinyWaitAdapter =
        newAdapter(new SystemSettingsSnapshot(settings(h3Settings(true, 1L, 2L))));
    assertThrows(
        IllegalStateException.class,
        () ->
            tinyWaitAdapter.execute(
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
            SOURCE_NODE,
            0,
            RESOURCE,
            RESOURCE,
            CanvasResourceKind.IMAGE,
            "source.png",
            "image/png",
            3L,
            512L,
            512L,
            null);
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "h3",
        REQUEST,
        adapter.models().get(0),
        new CanvasFunctionConfig(
            List.of(new TextSegment("animate it")), Map.of("ratio", "16:9", "duration", 5)),
        List.of(image),
        "result",
        TARGET,
        stage,
        state);
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {

    private final List<String> stages = new ArrayList<>();
    private boolean running = true;
    private String lastPresign;
    private UUID materializedTarget;
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
    public UUID materializeTarget(UUID targetResourceId, InputStream content) {
      materializedTarget = targetResourceId;
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
