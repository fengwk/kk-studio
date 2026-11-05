package fun.fengwk.kkstudio.plugin.opencli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliAdapterState.UploadedInput;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.Execution;
import fun.fengwk.kkstudio.plugin.opencli.OpenCliHubClient.UploadedResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Checkpoint、上传恢复和配置边界必须在调用 provider 前 fail closed。 */
class OpenCliAdapterSupportTest {

  private static final UUID RESOURCE_7 = new UUID(0L, 7L);
  private static final UUID RESOURCE_100 = new UUID(0L, 100L);
  private static final UUID RESOURCE_101 = new UUID(0L, 101L);
  private static final UUID RESOURCE_999 = new UUID(0L, 999L);

  @Test
  void adapterStateRoundTripsUploadsAndRejectsMalformedCheckpointValues() {
    Map<String, Object> state = new LinkedHashMap<>();
    assertNull(OpenCliAdapterState.optionalString(state, "missing"));
    state.put("text", "value");
    assertEquals("value", OpenCliAdapterState.optionalString(state, "text"));
    state.put("text", " ");
    assertThrows(
        IllegalArgumentException.class, () -> OpenCliAdapterState.optionalString(state, "text"));
    state.put("text", 1);
    assertThrows(
        IllegalArgumentException.class, () -> OpenCliAdapterState.optionalString(state, "text"));

    List<UploadedInput> expected = List.of(new UploadedInput(RESOURCE_7, "/resources/date/a.png"));
    OpenCliAdapterState.putUploads(state, expected);
    assertEquals(expected, OpenCliAdapterState.uploads(state));
    assertEquals(
        Map.of("resourceId", RESOURCE_7.toString(), "resourcePath", "/resources/date/a.png"),
        ((List<?>) state.get("uploads")).get(0));

    for (Object malformed :
        List.of(
            "not-an-array",
            List.of("not-an-object"),
            List.of(Map.of("resourceId", "0", "resourcePath", "/resources/a.png")),
            List.of(Map.of("resourceId", "7", "resourcePath", "/outside/a.png")),
            List.of(Map.of("resourceId", "7", "resourcePath", "/resources/%zz")),
            List.of(
                Map.of(
                    "resourceId",
                    "00000000-0000-0000-0000-00000000000A",
                    "resourcePath",
                    "/resources/a.png")),
            List.of(
                Map.of(
                    "resourceId",
                    "999999999999999999999999",
                    "resourcePath",
                    "/resources/a.png")))) {
      state.put("uploads", malformed);
      assertThrows(IllegalArgumentException.class, () -> OpenCliAdapterState.uploads(state));
    }
  }

  @Test
  void uploaderResumesOnlyMissingManifestEntriesAndCheckpointsConsistentState() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    OpenCliReferenceUploader uploader = new OpenCliReferenceUploader(client);
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    CanvasFunctionFrozenReference first = image(100L, "first.png");
    CanvasFunctionFrozenReference second = image(101L, "second.png");
    CanvasFunctionFrozenRun run = mock(CanvasFunctionFrozenRun.class);
    when(run.manifest()).thenReturn(List.of(first, second));
    when(context.isRunning()).thenReturn(true);
    when(context.openOriginal(second))
        .thenReturn(
            new CanvasFunctionResourceStream(
                new ByteArrayInputStream(new byte[] {1, 2}), 2L, () -> {}));
    when(client.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(new UploadedResource("/resources/date/second.png"));
    Map<String, Object> state = new LinkedHashMap<>();
    OpenCliAdapterState.putUploads(
        state, List.of(new UploadedInput(RESOURCE_100, "/resources/date/first.png")));

    OpenCliReferenceUploader.UploadResult result = uploader.uploadAll(context, run, state);

    assertEquals(
        List.of(
            new UploadedInput(RESOURCE_100, "/resources/date/first.png"),
            new UploadedInput(RESOURCE_101, "/resources/date/second.png")),
        result.uploads());
    verify(context).openOriginal(second);
    verify(context, never()).openOriginal(first);
    ArgumentCaptor<String> stages = ArgumentCaptor.forClass(String.class);
    verify(context, times(2)).checkpoint(stages.capture(), any());
    assertEquals(List.of("INPUT_UPLOADING", "INPUTS_UPLOADED"), stages.getAllValues());
  }

  @Test
  void uploaderRejectsInconsistentOrStoppedRecoveryAndWrapsCloseFailure() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    OpenCliReferenceUploader uploader = new OpenCliReferenceUploader(client);
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    CanvasFunctionFrozenReference reference = image(100L, "a.png");
    CanvasFunctionFrozenRun run = mock(CanvasFunctionFrozenRun.class);
    when(run.manifest()).thenReturn(List.of(reference));

    Map<String, Object> tooMany = new LinkedHashMap<>();
    OpenCliAdapterState.putUploads(
        tooMany,
        List.of(
            new UploadedInput(RESOURCE_100, "/resources/a.png"),
            new UploadedInput(RESOURCE_101, "/resources/b.png")));
    assertThrows(IllegalArgumentException.class, () -> uploader.uploadAll(context, run, tooMany));

    Map<String, Object> wrongOrder = new LinkedHashMap<>();
    OpenCliAdapterState.putUploads(
        wrongOrder, List.of(new UploadedInput(RESOURCE_999, "/resources/a.png")));
    assertThrows(
        IllegalArgumentException.class, () -> uploader.uploadAll(context, run, wrongOrder));

    when(context.isRunning()).thenReturn(false);
    assertThrows(
        IllegalStateException.class, () -> uploader.uploadAll(context, run, new LinkedHashMap<>()));
    verify(client, never()).upload(anyString(), anyString(), anyLong(), any(InputStream.class));

    CanvasFunctionExecutionContext closeFailureContext = mock(CanvasFunctionExecutionContext.class);
    when(closeFailureContext.isRunning()).thenReturn(true);
    when(closeFailureContext.openOriginal(reference))
        .thenReturn(
            new CanvasFunctionResourceStream(
                new ByteArrayInputStream(new byte[] {1}),
                1L,
                () -> {
                  throw new IOException("close failed");
                }));
    when(client.upload(anyString(), anyString(), anyLong(), any(InputStream.class)))
        .thenReturn(new UploadedResource("/resources/a.png"));
    assertThrows(
        UncheckedIOException.class,
        () -> uploader.uploadAll(closeFailureContext, run, new LinkedHashMap<>()));
  }

  /** 验证已上传引用的完整性校验必须完全覆盖 frozen manifest 且 ID 一致。 */
  @Test
  void requireCompleteUploadsValidatesManifestCoverageAndOrder() {
    CanvasFunctionFrozenRun run = mock(CanvasFunctionFrozenRun.class);
    when(run.manifest()).thenReturn(List.of(image(100L, "first.png"), image(101L, "second.png")));

    // 上传数量不足时报错
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OpenCliAdapterState.requireCompleteUploads(
                run, List.of(new UploadedInput(RESOURCE_100, "/resources/first.png"))));

    // 上传 resourceId 与 manifest 不匹配时报错
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OpenCliAdapterState.requireCompleteUploads(
                run,
                List.of(
                    new UploadedInput(RESOURCE_101, "/resources/second.png"),
                    new UploadedInput(RESOURCE_100, "/resources/first.png"))));

    // 匹配时正常通过
    assertDoesNotThrow(
        () ->
            OpenCliAdapterState.requireCompleteUploads(
                run,
                List.of(
                    new UploadedInput(RESOURCE_100, "/resources/first.png"),
                    new UploadedInput(RESOURCE_101, "/resources/second.png"))));
  }

  /** 验证 Hub 执行等待轮询逻辑，确保正常完成、运行终止中断以及携带前缀的超时异常正确抛出。 */
  @Test
  void awaitTerminalPollsUntilCompletionOrThrowsPrefixTimeout() {
    OpenCliHubClient client = mock(OpenCliHubClient.class);
    CanvasFunctionExecutionContext context = mock(CanvasFunctionExecutionContext.class);
    when(context.isRunning()).thenReturn(true);

    Execution running =
        new Execution("exec-1", OpenCliHubClient.ExecutionStatus.RUNNING, "", "", List.of());
    Execution succeeded =
        new Execution("exec-1", OpenCliHubClient.ExecutionStatus.SUCCEEDED, "ok", "", List.of());
    when(client.getExecution(anyString(), anyInt())).thenReturn(running, succeeded);

    // 成功轮询完成
    Execution result =
        OpenCliAdapterState.awaitTerminal(
            client, context, "exec-1", Duration.ofMinutes(5), "TestPrefix");
    assertEquals(succeeded, result);
    verify(client).getExecution("exec-1", 120);

    // 超时抛出带有指定前缀的 OpenCliHubException
    when(client.getExecution("exec-timeout", 0)).thenReturn(running);
    when(client.getExecution("exec-timeout", 1)).thenReturn(running);
    OpenCliHubException timeoutEx =
        assertThrows(
            OpenCliHubException.class,
            () ->
                OpenCliAdapterState.awaitTerminal(
                    client, context, "exec-timeout", Duration.ZERO, "CustomService"));
    assertEquals("CustomService Hub execution exceeded maximum wait", timeoutEx.getMessage());

    // 运行态中止抛出 IllegalStateException
    when(client.getExecution("exec-stopped", 0)).thenReturn(running);
    CanvasFunctionExecutionContext stoppedContext = mock(CanvasFunctionExecutionContext.class);
    when(stoppedContext.isRunning()).thenReturn(false);
    IllegalStateException stoppedEx =
        assertThrows(
            IllegalStateException.class,
            () ->
                OpenCliAdapterState.awaitTerminal(
                    client,
                    stoppedContext,
                    "exec-stopped",
                    Duration.ofMinutes(1),
                    "CustomService"));
    assertEquals("Canvas Function run is no longer running", stoppedEx.getMessage());
  }

  /** 验证规范虚拟资源路径谓词对合法格式与非法格式的判定。 */
  @Test
  void canonicalResourcePathValidatesVirtualPathCorrectly() {
    assertTrue(OpenCliHubClient.isCanonicalResourcePath("/resources/2026-08-10/a.png"));
    assertTrue(OpenCliHubClient.isCanonicalResourcePath("/resources/sub/dir/res.bin"));

    assertFalse(OpenCliHubClient.isCanonicalResourcePath(null));
    assertFalse(OpenCliHubClient.isCanonicalResourcePath(""));
    assertFalse(OpenCliHubClient.isCanonicalResourcePath("/api/resources/a.png"));
    assertFalse(OpenCliHubClient.isCanonicalResourcePath("/resources/a.png?query=1"));
    assertFalse(OpenCliHubClient.isCanonicalResourcePath("/resources/a.png#fragment"));
    assertFalse(OpenCliHubClient.isCanonicalResourcePath("/resources/../outside.png"));
    assertFalse(OpenCliHubClient.isCanonicalResourcePath("/resources/./inside.png"));
    assertFalse(OpenCliHubClient.isCanonicalResourcePath("/resources/%zz"));
  }

  private static CanvasFunctionFrozenReference image(long resourceId, String name) {
    UUID resource = new UUID(0L, resourceId);
    return new CanvasFunctionFrozenReference(
        new UUID(0L, 1L),
        0,
        resource,
        resource,
        CanvasResourceKind.IMAGE,
        name,
        "image/png",
        2L,
        null,
        null,
        null);
  }
}
