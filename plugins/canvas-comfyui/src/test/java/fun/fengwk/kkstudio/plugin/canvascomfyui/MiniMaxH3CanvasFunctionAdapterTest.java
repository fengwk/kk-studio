package fun.fengwk.kkstudio.plugin.canvascomfyui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
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
  private static final UUID THIRD_RESOURCE = new UUID(0L, 13L);
  private static final UUID THREAD_ID = new UUID(0L, 55L);
  private static final UUID SESSION_ID = new UUID(0L, 56L);
  private static final UUID UPLOAD_ID = new UUID(0L, 57L);
  private static final UUID BLOB_ID = new UUID(0L, 58L);

  private final ObjectMapper mapper = new ObjectMapper();
  private PluginPromptTasks promptTasks;
  private PluginPromptResources promptResources;

  private StandardComfyuiClient comfy;
  private MiniMaxH3CanvasFunctionAdapter adapter;
  private SystemSettingsSnapshot snapshot;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    promptTasks = mock(PluginPromptTasks.class);
    promptResources = mock(PluginPromptResources.class);
    comfy = mock(StandardComfyuiClient.class);
    when(promptResources.stage(any(), any(), any(InputStream.class), anyLong()))
        .thenReturn(new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID));
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
        promptTasks,
        promptResources,
        new H3WorkflowBuilder(mapper),
        clients);
  }

  private static SystemSettings settings(SystemSettings.MiniMaxH3 minimaxH3) {
    return new SystemSettings(
        SystemSettings.Tool.DEFAULT,
        SystemSettings.AiRuntime.DEFAULT,
        SystemSettings.Environment.DEFAULT,
        SystemSettings.Network.DEFAULT,
        new SystemSettings.Integrations(SystemSettings.Comfyui.DEFAULT, minimaxH3),
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
    when(promptTasks.submit(anyString(), anyString(), any())).thenReturn(THREAD_ID);
    when(promptTasks.await(any(), any(), any())).thenReturn("enhanced prompt");
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
            new CanvasFunctionBinding(1L, adapter.functions().get(0), null),
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

  /** 窄端口只接收有序 Text/Media 片段：宿主接受细节（RESOURCE 物化、Session 引用、upload owner 释放）由宿主测试覆盖，adapter 不得触碰。 */
  @Test
  void submitsOrderedTextAndMediaPartsThroughNarrowPorts() {
    AtomicReference<List<PluginPromptContent>> submitted = new AtomicReference<>();
    when(promptTasks.submit(anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              submitted.set(invocation.getArgument(2));
              return THREAD_ID;
            });
    when(promptTasks.await(any(), any(), any()))
        .thenThrow(new IllegalStateException("stop after preflight"));

    assertThrows(
        IllegalStateException.class,
        () -> adapter.submit(new RecordingContext(), run("QUEUED", Map.of())));

    List<PluginPromptContent> contents = submitted.get();
    assertEquals(3, contents.size());
    assertTrue(((PluginPromptContent.Text) contents.get(0)).text().contains("Frozen manifest"));
    String label = ((PluginPromptContent.Text) contents.get(1)).text();
    assertTrue(label.startsWith("\nThe next attachment is "), label);
    PluginPromptContent.Media media =
        assertInstanceOf(PluginPromptContent.Media.class, contents.get(2));
    assertEquals(new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID), media.resource());
    verify(promptResources, never()).discard(any());
  }

  /** 一旦进入窄端口 submit，异常不能证明接受事实未成立：必须转 UNKNOWN，禁止删除可能已被消费的 staged media，恢复时不得再次 submit。 */
  @Test
  void promptSubmitExceptionBecomesUnknownWithoutDiscardingStagedMedia() {
    when(promptTasks.submit(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("response lost after accept"));

    RecordingContext context = new RecordingContext();
    CanvasFunctionUnknownException unknown =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () -> adapter.submit(context, run("QUEUED", Map.of())));
    assertFalse(unknown.getMessage().isBlank());
    verify(promptResources, never()).discard(any());
    assertEquals(
        List.of(
            MiniMaxH3CanvasFunctionAdapter.INITIALIZED,
            MiniMaxH3CanvasFunctionAdapter.PROMPT_SUBMITTING),
        context.stages);

    CanvasFunctionFrozenRun crashed =
        run(
            MiniMaxH3CanvasFunctionAdapter.PROMPT_SUBMITTING,
            H3AdapterState.empty().withSeed(contextSeed(context)).encode());
    assertThrows(
        CanvasFunctionUnknownException.class,
        () -> adapter.submit(new RecordingContext(), crashed));
    verify(promptTasks, times(1)).submit(anyString(), anyString(), any());
  }

  @Test
  void promptAgentFailureCauseSecretNeverAppearsInUnknownMessage() {
    // 真实 H3 失败 cause 可能带 synthetic key/路径/URL 原文；UNKNOWN 分类只声明结果未知。
    String secret = "synthetic-secret path/to/agent.conf https://evil.example/leak";
    when(promptTasks.submit(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException(secret));

    CanvasFunctionUnknownException unknown =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () -> adapter.submit(new RecordingContext(), run("QUEUED", Map.of())));

    assertEquals("H3 Prompt Agent submission outcome unknown", unknown.getMessage());
    assertFalse(unknown.getMessage().contains("synthetic-secret"));
    assertNull(unknown.getCause());
  }

  @Test
  void comfySubmitFailureCauseSecretNeverAppearsInUnknownMessage() {
    // ComfyUI 提交失败同样不得把远端/请求原文拼进 UNKNOWN，且 checkpoint 停在提交前。
    String secret = "synthetic-secret path/to/agent.conf https://evil.example/leak";
    when(comfy.upload(anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(new H3UploadedFile("11.png", "kk-studio/7", "input"));
    when(comfy.submit(any(), anyString())).thenThrow(new IllegalStateException(secret));

    RecordingContext context = new RecordingContext();
    CanvasFunctionUnknownException unknown =
        assertThrows(
            CanvasFunctionUnknownException.class,
            () ->
                adapter.submit(
                    context,
                    run(
                        "QUEUED",
                        H3AdapterState.empty()
                            .withSeed(1L)
                            .withEnhancedPrompt("enhanced")
                            .encode())));

    assertEquals("H3 ComfyUI submission outcome unknown", unknown.getMessage());
    assertFalse(unknown.getMessage().contains("synthetic-secret"));
    assertNull(unknown.getCause());
    assertEquals(
        List.of(
            MiniMaxH3CanvasFunctionAdapter.COMFY_UPLOADING,
            MiniMaxH3CanvasFunctionAdapter.COMFY_UPLOADING,
            MiniMaxH3CanvasFunctionAdapter.COMFY_SUBMITTING),
        context.stages);
    verify(comfy, never()).history(anyString());
  }

  private static long contextSeed(RecordingContext context) {
    Object seed = context.lastAdapterState.get("seed");
    return ((Number) seed).longValue();
  }

  /** 提交前的输入准备失败仍按 FAILED 清理 staged media，且不得调用 submit。 */
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

    verify(promptResources).discard(new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID));
    verify(promptTasks, never()).submit(anyString(), anyString(), any());
  }

  /** 关闭原流失败发生在句柄已产生之后：该句柄必须已被登记清理，绝不留给耐久过期兜底。 */
  @Test
  void failingStreamCloseStillDiscardsItsStagedHandle() {
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    InputStream content = new ByteArrayInputStream(new byte[] {1, 2, 3});
    when(context.openOriginal(any()))
        .thenReturn(
            new CanvasFunctionResourceStream(
                content,
                3L,
                () -> {
                  throw new IOException("close failed");
                }));
    PluginPromptResources.StagedResource handle =
        new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID);
    when(promptResources.stage(any(), any(), any(InputStream.class), anyLong())).thenReturn(handle);

    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.submit(context, run("QUEUED", Map.of(), List.of(imageReference(RESOURCE)))));

    verify(promptResources).discard(handle);
    verify(promptTasks, never()).submit(anyString(), anyString(), any());
  }

  /** 后续准备失败必须清理每一个已登记句柄：前面成功的引用不能因为后面的引用失败而泄漏。 */
  @Test
  void laterPreparationFailureDiscardsEveryAlreadyStagedHandle() {
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    PluginPromptResources.StagedResource firstHandle =
        new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID);
    PluginPromptResources.StagedResource secondHandle =
        new PluginPromptResources.StagedResource(new UUID(0L, 61L), new UUID(0L, 62L));
    when(context.openOriginal(any()))
        .thenReturn(stream())
        .thenReturn(stream())
        .thenThrow(new IllegalStateException("third source unavailable"));
    when(promptResources.stage(any(), any(), any(InputStream.class), anyLong()))
        .thenReturn(firstHandle, secondHandle);

    assertThrows(
        IllegalStateException.class,
        () ->
            adapter.submit(
                context,
                run(
                    "QUEUED",
                    Map.of(),
                    List.of(
                        imageReference(RESOURCE),
                        imageReference(SECOND_RESOURCE),
                        imageReference(THIRD_RESOURCE)))));

    verify(promptResources).discard(firstHandle);
    verify(promptResources).discard(secondHandle);
    verify(promptTasks, never()).submit(anyString(), anyString(), any());
  }

  /** 主失败是 Error 时身份必须原样保留：清理抛出的 Error 只作为 suppressed 附加，剩余句柄仍然继续清理。 */
  @Test
  void primaryErrorIsPreservedWhileCleanupErrorsAreSuppressedAndCleanupContinues() {
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    PluginPromptResources.StagedResource firstHandle =
        new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID);
    PluginPromptResources.StagedResource secondHandle =
        new PluginPromptResources.StagedResource(new UUID(0L, 61L), new UUID(0L, 62L));
    AssertionError primary = new AssertionError("third source unavailable");
    LinkageError cleanupError = new LinkageError("discard failed");
    when(context.openOriginal(any())).thenReturn(stream()).thenReturn(stream()).thenThrow(primary);
    when(promptResources.stage(any(), any(), any(InputStream.class), anyLong()))
        .thenReturn(firstHandle, secondHandle);
    doThrow(cleanupError).when(promptResources).discard(firstHandle);

    AssertionError propagated =
        assertThrows(
            AssertionError.class,
            () ->
                adapter.submit(
                    context,
                    run(
                        "QUEUED",
                        Map.of(),
                        List.of(
                            imageReference(RESOURCE),
                            imageReference(SECOND_RESOURCE),
                            imageReference(THIRD_RESOURCE)))));

    assertSame(primary, propagated, "the primary Error identity must survive cleanup");
    assertArrayEquals(new Throwable[] {cleanupError}, propagated.getSuppressed());
    verify(promptResources).discard(firstHandle);
    verify(promptResources).discard(secondHandle);
    verify(promptTasks, never()).submit(anyString(), anyString(), any());
  }

  /** 清理期的 RuntimeException 只按去敏类型日志：它绝不进入主失败的传播链，主失败消息原样保留。 */
  @Test
  void cleanupRuntimeFailureNeverEntersThePrimaryFailureChain() {
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    PluginPromptResources.StagedResource handle =
        new PluginPromptResources.StagedResource(UPLOAD_ID, BLOB_ID);
    when(context.openOriginal(any()))
        .thenReturn(stream())
        .thenThrow(new IllegalStateException("second source unavailable"));
    when(promptResources.stage(any(), any(), any(InputStream.class), anyLong())).thenReturn(handle);
    doThrow(new IllegalStateException("upload already consumed"))
        .when(promptResources)
        .discard(handle);

    IllegalStateException propagated =
        assertThrows(
            IllegalStateException.class,
            () ->
                adapter.submit(
                    context,
                    run(
                        "QUEUED",
                        Map.of(),
                        List.of(imageReference(RESOURCE), imageReference(SECOND_RESOURCE)))));

    assertEquals("second source unavailable", propagated.getMessage());
    assertEquals(0, propagated.getSuppressed().length);
    verify(promptResources).discard(handle);
    verify(promptTasks, never()).submit(anyString(), anyString(), any());
  }

  private static CanvasFunctionResourceStream stream() {
    InputStream content = new ByteArrayInputStream(new byte[] {1, 2, 3});
    return new CanvasFunctionResourceStream(content, 3L, content::close);
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
    verify(promptTasks, never()).submit(anyString(), anyString(), any());

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
    doThrow(new IllegalStateException("stop failed")).when(promptTasks).stop(THREAD_ID);
    adapter.cancel(run(MiniMaxH3CanvasFunctionAdapter.COMFY_WAITING, cancelState.encode()));
    verify(promptTasks).stop(THREAD_ID);
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
    verify(promptTasks).stop(THREAD_ID);
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
        new CanvasFunctionBinding(1L, adapter.functions().get(0), null),
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
