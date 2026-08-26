package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link PlatformToolGateway} 回调桥：partial / terminal / error / cancel 语义与 managed Resource 外部化。
 *
 * <p>PLATFORM 路径由 FakeTool 的 handler 在 execute 内同步投递回调（gate 已打开，直达 listener）；ENVIRONMENT 路径 由测试直接驱动
 * FakeTransport 记录的 listener。
 */
class PlatformToolGatewayCallbackTest {

  private static final ToolDescriptor DESCRIPTOR =
      ToolGatewayTestSupport.platformDescriptor("demo");

  private static final byte[] BINARY_BYTES = new byte[] {1, 2, 3, 4, 5};
  private static final String LARGE_TEXT =
      "x".repeat(ToolResultExternalizer.INLINE_RESULT_UTF8_BYTES + 1);
  private static final String LARGE_JSON =
      "{\"data\":\"" + "y".repeat(ToolResultExternalizer.INLINE_RESULT_UTF8_BYTES) + "\"}";
  private static final UUID ID = new UUID(0L, 1L);

  @Test
  void binaryContentIsExternalizedBeforeTerminalDelivery() {
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    // store 收到一次 put：mediaType 原样、内容精确；展示名稳定有用。
    assertEquals(1, listener.store.puts.size());
    ToolGatewayTestSupport.FakeResourceStore.PutRecord put = listener.store.puts.get(0);
    assertEquals("image/png", put.mediaType());
    assertEquals("demo-result-1", put.name());
    assertArrayEquals(BINARY_BYTES, put.content());
    // 交付结果只剩 ResourceToolContent，原始 error 语义保持不变。
    assertEquals(1, succeeded.result().contents().size());
    ResourceToolContent externalized =
        assertInstanceOf(ResourceToolContent.class, succeeded.result().contents().get(0));
    assertEquals(put.content().length, externalized.resource().size());
    assertEquals(ToolGatewayTestSupport.sha256(BINARY_BYTES), externalized.resource().sha256());
    assertNull(externalized.preview());
    assertFalse(succeeded.result().error());
  }

  @Test
  void largeTextAndJsonAreExternalizedWithUsefulNamesAndInlineStaysInline() {
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(
                new TextToolContent("small"),
                new TextToolContent(LARGE_TEXT),
                new JsonToolContent(LARGE_JSON)),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertEquals(2, listener.store.puts.size());
    // 大文本 -> text/plain + .txt 名；大 JSON -> application/json + .json 名；UTF-8 字节精确。
    ToolGatewayTestSupport.FakeResourceStore.PutRecord textPut = listener.store.puts.get(0);
    assertEquals("text/plain", textPut.mediaType());
    assertEquals("demo-result-2.txt", textPut.name());
    assertArrayEquals(LARGE_TEXT.getBytes(StandardCharsets.UTF_8), textPut.content());
    ToolGatewayTestSupport.FakeResourceStore.PutRecord jsonPut = listener.store.puts.get(1);
    assertEquals("application/json", jsonPut.mediaType());
    assertEquals("demo-result-3.json", jsonPut.name());
    assertArrayEquals(LARGE_JSON.getBytes(StandardCharsets.UTF_8), jsonPut.content());
    // 小文本保持内联；两个外部化内容替换为 ResourceToolContent。
    assertEquals(3, succeeded.result().contents().size());
    assertEquals(new TextToolContent("small"), succeeded.result().contents().get(0));
    ResourceToolContent externalizedText =
        assertInstanceOf(ResourceToolContent.class, succeeded.result().contents().get(1));
    ResourceToolContent externalizedJson =
        assertInstanceOf(ResourceToolContent.class, succeeded.result().contents().get(2));
    assertEquals(LARGE_TEXT, externalizedText.preview());
    assertEquals(LARGE_JSON, externalizedJson.preview());
  }

  @Test
  void inlineThresholdBoundaryIsExact() {
    String atThreshold = "x".repeat(ToolResultExternalizer.INLINE_RESULT_UTF8_BYTES);
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(new TextToolContent(atThreshold), new TextToolContent(atThreshold + "x")),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result);
    // 恰好 8192 字节保持内联；8193 字节外部化。
    assertEquals(1, listener.store.puts.size());
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertEquals(2, succeeded.result().contents().size());
    assertEquals(new TextToolContent(atThreshold), succeeded.result().contents().get(0));
    assertInstanceOf(ResourceToolContent.class, succeeded.result().contents().get(1));
  }

  /** 恰好 16 KiB 的外部化文本完整保留为 preview，不添加截断标记。 */
  @Test
  void externalizedTextAtPreviewLimitKeepsExactPreview() {
    String text = "x".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES);
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncComplete(
            new ToolResult("call-1", List.of(new TextToolContent(text)), false, "{}"));
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);

    ResourceToolContent resource =
        assertInstanceOf(ResourceToolContent.class, succeeded.result().contents().getFirst());
    assertEquals(text, resource.preview());
    assertEquals(
        ResourceRef.MAX_PREVIEW_UTF8_BYTES, ResourceRef.utf8Length(resource.preview(), "preview"));
  }

  /** 超过 16 KiB 时按 code point 截断并追加稳定标记，不拆分 surrogate 或 UTF-8 序列。 */
  @Test
  void oversizedTextGetsDeterministicUtf8SafePreview() {
    int markerBytes =
        ResourceRef.utf8Length(
            ToolResultExternalizer.PREVIEW_TRUNCATION_MARKER, "preview truncation marker");
    int prefixBudget = ResourceRef.MAX_PREVIEW_UTF8_BYTES - markerBytes;
    String text = "a".repeat(prefixBudget - 1) + "😀" + "tail".repeat(1024);
    String expected =
        "a".repeat(prefixBudget - 1) + ToolResultExternalizer.PREVIEW_TRUNCATION_MARKER;

    ToolGatewayTestSupport.RecordingListener first =
        runPlatformSyncComplete(
            new ToolResult("call-1", List.of(new TextToolContent(text)), false, "{}"));
    ToolGatewayTestSupport.RecordingListener second =
        runPlatformSyncComplete(
            new ToolResult("call-1", List.of(new TextToolContent(text)), false, "{}"));
    ResourceToolContent firstResource =
        (ResourceToolContent)
            ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) first.events.get(0))
                .result()
                .contents()
                .getFirst();
    ResourceToolContent secondResource =
        (ResourceToolContent)
            ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) second.events.get(0))
                .result()
                .contents()
                .getFirst();

    assertEquals(expected, firstResource.preview());
    assertEquals(firstResource.preview(), secondResource.preview());
    assertTrue(
        ResourceRef.utf8Length(firstResource.preview(), "preview")
            <= ResourceRef.MAX_PREVIEW_UTF8_BYTES);
  }

  /** 9,926-byte read/bash 类输出虽被外部化，仍经 durable RESOURCE（preview 完整保留）与 provider 投影完整暴露给模型。 */
  @Test
  void externalized9926ByteTextRemainsFullyVisibleToProviderModel() {
    String text = "0123456789".repeat(992) + "123456";
    assertEquals(9926, text.getBytes(StandardCharsets.UTF_8).length);
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncComplete(
            new ToolResult("call-1", List.of(new TextToolContent(text)), false, "{}"));
    ToolResult externalized =
        ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0))
            .result();
    ToolInvocation invocation =
        new ToolInvocation(
            ID,
            ID,
            ID,
            0,
            ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR).call(),
            ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR).binding(),
            ToolInvocationStatus.SUCCEEDED,
            1,
            ToolApproval.notRequired(),
            externalized,
            null,
            Instant.EPOCH,
            Instant.EPOCH);

    // 物化端口（全局 Blob 摄入）会把外部化文本映射为 durable ResourceMessageContent；9926 字节 ≤ 16 KiB，
    // preview 完整保留正文，模型在 provider attempt 仍能看到全文。
    MessagePayload payload =
        new HistoryPayloadMapper()
            .toolResultPayload(
                invocation, List.of(new ResourceMessageContent(ID, "demo-result-1.txt", text)));
    ProviderToolResultBlock projected =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            new ProviderMessageProjector()
                .project(List.of(payload.message()))
                .getFirst()
                .contents()
                .getFirst());
    assertEquals(
        new ProviderResourceBlock(ID, "demo-result-1.txt", text), projected.contents().getFirst());
  }

  @Test
  void existingResourceContentPassesThroughWithoutStoreWrite() {
    byte[] existing = new byte[] {1, 2, 3};
    ResourceRef ref =
        new ResourceRef(
            "file:///resources/" + ToolGatewayTestSupport.sha256(existing),
            "text/plain",
            "existing",
            (long) existing.length,
            ToolGatewayTestSupport.sha256(existing));
    ResourceToolContent existingContent = new ResourceToolContent(ref, "existing preview");
    ToolResult result =
        new ToolResult(
            "call-1", List.of(existingContent, new TextToolContent("inline")), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result);
    assertTrue(listener.store.puts.isEmpty());
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertSame(existingContent, succeeded.result().contents().get(0));
  }

  @Test
  void semanticErrorResultIsPreservedAndStillExternalized() {
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(new BinaryToolContent("application/octet-stream", BINARY_BYTES)),
            true,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertTrue(succeeded.result().error());
    assertEquals(1, listener.store.puts.size());
  }

  @Test
  void partialWithBinaryIsRejectedWithoutAnyStoreIo() {
    ToolResult partial =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformPartial(partial);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    assertTrue(listener.store.puts.isEmpty(), "partial 不得产生任何存储 I/O");
  }

  @Test
  void partialWithResourceIsRejectedWithoutAnyStoreIo() {
    byte[] existing = new byte[] {1, 2, 3};
    ResourceRef ref =
        new ResourceRef(
            "file:///resources/" + ToolGatewayTestSupport.sha256(existing),
            "text/plain",
            null,
            (long) existing.length,
            ToolGatewayTestSupport.sha256(existing));
    ToolResult partial =
        new ToolResult("call-1", List.of(new ResourceToolContent(ref)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformPartial(partial);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertTrue(listener.store.puts.isEmpty());
  }

  @Test
  void plainPartialIsForwardedAsIs() {
    ToolResult partial =
        new ToolResult("call-1", List.of(new TextToolContent("progress")), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformPartial(partial);
    ToolGatewayTestSupport.RecordingListener.Event.Partial delivered =
        (ToolGatewayTestSupport.RecordingListener.Event.Partial) listener.events.get(0);
    assertEquals(partial, delivered.partial());
  }

  @Test
  void storeIoFailureAfterAdmissionMapsToUnknown() {
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    store.putFailure = new IllegalStateException("disk full");
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result, store);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("RESOURCE_STORE_FAILED", unknown.error().kind());
  }

  @Test
  void storeReferenceInvalidInputIsDeterministicInvalidResult() {
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    store.referenceFailure = new IllegalArgumentException("content too large");
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result, store);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    assertTrue(store.puts.isEmpty(), "reference rejection must happen before any put");
  }

  @Test
  void storePutIllegalArgumentAfterPlanningMapsToUnknown() {
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    store.putFailure = new IllegalArgumentException("store contract violation");
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result, store);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("RESOURCE_STORE_FAILED", unknown.error().kind());
  }

  @Test
  void invalidBinaryMediaTypeIsRejectedBeforeAnyPut() {
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(
                new BinaryToolContent("Image/PNG", BINARY_BYTES),
                new BinaryToolContent("image/png", BINARY_BYTES)),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result);
    // 全有或全无：第一个合法 put 之前就因非法 mediaType 拒绝，零存储副作用。
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertTrue(listener.store.puts.isEmpty());
  }

  @Test
  void cancelledErrorMapsToCancelled() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new EnvironmentCapabilityCancelledException("user stopped"));
    ToolGatewayTestSupport.RecordingListener.Event.Cancelled cancelled =
        (ToolGatewayTestSupport.RecordingListener.Event.Cancelled) listener.events.get(0);
    assertEquals("CANCELLED", cancelled.error().kind());
  }

  @Test
  void uncertainErrorMapsToUnknown() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new EnvironmentCapabilitySendUncertainException("connection lost"));
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("REMOTE_UNCERTAIN", unknown.error().kind());
  }

  @Test
  void unavailableErrorIsRetryableFailure() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new EnvironmentCapabilityUnavailableException("environment offline"));
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("UNAVAILABLE", failed.failure().error().kind());
    assertTrue(failed.failure().retryable());
  }

  @Test
  void daemonFailedErrorIsNonRetryableKnownFailure() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new EnvironmentCapabilityFailedException("tool blew up"));
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", failed.failure().error().kind());
    assertEquals("tool blew up", failed.failure().error().message());
    assertFalse(failed.failure().retryable());
  }

  @Test
  void genericErrorMapsToUnknownNotKnownFailure() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new IllegalStateException("tool failed"));
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
  }

  @Test
  void invalidArgumentErrorIsUnclassifiedUnknown() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new IllegalArgumentException("bad arguments"));
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("bad arguments"));
  }

  @Test
  void throwingExecuteMapsToUnknown() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler =
        (request, listener) -> {
          throw new IllegalStateException("init failed");
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      PlatformToolGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.factories(tool),
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStore(),
              executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
              listener);
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
      // execute 抛异常无法确认是否产生副作用：UNKNOWN，绝不是已知 FAILED。
      startedResult.handle().activate();
      listener.awaitCount(1);
      ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
          (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
      assertEquals("EXECUTION_FAILED", unknown.error().kind());
      assertTrue(unknown.error().message().contains("init failed"));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void duplicateAndLateTerminalAreIgnoredAfterFirstTerminal() {
    ToolResult first =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolResult second =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", new byte[] {9, 9})), false, "{}");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler =
        (request, listener) -> {
          listener.onComplete(first);
          listener.onComplete(second);
          listener.onError(new IllegalStateException("late error"));
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // 桥 terminal-once：只有第一个 terminal 被投递并外部化，重复 complete / 迟到 error 一律忽略。
    listener.awaitCount(1);
    assertInstanceOf(
        ToolGatewayTestSupport.RecordingListener.Event.Succeeded.class, listener.events.get(0));
    assertEquals(
        1, store.puts.size(), "duplicate terminal must not trigger a second resource write");
  }

  @Test
  void cancelBeforeToolHandleAttachCancelsImmediatelyAfterAttach() throws Exception {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    tool.handler =
        (request, listener) -> {
          entered.countDown();
          try {
            assertTrue(release.await(10, TimeUnit.SECONDS));
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      PlatformToolGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.factories(tool),
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStore(),
              executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
      // 两阶段激活后 Tool 才运行；execute 尚未返回 handle：cancel 只记录意图。
      startedResult.handle().activate();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      startedResult.handle().cancel();
      startedResult.handle().cancel();
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((tool.handles.isEmpty() || !tool.handles.get(0).isCancelled())
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(1, tool.handles.size());
      // attach 后立即取消；重复 cancel 幂等。
      assertTrue(tool.handles.get(0).isCancelled());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void cancelAfterAttachCancelsTransportHandleIdempotently() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // invoke 同步返回的 handle 已 attach：cancel 直达 transport handle，重复调用幂等。
    assertEquals(1, transport.returnedHandles.size());
    startedResult.handle().cancel();
    startedResult.handle().cancel();
    assertTrue(transport.returnedHandles.get(0).isCancelled());
  }

  @Test
  void nullPartialIsRejectedAsInvalidPartial() {
    ToolGatewayTestSupport.RecordingListener listener = runPlatformPartial(null);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertEquals("partial must not be null", failed.failure().error().message());
    assertFalse(failed.failure().retryable());
  }

  @Test
  void nullTerminalResultIsRejectedAsInvalidResult() {
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(null);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertEquals("terminal result must not be null", failed.failure().error().message());
    assertFalse(failed.failure().retryable());
  }

  @Test
  void nullPlatformToolHandleIsPostAcceptanceUnknown() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.returnNullHandle = true;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // execute 返回 null handle：已接受的执行结果无法确认，恰好一次 UNKNOWN。
    assertEquals(1, tool.requests.size());
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
  }

  @Test
  void synchronousTerminalWinsOverNullPlatformToolHandle() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.returnNullHandle = true;
    tool.handler =
        (request, listener) ->
            listener.onComplete(
                new ToolResult(
                    "call-1",
                    List.of(new BinaryToolContent("image/png", BINARY_BYTES)),
                    false,
                    "{}"));
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // execute 内同步 terminal 先于 null-handle 失败入队：FIFO/terminal-once 下 terminal 获胜，null-handle 信号被丢弃。
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertEquals("call-1", succeeded.result().toolCallId());
    assertEquals(1, store.puts.size(), "terminal externalization is unaffected by the null handle");
  }

  @Test
  void nullMessageUnavailableErrorUsesFallbackMessage() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new EnvironmentCapabilityUnavailableException(null));
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("UNAVAILABLE", failed.failure().error().kind());
    assertEquals("Tool is unavailable.", failed.failure().error().message());
    assertTrue(failed.failure().retryable());
  }

  @Test
  void nullMessageGenericErrorMapsToUnknownWithFallbackMessage() {
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncError(new IllegalStateException());
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertEquals(
        "unclassified tool failure; outcome cannot be confirmed", unknown.error().message());
  }

  @Test
  void invalidPartialTerminallyFailsAndPreventsLaterResourceWrites() {
    ToolResult partial =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolResult complete =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    // 非法 partial 后 Tool 继续投递 terminal success：桥已 terminal，success 绝不外部化、绝不投递。
    tool.handler =
        (request, listener) -> {
          listener.onPartial(partial);
          listener.onComplete(complete);
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    assertTrue(store.puts.isEmpty(), "invalid partial must prevent later resource writes");
  }

  @Test
  void concurrentDuplicateTerminalsAreSerializedDeliveredOnceAndExternalizedOnce()
      throws Exception {
    ToolResult first =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolResult second =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", new byte[] {9, 9})), false, "{}");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler =
        (request, listener) -> {
          int threads = 6;
          CountDownLatch start = new CountDownLatch(1);
          CountDownLatch done = new CountDownLatch(threads);
          for (int i = 0; i < threads; i++) {
            ToolResult terminal = i % 2 == 0 ? first : second;
            new Thread(
                    () -> {
                      try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                      } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                      }
                      listener.onComplete(terminal);
                      done.countDown();
                    })
                .start();
          }
          start.countDown();
          try {
            assertTrue(done.await(10, TimeUnit.SECONDS));
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // 并发重复 terminal：串行 FIFO 下恰好一个 Succeeded（第一个被处理者），其余忽略。
    listener.awaitCount(1);
    assertInstanceOf(
        ToolGatewayTestSupport.RecordingListener.Event.Succeeded.class, listener.events.get(0));
    assertEquals(1, store.puts.size(), "only the accepted terminal may externalize resources");
    assertEquals(
        1, listener.maxConcurrency.get(), "bridge must never process callbacks in parallel");
  }

  @Test
  void laterTerminalCannotOvertakeAnInFlightPartial() throws Exception {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    CountDownLatch partialDelivered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<String> order = new ArrayList<>();
    ToolGateway.Listener listener =
        new ToolGateway.Listener() {
          @Override
          public void onPartial(ToolResult partial) {
            order.add("partial");
            partialDelivered.countDown();
            try {
              assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
          }

          @Override
          public void onSucceeded(ToolSuccess success) {
            order.add("succeeded");
          }

          @Override
          public void onFailed(ToolGateway.Failure failure) {
            order.add("failed");
          }

          @Override
          public void onCancelled(ToolInvocationError error) {
            order.add("cancelled");
          }

          @Override
          public void onUnknown(ToolInvocationError error) {
            order.add("unknown");
          }
        };
    tool.handler =
        (request, bridgeListener) -> {
          Thread partialThread =
              new Thread(
                  () -> bridgeListener.onPartial(ToolGatewayTestSupport.result("call-1", "p")));
          partialThread.start();
          try {
            assertTrue(partialDelivered.await(10, TimeUnit.SECONDS));
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
          Thread completeThread =
              new Thread(
                  () -> bridgeListener.onComplete(ToolGatewayTestSupport.result("call-1", "done")));
          completeThread.start();
          // partial 仍在 listener 内阻塞：terminal 只能排队，绝不能超车先投递。
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
          while (order.size() > 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
          }
          assertEquals(1, order.size(), "terminal must not overtake the in-flight partial");
          release.countDown();
          try {
            partialThread.join(5_000L);
            completeThread.join(5_000L);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        };
    ToolGatewayTestSupport.RecordingListener unused =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // FIFO：partial 先于 terminal 投递；terminal 恰好一次。
    assertEquals(List.of("partial", "succeeded"), order);
  }

  @Test
  void throwingListenerOnPartialMapsToUnknownTerminal() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler =
        (request, listener) -> listener.onPartial(ToolGatewayTestSupport.result("call-1", "boom"));
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    listener.throwOnPartial = true;
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // 已接受的 execution 上 listener 内部失败：结果无法确认，恰好一次 UNKNOWN terminal。
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
  }

  @Test
  void throwingOnSucceededIssuesNoSecondTerminalCallback() {
    RejectedTerminalRun run =
        runRejectedTerminal(
            listener -> listener.throwOnSucceeded = true,
            listener ->
                listener.onComplete(
                    new ToolResult(
                        "call-1",
                        List.of(new BinaryToolContent("image/png", BINARY_BYTES)),
                        false,
                        "{}")));
    // terminal 选择（Succeeded）已经发生：恰好一次 terminal 调用；外部化照常完成；listener 拒绝只记录，绝不追加 UNKNOWN。
    assertEquals(1, run.listener.terminalInvocations.get());
    assertEquals(1, run.store.puts.size());
    assertTrue(
        run.listener.events.isEmpty(),
        "throwing terminal listener must not yield a second terminal callback");
    run.bridge.get().onPartial(ToolGatewayTestSupport.result("call-1", "late"));
    assertEquals(
        1, run.listener.terminalInvocations.get(), "late signals must not resurrect a terminal");
    assertEquals(1, run.store.puts.size(), "late signals must not trigger resource writes");
  }

  @Test
  void throwingOnFailedIssuesNoSecondTerminalCallback() {
    RejectedTerminalRun run =
        runRejectedTerminal(
            listener -> listener.throwOnFailed = true,
            listener -> listener.onError(new EnvironmentCapabilityFailedException("tool blew up")));
    assertEquals(1, run.listener.terminalInvocations.get(), "onFailed threw: exactly one terminal");
    assertTrue(run.listener.events.isEmpty(), "no UNKNOWN may follow a rejected terminal");
    run.bridge.get().onError(new EnvironmentCapabilityCancelledException("late"));
    assertEquals(1, run.listener.terminalInvocations.get());
  }

  @Test
  void throwingOnCancelledIssuesNoSecondTerminalCallback() {
    RejectedTerminalRun run =
        runRejectedTerminal(
            listener -> listener.throwOnCancelled = true,
            listener ->
                listener.onError(new EnvironmentCapabilityCancelledException("user stopped")));
    assertEquals(
        1, run.listener.terminalInvocations.get(), "onCancelled threw: exactly one terminal");
    assertTrue(run.listener.events.isEmpty(), "no UNKNOWN may follow a rejected terminal");
    run.bridge.get().onComplete(ToolGatewayTestSupport.result("call-1", "late"));
    assertEquals(1, run.listener.terminalInvocations.get());
  }

  @Test
  void throwingOnUnknownIssuesNoSecondTerminalCallback() {
    RejectedTerminalRun run =
        runRejectedTerminal(
            listener -> listener.throwOnUnknown = true,
            listener -> listener.onError(new IllegalStateException("unclassified")));
    assertEquals(
        1, run.listener.terminalInvocations.get(), "onUnknown threw: exactly one terminal");
    assertTrue(run.listener.events.isEmpty(), "no second UNKNOWN may be issued");
    run.bridge.get().onError(new IllegalStateException("late"));
    assertEquals(1, run.listener.terminalInvocations.get());
  }

  /** Capability 重构不可退化的 fitness gate：Environment callback bridge 必须按到达顺序完整重放多个 PARTIAL。 */
  @Test
  void bufferedSignalsUpToLimitAreReplayedOnActivation() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_PARTIALS;
    transport.syncPartialCount = PlatformToolGateway.MAX_BUFFERED_SIGNALS;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            store,
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(listener.events.isEmpty(), "gate closed: nothing may be delivered during invoke");
    // 恰好达到上限：activate 后全部按 FIFO 重放，无 terminal、无 store 写入。
    startedResult.handle().activate();
    listener.awaitCount(PlatformToolGateway.MAX_BUFFERED_SIGNALS);
    assertEquals(0, listener.terminalInvocations.get(), "partials are never terminals");
    for (int index = 0; index < listener.events.size(); index++) {
      ToolGatewayTestSupport.RecordingListener.Event.Partial partial =
          assertInstanceOf(
              ToolGatewayTestSupport.RecordingListener.Event.Partial.class,
              listener.events.get(index));
      assertEquals(
          "progress-" + index, ((TextToolContent) partial.partial().contents().get(0)).text());
    }
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void environmentCapabilityCorrelationRestoresModelCallId() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "done");
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    listener.store = store;
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            store,
            new ToolGatewayTestSupport.ManualExecutor());

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-1", ToolGatewayTestSupport.ENV_A)),
                listener));
    started.handle().activate();
    listener.awaitCount(1);

    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.getFirst();
    assertEquals("call-1", succeeded.result().toolCallId());
    assertEquals("done", ((TextToolContent) succeeded.result().contents().getFirst()).text());
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void wrongEnvironmentCapabilityCorrelationFailsBeforeResourceExternalization() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.callbackCallId = "call-1";
    transport.syncResult =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    listener.store = store;
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            store,
            new ToolGatewayTestSupport.ManualExecutor());

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-1", ToolGatewayTestSupport.ENV_A)),
                listener));
    started.handle().activate();
    listener.awaitCount(1);

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.getFirst();
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertEquals(1, listener.terminalInvocations.get());
    assertTrue(store.puts.isEmpty(), "wrong correlation must be rejected before externalization");
  }

  @Test
  void bufferedOverflowSelectsExactlyOneUnknownAtActivationWithoutStoreWrites() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_PARTIALS;
    transport.syncPartialCount = PlatformToolGateway.MAX_BUFFERED_SIGNALS + 1;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(),
            transport,
            store,
            new ToolGatewayTestSupport.ManualExecutor());
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(listener.events.isEmpty(), "gate closed: nothing may be delivered during invoke");
    // 第 257 个信号溢出：缓冲被清空（有界），activate 时确定性选择恰好一次 UNKNOWN；绝无 partial 投递、绝无 store 写入。
    startedResult.handle().activate();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("overflowed"));
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
    assertEquals(1, listener.terminalInvocations.get(), "overflow selects exactly one terminal");
    assertTrue(store.puts.isEmpty(), "overflow must never touch the resource store");
    // 溢出后迟到信号一律丢弃，绝不出现第二个 terminal、绝不产生 store 副作用。
    EnvironmentCapabilityExecutionListener bridge = transport.invocations.get(0).listener();
    bridge.onComplete(ToolGatewayTestSupport.capabilityResult("call-1", "late done"));
    bridge.onPartial(ToolGatewayTestSupport.capabilityResult("call-1", "later"));
    assertEquals(1, listener.terminalInvocations.get());
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void malformedSurrogateTextIsDeterministicInvalidWithZeroPuts() {
    // 未配对高位代理项不是合法 Unicode：严格 Resource UTF-8 语义下确定性 INVALID_RESULT，零存储副作用。
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(new TextToolContent("prefix\uD800suffix"), new TextToolContent("ok")),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore());
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    assertTrue(listener.store.puts.isEmpty(), "malformed content must be rejected before any put");
  }

  @Test
  void wrongCallPartialThenBinaryTerminalYieldsOneInvalidPartialWithZeroPuts() {
    ToolResult partial =
        new ToolResult("wrong-call", List.of(new TextToolContent("progress")), false, "{}");
    ToolResult complete =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    // 非法 partial 后 Tool 继续投递二进制 terminal：桥已 terminal，success 绝不外部化、绝不投递。
    tool.handler =
        (request, listener) -> {
          listener.onPartial(partial);
          listener.onComplete(complete);
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertEquals(
        "partial toolCallId does not match the request call", failed.failure().error().message());
    assertFalse(failed.failure().retryable());
    assertTrue(
        store.puts.isEmpty(),
        "wrong-call partial must terminalize the bridge before any store side effect");
  }

  @Test
  void oversizedPartialThenBinaryTerminalYieldsOneInvalidPartialWithZeroPuts() {
    ToolResult partial =
        new ToolResult(
            "call-1",
            List.of(
                new TextToolContent(
                    "x".repeat(ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES))),
            false,
            "{}");
    ToolResult complete =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler =
        (request, listener) -> {
          listener.onPartial(partial);
          listener.onComplete(complete);
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertTrue(failed.failure().error().message().contains("must not exceed"));
    assertFalse(failed.failure().retryable());
    assertTrue(
        store.puts.isEmpty(),
        "oversized partial must terminalize the bridge before any store side effect");
  }

  @Test
  void wrongCallBinaryTerminalIsInvalidResultWithZeroPuts() {
    ToolResult complete =
        new ToolResult(
            "wrong-call", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncComplete(complete, new ToolGatewayTestSupport.FakeResourceStore());
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertEquals(
        "terminal result toolCallId does not match the request call",
        failed.failure().error().message());
    assertFalse(failed.failure().retryable());
    assertTrue(
        listener.store.puts.isEmpty(),
        "wrong-call terminal must be rejected before any store side effect");
  }

  @Test
  void projectedResultExceedingOneMiBIsRejectedBeforeAnyPut() {
    // 全部内联文本（63 × 8 KiB ≈ 504 KiB）+ 600 KiB detailsJson + 一个本会被外部化的二进制项：
    // 投影结果（外部化后）超过 canonical JSON 上限，必须在第一个 put 之前确定性拒绝。
    List<ToolContent> contents = new ArrayList<>(64);
    for (int i = 0; i < 63; i++) {
      contents.add(new TextToolContent("x".repeat(8 * 1024)));
    }
    contents.add(new BinaryToolContent("image/png", BINARY_BYTES));
    String details = "{\"data\":\"" + "y".repeat(600 * 1024) + "\"}";
    ToolResult result = new ToolResult("call-1", contents, false, details);
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore());
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertTrue(failed.failure().error().message().contains("must not exceed"));
    assertFalse(failed.failure().retryable());
    assertTrue(
        listener.store.puts.isEmpty(), "projected size rejection must happen before any put");
  }

  /** 外部化 preview 必须计入 pre-write 投影上限；64 个 16 KiB preview 在任何 put 前整体拒绝。 */
  @Test
  void projectedPreviewBytesAreCheckedBeforeAnyPut() {
    List<ToolContent> contents = new ArrayList<>(ToolResult.MAX_CONTENT_ITEMS);
    String text = "x".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES);
    for (int index = 0; index < ToolResult.MAX_CONTENT_ITEMS; index++) {
      contents.add(new TextToolContent(text));
    }
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();

    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncComplete(new ToolResult("call-1", contents, false, "{}"), store);

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.getFirst();
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertTrue(failed.failure().error().message().contains("must not exceed"));
    assertTrue(store.puts.isEmpty(), "preview size rejection must happen before any put");
  }

  @Test
  void plannedRefMismatchAfterPutMapsToUnknown() {
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    store.mismatchReturnedRef = true;
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryToolContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runPlatformSyncComplete(result, store);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("RESOURCE_STORE_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
    // put 已发生（副作用存在），契约违反按 UNKNOWN 收敛而不是伪装为确定性 INVALID_RESULT。
    assertEquals(1, store.puts.size(), "put side effect occurred before the contract mismatch");
  }

  @Test
  void nullErrorConvergesExactlyOneUnknownWithoutWedgingDispatch() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    AtomicReference<ToolExecutionListener> bridge = new AtomicReference<>();
    tool.handler =
        (request, listener) -> {
          bridge.set(listener);
          listener.onError(null);
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // onError(null)：无法确认执行结果，恰好一次 UNKNOWN。
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
    // 未 wedge：terminal 后的迟到信号立即被忽略，不阻塞、不产生新事件。
    bridge.get().onPartial(ToolGatewayTestSupport.result("call-1", "late"));
    assertEquals(1, listener.events.size());
  }

  @Test
  void adversarialErrorMessageConvergesExactlyOneUnknownWithoutWedgingDispatch() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    AtomicReference<ToolExecutionListener> bridge = new AtomicReference<>();
    tool.handler =
        (request, listener) -> {
          bridge.set(listener);
          listener.onError(
              new Throwable() {
                @Override
                public String getMessage() {
                  throw new IllegalStateException("adversarial message");
                }
              });
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    // getMessage 抛异常的错误：分发循环收敛恰好一次 UNKNOWN（adversarial detail 不影响收敛），绝不 wedge dispatching。
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
    bridge.get().onPartial(ToolGatewayTestSupport.result("call-1", "late"));
    assertEquals(1, listener.events.size());
  }

  @Test
  void oversizedStoredContentIsRejectedBeforeAnyPut() {
    int maxBytes = 64 * 1024;
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(new TextToolContent("x".repeat(maxBytes + 1)), new TextToolContent("small")),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        runPlatformSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore(), maxBytes);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    // all-or-nothing：超限项在第一个 put 之前被确定性拒绝，零存储副作用。
    assertTrue(listener.store.puts.isEmpty(), "oversized item must be rejected before any put");
  }

  @Test
  void interruptedWaitingTaskDefersExactlyOneUnknownUntilActivation() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.InterruptingExecutor executor =
        new ToolGatewayTestSupport.InterruptingExecutor();
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    // execute() 阻塞到任务线程在 gate release 前被中断：任务退出前队列一个未分类失败，绝不触碰 Tool、绝不投递回调。
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(tool.requests.isEmpty(), "tool must not execute after interrupted waiting task");
    assertTrue(listener.events.isEmpty(), "no callback may fire before activation");
    // 激活：等待期中断的执行结果无法确认，恰好一次 UNKNOWN；Tool 仍未被触碰。
    startedResult.handle().activate();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
    assertTrue(tool.requests.isEmpty(), "tool must never be touched");
  }

  @Test
  void cancelAfterInterruptedWaitingTaskKeepsSilent() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.InterruptingExecutor executor =
        new ToolGatewayTestSupport.InterruptingExecutor();
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // 中断后 cancel-before-activate：缓冲的未分类失败被丢弃，保持静默（无 Tool、无回调、无泄漏）。
    startedResult.handle().cancel();
    startedResult.handle().activate();
    assertTrue(listener.events.isEmpty(), "cancel-before-activate must stay silent");
    assertTrue(tool.requests.isEmpty(), "tool must never be touched");
  }

  private static ToolGatewayTestSupport.RecordingListener runPlatformSyncComplete(
      ToolResult result) {
    return runPlatformSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore());
  }

  private static ToolGatewayTestSupport.RecordingListener runPlatformSyncComplete(
      ToolResult result, ToolGatewayTestSupport.FakeResourceStore store) {
    return runPlatformSyncComplete(result, store, ToolGatewayTestSupport.RESOURCE_MAX_BYTES);
  }

  private static ToolGatewayTestSupport.RecordingListener runPlatformSyncComplete(
      ToolResult result, ToolGatewayTestSupport.FakeResourceStore store, int resourceMaxBytes) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler = (request, listener) -> listener.onComplete(result);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor,
            resourceMaxBytes);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    // 两阶段激活：activate 打开回调 gate 后 executor 任务才运行 Tool。
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    listener.store = store;
    return listener;
  }

  private static ToolGatewayTestSupport.RecordingListener runPlatformPartial(ToolResult partial) {
    return runPlatformPartial(partial, new ToolGatewayTestSupport.FakeResourceStore());
  }

  private static ToolGatewayTestSupport.RecordingListener runPlatformPartial(
      ToolResult partial, ToolGatewayTestSupport.FakeResourceStore store) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler = (request, listener) -> listener.onPartial(partial);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    listener.store = store;
    return listener;
  }

  private static ToolGatewayTestSupport.RecordingListener runPlatformSyncError(Throwable error) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler = (request, listener) -> listener.onError(error);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    return listener;
  }

  /** 拒绝 terminal 回调的场景结果：记录器、store 与桥引用（用于迟到信号断言）。 */
  private record RejectedTerminalRun(
      ToolGatewayTestSupport.RecordingListener listener,
      ToolGatewayTestSupport.FakeResourceStore store,
      AtomicReference<ToolExecutionListener> bridge) {}

  /**
   * 运行一个 Platform 同步发射信号的场景：{@code configure} 先配置记录器（如置位某个 throwOn* 标志），{@code emit} 在 execute
   * 内发射信号并捕获桥引用；activate + runAll 后所有分发已完成（同步），可直接断言 terminal 调用次数。
   */
  private static RejectedTerminalRun runRejectedTerminal(
      Consumer<ToolGatewayTestSupport.RecordingListener> configure,
      Consumer<ToolExecutionListener> emit) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    AtomicReference<ToolExecutionListener> bridge = new AtomicReference<>();
    tool.handler =
        (request, listener) -> {
          bridge.set(listener);
          emit.accept(listener);
        };
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    configure.accept(listener);
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    return new RejectedTerminalRun(listener, store, bridge);
  }
}
