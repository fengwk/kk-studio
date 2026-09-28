package fun.fengwk.kkstudio.plugin.canvascomfyui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.platform.harness.oneshot.HarnessOneShotService;
import fun.fengwk.kkstudio.platform.harness.oneshot.OneShotTicket;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** adapter stage machine 覆盖主链、每项上传 checkpoint/reuse、双 SUBMITTING 防重放、cancel 与 materialize。 */
class MiniMaxH3CanvasFunctionAdapterTest {

  private static final UUID CANVAS = new UUID(0L, 7L);
  private static final UUID NODE = new UUID(0L, 8L);
  private static final UUID REQUEST = new UUID(0L, 9L);
  private static final UUID TARGET = new UUID(0L, 99L);
  private static final UUID SOURCE_NODE = new UUID(0L, 2L);
  private static final UUID RESOURCE = new UUID(0L, 11L);
  private static final UUID SECOND_RESOURCE = new UUID(0L, 12L);
  private static final UUID THREAD_ID = new UUID(0L, 55L);
  private static final UUID SESSION_ID = new UUID(0L, 56L);
  private static final UUID UPLOAD_ID = new UUID(0L, 57L);
  private static final UUID BLOB_ID = new UUID(0L, 58L);

  private final ObjectMapper mapper = new ObjectMapper();
  private HarnessOneShotService oneShot;
  private StorageUploadService uploadService;
  private SessionBlobRefManager refManager;

  private StandardComfyuiClient comfy;
  private MiniMaxH3CanvasFunctionAdapter adapter;
  private SystemSettingsSnapshot snapshot;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    oneShot = mock(HarnessOneShotService.class);
    comfy = mock(StandardComfyuiClient.class);
    uploadService = mock(StorageUploadService.class);
    refManager = mock(SessionBlobRefManager.class);
    when(uploadService.stage(any(), any(), any(InputStream.class), anyLong()))
        .thenReturn(
            new StorageUploadService.StagedUpload(
                UPLOAD_ID, BLOB_ID, "source.png", "image/png", 3L, "a".repeat(64)));
    when(uploadService.lockReady(UPLOAD_ID))
        .thenReturn(new StorageUploadService.ReadyUpload(BLOB_ID, "source.png"));
    snapshot = new SystemSettingsSnapshot(settings(h3Settings(true, 1L, 1_000L)));
    adapter = newAdapter(snapshot);
  }

  @SuppressWarnings("unchecked")
  private MiniMaxH3CanvasFunctionAdapter newAdapter(SystemSettingsSnapshot snapshot) {
    ObjectProvider<StandardComfyuiClient> clients = mock(ObjectProvider.class);
    when(clients.getIfAvailable()).thenReturn(comfy);
    return new MiniMaxH3CanvasFunctionAdapter(
        snapshot,
        new H3MediaPreflight(),
        new H3PromptRequestBuilder(),
        oneShot,
        new H3WorkflowBuilder(mapper),
        clients,
        uploadService,
        refManager,
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
    when(oneShot.submit(anyString(), anyString(), any(), any()))
        .thenReturn(OneShotTicket.forThread(THREAD_ID));
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

    CanvasFunctionFrozenRun initialRun = run("QUEUED", Map.of());
    adapter.submit(context, initialRun);

    Map<String, Object> submittedState = context.lastAdapterState;
    CanvasFunctionFrozenRun submittedRun =
        new CanvasFunctionFrozenRun(
            CANVAS,
            NODE,
            "h3",
            REQUEST,
            adapter.functions().get(0),
            initialRun.args(),
            initialRun.manifest(),
            initialRun.outputs(),
            CanvasFunctionSubmitState.SUBMITTED,
            "COMFY_WAITING",
            submittedState);

    assertEquals(List.of(TARGET), adapter.execute(context, submittedRun));
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
    assertNull(context.lastPresign);
    assertEquals(TARGET, context.materializedTarget);
    assertEquals(List.of(9, 8, 7), context.materializedBytes);
  }

  @Test
  void preflightTransfersStagedMediaIntoDurableMessage() {
    AtomicReference<List<NewThreadCommand>> preparedCommands = new AtomicReference<>();
    when(oneShot.submit(anyString(), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              AgentMessage prompt = invocation.getArgument(2);
              AcceptancePreflight preflight = invocation.getArgument(3);
              NewThreadCommand command =
                  new NewThreadCommand(new CustomMessageCommandPayload(prompt), UUID.randomUUID());
              preparedCommands.set(
                  preflight.prepare(
                      mock(HarnessStore.Transaction.class),
                      new Session(SESSION_ID, "h3", Instant.EPOCH),
                      List.of(command)));
              return OneShotTicket.forThread(THREAD_ID);
            });
    when(oneShot.await(any(), any(), any()))
        .thenThrow(new IllegalStateException("stop after preflight"));

    assertThrows(
        IllegalStateException.class,
        () -> adapter.submit(new RecordingContext(), run("QUEUED", Map.of())));

    CustomMessageCommandPayload payload =
        assertInstanceOf(
            CustomMessageCommandPayload.class, preparedCommands.get().get(0).payload());
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, payload.message().contents().get(2));
    assertEquals(BLOB_ID, resource.blobId());
    assertEquals("source.png", resource.name());
    verify(refManager).retainRef(SESSION_ID, BLOB_ID);
    verify(uploadService).delete(UPLOAD_ID);
  }

  @Test
  void submitFailureDiscardsPreparedMedia() {
    when(oneShot.submit(anyString(), anyString(), any(), any()))
        .thenThrow(new IllegalStateException("submit failed"));

    assertThrows(
        IllegalStateException.class,
        () -> adapter.submit(new RecordingContext(), run("QUEUED", Map.of())));

    verify(uploadService).delete(UPLOAD_ID);
  }

  @Test
  void stagingFailureDiscardsPreviouslyPreparedMedia() {
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    InputStream first = new ByteArrayInputStream(new byte[] {1, 2, 3});
    when(context.openOriginal(any()))
        .thenReturn(new CanvasFunctionResourceStream(first, 3L, first::close))
        .thenThrow(new IllegalStateException("second source unavailable"));

    assertThrows(
        IllegalStateException.class,
        () ->
            adapter.submit(
                context,
                run(
                    "QUEUED",
                    Map.of(),
                    List.of(imageReference(RESOURCE), imageReference(SECOND_RESOURCE)))));

    verify(uploadService).delete(UPLOAD_ID);
    verify(oneShot, never()).submit(anyString(), anyString(), any(), any());
  }

  @Test
  void refusesBothSubmittingCrashWindowsWithoutResubmission() {
    CanvasFunctionUnknownException prompt =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () ->
                adapter.submit(
                    new RecordingContext(),
                    run(
                        MiniMaxH3CanvasFunctionAdapter.PROMPT_SUBMITTING,
                        H3AdapterState.empty().withSeed(1L).encode())));
    assertFalse(prompt.getMessage().isBlank());
    verify(oneShot, never()).submit(anyString(), anyString(), any(), any());

    CanvasFunctionUnknownException comfyError =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () ->
                adapter.submit(
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

    adapter.submit(
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
    assertEquals(
        MiniMaxH3CanvasFunctionAdapter.FUNCTION_NAME, disabledAdapter.functions().get(0).name());
    assertFalse(disabledAdapter.unavailableReason().isBlank());
    assertEquals(
        List.of(TARGET),
        disabledAdapter.execute(
            new RecordingContext(),
            run(
                MiniMaxH3CanvasFunctionAdapter.COMPLETE,
                H3AdapterState.empty().withSeed(1L).withPromptId("p1").encode())));
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
    return run(stage, state, List.of(imageReference(RESOURCE)));
  }

  private CanvasFunctionFrozenRun run(
      String stage, Map<String, Object> state, List<CanvasFunctionFrozenReference> manifest) {
    Map<String, CanvasJson> argsMap = new LinkedHashMap<>();
    argsMap.put("prompt", new JsonText("animate it"));
    argsMap.put("ratio", new JsonText("16:9"));
    argsMap.put("duration", new JsonNumber(BigDecimal.valueOf(5)));
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "h3",
        REQUEST,
        adapter.functions().get(0),
        new JsonObject(argsMap),
        manifest,
        List.of(new CanvasFunctionFrozenOutput(TARGET, 0, CanvasResourceKind.VIDEO, "result.mp4")),
        CanvasFunctionSubmitState.SUBMITTED,
        stage,
        state);
  }

  private static CanvasFunctionFrozenReference imageReference(UUID resourceId) {
    return new CanvasFunctionFrozenReference(
        SOURCE_NODE,
        0,
        resourceId,
        resourceId,
        CanvasResourceKind.IMAGE,
        "source.png",
        "image/png",
        3L,
        512L,
        512L,
        null);
  }

  private static final class RecordingContext implements CanvasFunctionExecutionContext {
    private final List<String> stages = new ArrayList<>();
    private Map<String, Object> lastAdapterState = Map.of();
    private UUID materializedTarget;
    private List<Integer> materializedBytes;
    private String lastPresign;
    private boolean running = true;

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      stages.add(stage);
      this.lastAdapterState = adapterState;
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      ByteArrayInputStream stream = new ByteArrayInputStream(new byte[] {1, 2, 3});
      return new CanvasFunctionResourceStream(stream, 3L, () -> {});
    }

    @Override
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      lastPresign = "https://example.com/presigned/" + reference.resourceId();
      return lastPresign;
    }

    @Override
    public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
      materializedTarget = output.resourceId();
      try {
        byte[] bytes = content.readAllBytes();
        materializedBytes = new ArrayList<>();
        for (byte b : bytes) {
          materializedBytes.add((int) b);
        }
      } catch (Exception error) {
        throw new RuntimeException(error);
      }
      return output.resourceId();
    }

    @Override
    public UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text) {
      throw new UnsupportedOperationException("H3 output is a media slot");
    }
  }
}
