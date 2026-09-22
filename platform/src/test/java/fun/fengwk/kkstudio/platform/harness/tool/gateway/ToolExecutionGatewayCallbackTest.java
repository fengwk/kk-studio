package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
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
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link ToolExecutionGateway} 回调桥：partial / terminal / error / cancel 语义与 managed Resource 外部化。
 *
 * <p>本地 Tool 路径由 FakeTool 的 handler 在 execute 内同步投递回调（gate 已打开，直达 listener）；Environment 绑定路径由测试直接驱动
 * FakeTransport 记录的 listener。
 */
class ToolExecutionGatewayCallbackTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");

  private static final byte[] BINARY_BYTES = new byte[] {1, 2, 3, 4, 5};
  private static final String LARGE_TEXT =
      "x".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES + 1);
  private static final String LARGE_JSON =
      "{\"data\":\"" + "y".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES) + "\"}";
  private static final UUID ID = new UUID(0L, 1L);

  @Test
  void binaryContentIsExternalizedBeforeTerminalDelivery() {
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    // store 收到一次 put：mediaType 原样、内容精确；展示名稳定有用。
    assertEquals(1, listener.store.puts.size());
    ToolGatewayTestSupport.FakeResourceStore.PutRecord put = listener.store.puts.get(0);
    assertEquals("image/png", put.mediaType());
    assertEquals("demo-result-1", put.name());
    assertArrayEquals(BINARY_BYTES, put.content());
    // 交付结果只剩 ResourceResultContent，原始 error 语义保持不变。
    assertEquals(1, succeeded.result().contents().size());
    ResourceResultContent externalized =
        assertInstanceOf(ResourceResultContent.class, succeeded.result().contents().get(0));
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
                new TextResultContent("small"),
                new TextResultContent(LARGE_TEXT),
                new JsonResultContent(LARGE_JSON)),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertEquals(1, listener.store.puts.size());
    // 多个文本块由 \n\n 聚合为一个 text/plain + .txt managed resource
    ToolGatewayTestSupport.FakeResourceStore.PutRecord textPut = listener.store.puts.get(0);
    assertEquals("text/plain", textPut.mediaType());
    assertEquals("demo-result.txt", textPut.name());
    String expectedJoined = "small\n\n" + LARGE_TEXT + "\n\n" + LARGE_JSON;
    assertArrayEquals(expectedJoined.getBytes(StandardCharsets.UTF_8), textPut.content());
    // 所有文本内容投影为一个 ResourceResultContent，位于第一个文本位置
    assertEquals(1, succeeded.result().contents().size());
    ResourceResultContent externalized =
        assertInstanceOf(ResourceResultContent.class, succeeded.result().contents().get(0));
    assertEquals("demo-result.txt", externalized.resource().name());
    assertNotNull(externalized.textMetadata());
    assertEquals(
        expectedJoined.getBytes(StandardCharsets.UTF_8).length,
        externalized.textMetadata().totalBytes());
  }

  @Test
  void inlineThresholdBoundaryIsExact() {
    String atThreshold = "x".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES);
    ToolResult inlineResult =
        new ToolResult("call-1", List.of(new TextResultContent(atThreshold)), false, "{}");
    ToolGatewayTestSupport.RecordingListener inlineListener = runHostSyncComplete(inlineResult);
    assertEquals(0, inlineListener.store.puts.size());
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded inlineSucceeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) inlineListener.events.get(0);
    assertEquals(1, inlineSucceeded.result().contents().size());
    assertEquals(new TextResultContent(atThreshold), inlineSucceeded.result().contents().get(0));

    ToolResult overResult =
        new ToolResult("call-1", List.of(new TextResultContent(atThreshold + "x")), false, "{}");
    ToolGatewayTestSupport.RecordingListener overListener = runHostSyncComplete(overResult);
    assertEquals(1, overListener.store.puts.size());
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded overSucceeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) overListener.events.get(0);
    assertEquals(1, overSucceeded.result().contents().size());
    assertInstanceOf(ResourceResultContent.class, overSucceeded.result().contents().get(0));
  }

  /** 恰好在 preview 限制内的外部化文本完整保留为 preview，不添加截断标记。 */
  @Test
  void externalizedTextAtPreviewLimitKeepsExactPreview() {
    String text = "x".repeat(ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_UTF8_BYTES);
    String input = text + "\n" + "y".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES);
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncComplete(
            new ToolResult("call-1", List.of(new TextResultContent(input)), false, "{}"));
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);

    ResourceResultContent resource =
        assertInstanceOf(ResourceResultContent.class, succeeded.result().contents().getFirst());
    assertEquals(text, resource.preview());
    assertEquals(
        ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_UTF8_BYTES,
        ResourceRef.utf8Length(resource.preview(), "preview"));
  }

  /** 超过 2 KiB 时按 code point 截断，无额外标记，不拆分 surrogate 或 UTF-8 序列。 */
  @Test
  void oversizedTextGetsDeterministicUtf8SafePreview() {
    int budget = ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_UTF8_BYTES;
    String text = "a".repeat(budget - 1) + "\uD83D\uDE00" + "tail".repeat(16384);
    String expected = "a".repeat(budget - 1);

    ToolGatewayTestSupport.RecordingListener first =
        runHostSyncComplete(
            new ToolResult("call-1", List.of(new TextResultContent(text)), false, "{}"));
    ToolGatewayTestSupport.RecordingListener second =
        runHostSyncComplete(
            new ToolResult("call-1", List.of(new TextResultContent(text)), false, "{}"));
    ResourceResultContent firstResource =
        (ResourceResultContent)
            ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) first.events.get(0))
                .result()
                .contents()
                .getFirst();
    ResourceResultContent secondResource =
        (ResourceResultContent)
            ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) second.events.get(0))
                .result()
                .contents()
                .getFirst();

    assertEquals(expected, firstResource.preview());
    assertEquals(firstResource.preview(), secondResource.preview());
    assertTrue(
        ResourceRef.utf8Length(firstResource.preview(), "preview")
            <= ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_UTF8_BYTES);
  }

  @Test
  void externalizedTextProjectsStructuredFactsToProviderModel() {
    String text = "0123456789".repeat(6000);
    int totalBytes = text.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(totalBytes > ToolResultFinalizer.INLINE_MAX_UTF8_BYTES);
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncComplete(
            new ToolResult("call-1", List.of(new TextResultContent(text)), false, "{}"));
    ToolResult externalized =
        ((ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0))
            .result();
    ToolInvocation invocation =
        new ToolInvocation(
            ID,
            ID,
            ID,
            0,
            ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR).call(),
            ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR).binding(),
            ToolInvocationStatus.SUCCEEDED,
            1,
            ToolApproval.notRequired(),
            externalized,
            null,
            Instant.EPOCH,
            Instant.EPOCH);

    String preview = ToolResultFinalizer.extractRawPreview(text);
    MessagePayload payload =
        new HistoryPayloadMapper()
            .toolResultPayload(
                invocation,
                List.of(
                    ResourceMessageContent.externalizedText(
                        ID, "demo-result.txt", totalBytes, 1L, preview)));
    ProviderToolResultBlock projected =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            ProviderMessageProjector.byNames(Set.of(DESCRIPTOR.name()))
                .project(List.of(payload.message()))
                .getFirst()
                .contents()
                .getFirst());
    assertEquals(
        ProviderResourceBlock.externalizedText(ID, "demo-result.txt", totalBytes, 1L, preview),
        projected.contents().getFirst());
  }

  @Test
  void existingManagedResourceIsStagedBeforeDelivery() {
    byte[] existing = new byte[] {1, 2, 3};
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ResourceRef ref = store.seed("text/plain", "existing", existing);
    ResourceResultContent existingContent = new ResourceResultContent(ref, "existing preview");
    ToolResult result =
        new ToolResult(
            "call-1", List.of(existingContent, new TextResultContent("inline")), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result, store);
    assertEquals(1, listener.store.puts.size());
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    ResourceResultContent staged =
        assertInstanceOf(ResourceResultContent.class, succeeded.result().contents().get(0));
    assertTrue(staged.resource().uri().startsWith("blob-upload:"));
    assertEquals(existingContent.preview(), staged.preview());
  }

  @Test
  void semanticErrorResultIsPreservedAndStillExternalized() {
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(new BinaryResultContent("application/octet-stream", BINARY_BYTES)),
            true,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertTrue(succeeded.result().error());
    assertEquals(1, listener.store.puts.size());
  }

  @Test
  void partialWithBinaryIsRejectedWithoutAnyStoreIo() {
    ToolResult partial =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostPartial(partial);
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
        new ToolResult("call-1", List.of(new ResourceResultContent(ref)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostPartial(partial);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertTrue(listener.store.puts.isEmpty());
  }

  @Test
  void plainPartialIsForwardedAsIs() {
    ToolResult partial =
        new ToolResult("call-1", List.of(new TextResultContent("progress")), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostPartial(partial);
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
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result, store);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("RESOURCE_STORE_FAILED", unknown.error().kind());
  }

  @Test
  void uploadReferenceWithTextMetadataIsDeterministicInvalidResult() {
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ResourceRef upload =
        new ResourceRef(
            ResourceRef.blobUploadUri(UUID.randomUUID()),
            "image/png",
            "image.png",
            (long) BINARY_BYTES.length,
            ToolGatewayTestSupport.sha256(BINARY_BYTES));
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(
                new ResourceResultContent(
                    upload, null, new TextArtifactMetadata((long) BINARY_BYTES.length, 1L))),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result, store);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    assertTrue(store.puts.isEmpty(), "invalid upload references must be rejected before staging");
  }

  @Test
  void storePutIllegalArgumentAfterPlanningMapsToUnknown() {
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    store.putFailure = new IllegalArgumentException("store contract violation");
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result, store);
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
                new BinaryResultContent("Image/PNG", BINARY_BYTES),
                new BinaryResultContent("image/png", BINARY_BYTES)),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertTrue(listener.store.puts.isEmpty());
  }

  @Test
  void cancelledErrorMapsToCancelled() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new EnvironmentCapabilityCancelledException("user stopped"));
    ToolGatewayTestSupport.RecordingListener.Event.Cancelled cancelled =
        (ToolGatewayTestSupport.RecordingListener.Event.Cancelled) listener.events.get(0);
    assertEquals("CANCELLED", cancelled.error().kind());
  }

  @Test
  void uncertainErrorMapsToUnknown() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new EnvironmentCapabilitySendUncertainException("connection lost"));
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("REMOTE_UNCERTAIN", unknown.error().kind());
  }

  @Test
  void unavailableErrorIsRetryableFailure() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new EnvironmentCapabilityUnavailableException("environment offline"));
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("UNAVAILABLE", failed.failure().error().kind());
    assertTrue(failed.failure().retryable());
  }

  @Test
  void daemonFailedErrorIsNonRetryableKnownFailure() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new EnvironmentCapabilityFailedException("tool blew up"));
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", failed.failure().error().kind());
    assertEquals("tool blew up", failed.failure().error().message());
    assertFalse(failed.failure().retryable());
  }

  @Test
  void genericErrorMapsToUnknownNotKnownFailure() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new IllegalStateException("tool failed"));
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
  }

  @Test
  void invalidArgumentErrorIsUnclassifiedUnknown() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new IllegalArgumentException("bad arguments"));
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
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.defaultCatalog(tool),
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStore(),
              executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
              listener);
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
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
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolResult second =
        new ToolResult(
            "call-1",
            List.of(new BinaryResultContent("image/png", new byte[] {9, 9})),
            false,
            "{}");
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
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
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.defaultCatalog(tool),
              new ToolGatewayTestSupport.FakeTransport(),
              new ToolGatewayTestSupport.FakeResourceStore(),
              executor);
      ToolGateway.StartResult started =
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
              new ToolGatewayTestSupport.RecordingListener());
      ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
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
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    assertEquals(1, transport.returnedHandles.size());
    startedResult.handle().cancel();
    startedResult.handle().cancel();
    assertTrue(transport.returnedHandles.get(0).isCancelled());
  }

  @Test
  void nullPartialIsRejectedAsInvalidPartial() {
    ToolGatewayTestSupport.RecordingListener listener = runHostPartial(null);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_PARTIAL", failed.failure().error().kind());
    assertEquals("partial must not be null", failed.failure().error().message());
    assertFalse(failed.failure().retryable());
  }

  @Test
  void nullTerminalResultIsRejectedAsInvalidResult() {
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(null);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertEquals("terminal result must not be null", failed.failure().error().message());
    assertFalse(failed.failure().retryable());
  }

  @Test
  void nullHostToolHandleIsPostAcceptanceUnknown() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.returnNullHandle = true;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    assertEquals(1, tool.requests.size());
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
  }

  @Test
  void synchronousTerminalWinsOverNullHostToolHandle() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.returnNullHandle = true;
    tool.handler =
        (request, listener) ->
            listener.onComplete(
                new ToolResult(
                    "call-1",
                    List.of(new BinaryResultContent("image/png", BINARY_BYTES)),
                    false,
                    "{}"));
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.get(0);
    assertEquals("call-1", succeeded.result().toolCallId());
    assertEquals(1, store.puts.size(), "terminal externalization is unaffected by the null handle");
  }

  @Test
  void nullMessageUnavailableErrorUsesFallbackMessage() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new EnvironmentCapabilityUnavailableException(null));
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("UNAVAILABLE", failed.failure().error().kind());
    assertEquals("Tool is unavailable.", failed.failure().error().message());
    assertTrue(failed.failure().retryable());
  }

  @Test
  void nullMessageGenericErrorMapsToUnknownWithFallbackMessage() {
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncError(new IllegalStateException());
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
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolResult complete =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
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
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolResult second =
        new ToolResult(
            "call-1",
            List.of(new BinaryResultContent("image/png", new byte[] {9, 9})),
            false,
            "{}");
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
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
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
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
                        List.of(new BinaryResultContent("image/png", BINARY_BYTES)),
                        false,
                        "{}")));
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

  @Test
  void bufferedSignalsUpToLimitAreReplayedOnActivation() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_PARTIALS;
    transport.syncPartialCount = ToolExecutionGateway.MAX_BUFFERED_SIGNALS;
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(), transport, store, executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(ToolExecutionGateway.MAX_BUFFERED_SIGNALS);
    assertEquals(0, listener.terminalInvocations.get(), "partials are never terminals");
    for (int index = 0; index < listener.events.size(); index++) {
      ToolGatewayTestSupport.RecordingListener.Event.Partial partial =
          assertInstanceOf(
              ToolGatewayTestSupport.RecordingListener.Event.Partial.class,
              listener.events.get(index));
      assertEquals(
          "progress-" + index, ((TextResultContent) partial.partial().contents().get(0)).text());
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
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(), transport, store, executor);

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-1", ToolGatewayTestSupport.ENV_A)),
                listener));
    started.handle().activate();
    executor.runAll();
    listener.awaitCount(1);

    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        (ToolGatewayTestSupport.RecordingListener.Event.Succeeded) listener.events.getFirst();
    assertEquals("call-1", succeeded.result().toolCallId());
    assertEquals("done", ((TextResultContent) succeeded.result().contents().getFirst()).text());
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void wrongEnvironmentCapabilityCorrelationFailsBeforeResourceExternalization() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.callbackCallId = "call-1";
    transport.syncResult =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    listener.store = store;
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(), transport, store, executor);

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-1", ToolGatewayTestSupport.ENV_A)),
                listener));
    started.handle().activate();
    executor.runAll();
    listener.awaitCount(1);

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.getFirst();
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertEquals(1, listener.terminalInvocations.get());
    assertTrue(store.puts.isEmpty(), "wrong correlation must be rejected before externalization");
  }

  @Test
  void bufferedOverflowSelectsExactlyOneUnknownAtActivationWithoutStoreWrites() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.hostDescriptor("overflow");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(descriptor);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", descriptor)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(listener.events.isEmpty(), "gate closed: nothing may be delivered before activate");

    ToolExecutionGateway.GatewayHandle handle =
        (ToolExecutionGateway.GatewayHandle) startedResult.handle();
    for (int i = 0; i < ToolExecutionGateway.MAX_BUFFERED_SIGNALS + 1; i++) {
      handle
          .bridge()
          .onPartial(
              new ToolResult("call-1", List.of(new TextResultContent("p-" + i)), false, "{}"));
    }

    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("overflowed"));
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
    assertEquals(1, listener.terminalInvocations.get(), "overflow selects exactly one terminal");
    assertTrue(store.puts.isEmpty(), "overflow must never touch the resource store");
    handle.bridge().onComplete(ToolGatewayTestSupport.result("call-1", "late done"));
    handle.bridge().onPartial(ToolGatewayTestSupport.result("call-1", "later"));
    assertEquals(1, listener.terminalInvocations.get());
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void malformedSurrogateTextIsDeterministicInvalidWithZeroPuts() {
    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(new TextResultContent("prefix\uD800suffix"), new TextResultContent("ok")),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore());
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    assertTrue(listener.store.puts.isEmpty(), "malformed content must be rejected before any put");
  }

  @Test
  void wrongCallPartialThenBinaryTerminalYieldsOneInvalidPartialWithZeroPuts() {
    ToolResult partial =
        new ToolResult("wrong-call", List.of(new TextResultContent("progress")), false, "{}");
    ToolResult complete =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
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
                new TextResultContent(
                    "x".repeat(ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES))),
            false,
            "{}");
    ToolResult complete =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
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
            "wrong-call", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncComplete(complete, new ToolGatewayTestSupport.FakeResourceStore());
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
    List<ResultContent> contents = new ArrayList<>(64);
    for (int i = 0; i < 63; i++) {
      contents.add(new TextResultContent("x".repeat(8 * 1024)));
    }
    contents.add(new BinaryResultContent("image/png", BINARY_BYTES));
    int padding = ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES - 64;
    String details = "{\"data\":\"" + "y".repeat(padding) + "\"}";
    ToolResult result = new ToolResult("call-1", contents, false, details);
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore());
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals("INVALID_RESULT", failed.failure().error().kind());
    assertTrue(failed.failure().error().message().contains("must not exceed"));
    assertFalse(failed.failure().retryable());
    assertTrue(
        listener.store.puts.isEmpty(), "projected size rejection must happen before any put");
  }

  @Test
  void plannedRefMismatchAfterPutMapsToUnknown() {
    ToolGatewayTestSupport.FakeResourceStore store = new ToolGatewayTestSupport.FakeResourceStore();
    store.mismatchReturnedRef = true;
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");
    ToolGatewayTestSupport.RecordingListener listener = runHostSyncComplete(result, store);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("RESOURCE_STORE_FAILED", unknown.error().kind());
    assertEquals(
        "Tool result resource persistence failed; outcome is unknown.", unknown.error().message());
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    ToolGatewayTestSupport.RecordingListener.Event.Unknown unknown =
        (ToolGatewayTestSupport.RecordingListener.Event.Unknown) listener.events.get(0);
    assertEquals("EXECUTION_FAILED", unknown.error().kind());
    assertTrue(unknown.error().message().contains("cannot be confirmed"));
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
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
            List.of(
                new TextResultContent("x".repeat(maxBytes + 1)), new TextResultContent("small")),
            false,
            "{}");
    ToolGatewayTestSupport.RecordingListener listener =
        runHostSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore(), maxBytes);
    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        (ToolGatewayTestSupport.RecordingListener.Event.Failed) listener.events.get(0);
    assertEquals(ToolResultFinalizer.OUTPUT_TOO_LARGE_KIND, failed.failure().error().kind());
    assertFalse(failed.failure().retryable());
    assertTrue(listener.store.puts.isEmpty(), "oversized item must be rejected before any put");
  }

  @Test
  void interruptedWaitingTaskDefersExactlyOneUnknownUntilActivation() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.InterruptingExecutor executor =
        new ToolGatewayTestSupport.InterruptingExecutor();
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    assertTrue(tool.requests.isEmpty(), "tool must not execute after interrupted waiting task");
    assertTrue(listener.events.isEmpty(), "no callback may fire before activation");
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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().cancel();
    startedResult.handle().activate();
    assertTrue(listener.events.isEmpty(), "cancel-before-activate must stay silent");
    assertTrue(tool.requests.isEmpty(), "tool must never be touched");
  }

  private static ToolGatewayTestSupport.RecordingListener runHostSyncComplete(ToolResult result) {
    return runHostSyncComplete(result, new ToolGatewayTestSupport.FakeResourceStore());
  }

  private static ToolGatewayTestSupport.RecordingListener runHostSyncComplete(
      ToolResult result, ToolGatewayTestSupport.FakeResourceStore store) {
    return runHostSyncComplete(result, store, ToolGatewayTestSupport.RESOURCE_MAX_BYTES);
  }

  private static ToolGatewayTestSupport.RecordingListener runHostSyncComplete(
      ToolResult result, ToolGatewayTestSupport.FakeResourceStore store, int resourceMaxBytes) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler = (request, listener) -> listener.onComplete(result);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor,
            resourceMaxBytes);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    listener.store = store;
    return listener;
  }

  private static ToolGatewayTestSupport.RecordingListener runHostPartial(ToolResult partial) {
    return runHostPartial(partial, new ToolGatewayTestSupport.FakeResourceStore());
  }

  private static ToolGatewayTestSupport.RecordingListener runHostPartial(
      ToolResult partial, ToolGatewayTestSupport.FakeResourceStore store) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler = (request, listener) -> listener.onPartial(partial);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    listener.store = store;
    return listener;
  }

  private static ToolGatewayTestSupport.RecordingListener runHostSyncError(Throwable error) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.handler = (request, listener) -> listener.onError(error);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    listener.awaitCount(1);
    return listener;
  }

  private record RejectedTerminalRun(
      ToolGatewayTestSupport.RecordingListener listener,
      ToolGatewayTestSupport.FakeResourceStore store,
      AtomicReference<ToolExecutionListener> bridge) {}

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
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            executor);
    ToolGateway.StartResult started =
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR)),
            listener);
    ToolGateway.Started startedResult = assertInstanceOf(ToolGateway.Started.class, started);
    startedResult.handle().activate();
    executor.runAll();
    return new RejectedTerminalRun(listener, store, bridge);
  }
}
