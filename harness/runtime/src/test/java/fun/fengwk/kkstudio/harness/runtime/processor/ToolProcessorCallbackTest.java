package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * ToolProcessor callback 行为：RUNNING 后 partial 校验与 sink 隔离、success normalize（terminate 归一 false /
 * binary 拒绝 / toolCallId 匹配）、retry 决策（retryable + sideEffect + policy）、cancel / unknown、duplicate /
 * late / stale / deleteWork / new-wake。
 */
class ToolProcessorCallbackTest {

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  private ToolProcessorTestSupport.Fixture startedFixture() {
    return startedFixture(ToolProcessorTestSupport.NO_RETRY, ToolSideEffect.READ_ONLY);
  }

  private ToolProcessorTestSupport.Fixture startedFixture(
      InvocationRetryPolicy retryPolicy, ToolSideEffect sideEffect) {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(retryPolicy, sideEffect);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    return fixture;
  }

  /** partial：校验 RUNNING + attempt 与 ownership 后发布 ToolPartial；无任何 durable mutation。 */
  @Test
  void partialIsPublishedAfterRunningFence() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));

    assertEquals(1, fixture.sink.events.size());
    RealtimeEvent.ToolPartial event = (RealtimeEvent.ToolPartial) fixture.sink.events.get(0);
    assertEquals(fixture.baseline.threadId(), event.threadId());
    assertEquals(fixture.toolInvocationId, event.toolInvocationId());
    assertEquals(1, event.attempt());
    assertEquals("call-1", event.partial().toolCallId());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** partial toolCallId 不匹配 request：协议破坏，确定性 FAILED(INVALID_PARTIAL)。 */
  @Test
  void partialWithMismatchedToolCallIdFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onPartial(ToolProcessorTestSupport.partialResult("other-call"));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_PARTIAL", tool.error().kind());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertTrue(fixture.sink.events.isEmpty());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** partial 携带 BinaryToolContent（partial 不可持久资源）：确定性 FAILED。 */
  @Test
  void partialWithBinaryContentFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onPartial(
        new ToolResult(
            "call-1",
            List.of(new BinaryToolContent("application/octet-stream", new byte[] {1, 2})),
            false,
            "{}",
            false));

    assertEquals(
        ToolInvocationStatus.FAILED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        "INVALID_PARTIAL",
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).error().kind());
  }

  /** partial 携带 ResourceToolContent：确定性 FAILED(INVALID_PARTIAL)。 */
  @Test
  void partialWithResourceContentFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onPartial(
        new ToolResult(
            "call-1",
            List.of(
                new ResourceToolContent(
                    new ResourceRef("https://example.com/a", "text/plain", null, null, null))),
            false,
            "{}",
            false));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_PARTIAL", tool.error().kind());
  }

  /** sink 失败只影响实时投影：partial 后 execution 继续，terminal 照常落地。 */
  @Test
  void partialSinkFailureIsIsolated() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    fixture.sink.failure = new IllegalStateException("redis down");

    listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));
    assertEquals(
        ToolInvocationStatus.SUCCEEDED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** success：terminate 强制归一 false 后写 SUCCEEDED，THREAD wake + complete。 */
  @Test
  void successNormalizesTerminateAndWakesThread() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", true, new TextToolContent("done")));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool.status());
    assertEquals(1, tool.attempt());
    assertFalse(tool.result().terminate(), "terminate must be normalized to false");
    assertEquals("done", ((TextToolContent) tool.result().contents().get(0)).text());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** success toolCallId 不匹配：FAILED(INVALID_RESULT)，不写 SUCCEEDED。 */
  @Test
  void successWithMismatchedToolCallIdFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("other-call", new TextToolContent("x")));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_RESULT", tool.error().kind());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** success 携带 BinaryToolContent：Gateway 必须先外部化为稳定 ResourceToolContent ref，否则 FAILED。 */
  @Test
  void successWithBinaryContentFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult(
            "call-1", new BinaryToolContent("application/octet-stream", new byte[] {1})));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_RESULT", tool.error().kind());
  }

  /** success 携带 ResourceToolContent（规范引用）：合法，直接 SUCCEEDED。 */
  @Test
  void successWithResourceContentSucceeds() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult(
            "call-1",
            new ResourceToolContent(
                new ResourceRef("https://example.com/a", "text/plain", null, null, null))));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool.status());
    assertTrue(tool.result().contents().get(0) instanceof ResourceToolContent);
  }

  /** partial canonical JSON 超过 256 KiB（N×内联大文本）：确定性 INVALID_PARTIAL，绝不发布实时事件。 */
  @Test
  void partialExceedingEncodedSizeCapFailsWithoutRealtime() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onPartial(
        new ToolResult(
            "call-1", List.of(new TextToolContent("a".repeat(300_000))), false, "{}", false));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_PARTIAL", tool.error().kind());
    assertTrue(fixture.sink.events.isEmpty());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** terminal 聚合大量 data URI refs 超过 1 MiB canonical JSON：确定性 INVALID_RESULT，不得写入 PostgreSQL。 */
  @Test
  void successWithAggregatedDataResourceRefsOverCapFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    List<ResourceToolContent> refs = new ArrayList<>();
    for (int index = 0; index < 20; index++) {
      refs.add(dataResource("data:text/plain,", 60_000));
    }

    listener.onSucceeded(new ToolResult("call-1", new ArrayList<>(refs), false, "{}", false));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_RESULT", tool.error().kind());
    assertNull(tool.result(), "oversized result must never be persisted");
  }

  /** terminal 超大内联文本超过 1 MiB canonical JSON：确定性 INVALID_RESULT。 */
  @Test
  void successWithHugeInlineTextOverCapFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult(
            "call-1", new TextToolContent("a".repeat(1_100_000))));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_RESULT", tool.error().kind());
    assertNull(tool.result(), "oversized result must never be persisted");
  }

  /** terminal 的合法 detailsJson 与内联文本聚合后超过 1 MiB canonical JSON：确定性 INVALID_RESULT。 */
  @Test
  void successWithHugeDetailsJsonOverCapFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(
        new ToolResult(
            "call-1",
            List.of(new TextToolContent("b".repeat(200_000))),
            false,
            "{\"x\":\"" + "a".repeat(900_000) + "\"}",
            false));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_RESULT", tool.error().kind());
    assertNull(tool.result(), "oversized result must never be persisted");
  }

  /**
   * terminal 24 MiB 内联文本：尺寸判定必须走 bounded 编码器（超限即中止，不物化完整 canonical JSON String / byte[]）， 绝不能先
   * encode 完整 JSON 再量长度；bounded 辅助器与处理器结论一致，确定性 INVALID_RESULT。
   */
  @Test
  void successWithVeryLargeInlineTextRejectsWithoutFullEncode() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    String huge = "a".repeat(24 * 1024 * 1024);

    assertTrue(
        ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
            ToolProcessorTestSupport.successResult("call-1", new TextToolContent(huge)),
            ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES),
        "bounded helper must reject 24 MiB text without materializing the full JSON");

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent(huge)));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_RESULT", tool.error().kind());
    assertNull(tool.result(), "oversized result must never be persisted");
  }

  /** 构造 canonical data URI ResourceToolContent；payload 全为 unreserved ASCII，无需百分号转义。 */
  private static ResourceToolContent dataResource(String prefix, int payloadSize) {
    byte[] payload = "a".repeat(payloadSize).getBytes(StandardCharsets.UTF_8);
    String sha = HexFormat.of().formatHex(sha256(payload));
    return new ResourceToolContent(
        new ResourceRef(
            prefix + new String(payload, StandardCharsets.UTF_8),
            "text/plain",
            null,
            (long) payloadSize,
            sha));
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  /**
   * retryable 失败 + READ_ONLY：RUNNING -&gt; READY + revision+1 + policy delay reschedule，不 request
   * THREAD。
   */
  @Test
  void retryableFailureRetriesReadOnlyWithPolicyDelay() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onFailed(
        new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "unavailable"), true));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.READY, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        ToolProcessorTestSupport.NOW.plusSeconds(5),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
    assertNull(
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).leaseToken());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** retryable 失败 + IDEMPOTENT：与 READ_ONLY 相同，自动重试。 */
  @Test
  void retryableFailureRetriesIdempotent() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.IDEMPOTENT);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onFailed(
        new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "unavailable"), true));

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** retryable 失败 + NON_IDEMPOTENT：绝不自动 retry，直接 FAILED + THREAD wake。 */
  @Test
  void retryableFailureOnNonIdempotentNeverRetries() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.NON_IDEMPOTENT);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onFailed(
        new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "unavailable"), true));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals("TRANSIENT", tool.error().kind());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** retryable=false：即使 READ_ONLY 也直接 FAILED + THREAD wake + complete。 */
  @Test
  void nonRetryableFailureFailsAndWakesThread() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onFailed(new ToolGateway.Failure(new ToolInvocationError("PERMANENT", "nope"), false));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("PERMANENT", tool.error().kind());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** retry budget 耗尽：第二次 retryable 失败转为 FAILED，attempt 保持已确认值。 */
  @Test
  void retryExhaustionFailsAfterSecondFailure() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_ONCE, ToolSideEffect.READ_ONLY);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onFailed(new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "first"), true));
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, fixture.clock.instant())));
    ToolGateway.Listener secondListener = fixture.gateway.listener(fixture.toolInvocationId);

    secondListener.onFailed(
        new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "second"), true));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals(2, tool.attempt());
    assertEquals(
        6, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** onUnknown：RUNNING 保留 attempt 转 UNKNOWN，即使错误可重试也不 retry。 */
  @Test
  void unknownCallbackTerminatesWithoutRetry() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onUnknown(new ToolInvocationError("UNCERTAIN", "outcome unknown"));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.UNKNOWN, tool.status());
    assertEquals(1, tool.attempt());
    assertEquals("UNCERTAIN", tool.error().kind());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** onCancelled：CANCELLED terminal，不 retry。 */
  @Test
  void cancelledCallbackTerminatesCancelled() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onCancelled(new ToolInvocationError("CANCELLED", "stopped"));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.CANCELLED, tool.status());
    assertEquals("CANCELLED", tool.error().kind());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** terminal 只生效一次：duplicate / late terminal 与 terminal 后的 partial 全部 no-op。 */
  @Test
  void duplicateTerminalAndLateEventsAreNoOps() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("first")));
    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("second")));
    listener.onFailed(new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "late"), true));
    listener.onCancelled(new ToolInvocationError("CANCELLED", "late"));
    listener.onUnknown(new ToolInvocationError("UNCERTAIN", "late"));
    listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool.status());
    assertEquals("first", ((TextToolContent) tool.result().contents().get(0)).text());
    assertEquals(1, tool.attempt());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** Stop deleteWork 后到达的 late callback：ownership 校验失败，不写任何 durable 状态并关闭本地执行。 */
  @Test
  void lateCallbackAfterWorkDeletionIsNoOp() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));
    listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertTrue(fixture.sink.events.isEmpty());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop deleteWork 后到达的 late retryable 失败：retry 事务失败，保持 RUNNING 并关闭本地执行。 */
  @Test
  void retryAfterWorkDeletionIsLost() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    listener.onFailed(new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "boom"), true));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop deleteWork 后到达的 late UNKNOWN：terminal 事务失败，保持 RUNNING。 */
  @Test
  void unknownAfterWorkDeletionIsLost() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    listener.onUnknown(new ToolInvocationError("UNCERTAIN", "outcome unknown"));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop deleteWork 后到达的 late CANCELLED：terminal 事务失败，保持 RUNNING。 */
  @Test
  void cancelledAfterWorkDeletionIsLost() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    listener.onCancelled(new ToolInvocationError("CANCELLED", "stopped"));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 处理期间到达新 wake：terminal complete 只清 lease 保留行，新 wake 保持可见。 */
  @Test
  void newWakeSurvivesTerminalCompletion() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.requestToolWork(fixture, ToolProcessorTestSupport.NOW);

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));

    assertEquals(
        ToolInvocationStatus.SUCCEEDED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    var toolWork = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    assertEquals(2, toolWork.wakeVersion());
    assertNull(toolWork.leaseToken());
    assertNull(toolWork.leaseUntil());
  }

  /** sink 失败不能改变 durable terminal：SUCCEEDED 照常落地。 */
  @Test
  void sinkFailureDoesNotAffectDurableTerminal() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    fixture.sink.failure = new IllegalStateException("redis down");

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));

    assertEquals(
        ToolInvocationStatus.SUCCEEDED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** 激活前缓冲的 retryable 失败：激活时落地 retry，process 返回 RESCHEDULED。 */
  @Test
  void bufferedRetryableFailureBeforeActivationReschedules() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.beforeStartReturn =
        listener ->
            listener.onFailed(
                new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "boom"), true));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.RESCHEDULED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        1, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        ToolProcessorTestSupport.NOW.plusSeconds(5),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** 激活前缓冲的 CANCELLED：激活时落地 terminal，process 返回 TERMINATED。 */
  @Test
  void bufferedCancellationBeforeActivationTerminates() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.beforeStartReturn =
        listener -> listener.onCancelled(new ToolInvocationError("CANCELLED", "stopped"));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(
        ToolInvocationStatus.CANCELLED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        3, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
  }

  /** 激活前缓冲的 partial + success：按到达顺序重放，partial 先发布、terminal 后落地。 */
  @Test
  void partialBeforeActivationIsBufferedAndReplayed() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    fixture.gateway.beforeStartReturn =
        listener -> {
          listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));
          listener.onSucceeded(
              ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));
        };
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));

    assertEquals(
        ProcessResult.TERMINATED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertEquals(1, fixture.sink.events.size());
    assertEquals(
        ToolInvocationStatus.SUCCEEDED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
  }

  /** handle.cancel 抛异常：本地隔离，不传播也不影响 registry 释放。 */
  @Test
  void handleCancelFailureIsIsolated() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolGateway.Handle throwingHandle =
        new ToolGateway.Handle() {
          @Override
          public void cancel() {
            throw new IllegalStateException("transport gone");
          }
        };
    fixture.gateway.queueStart(new ToolGateway.Started(throwingHandle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    assertTrue(fixture.processor.cancel(fixture.toolInvocationId));
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
  }

  /** 非法 partial（null）：确定性 FAILED(INVALID_PARTIAL)。 */
  @Test
  void nullPartialFails() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onPartial(null);

    assertEquals(
        ToolInvocationStatus.FAILED,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        "INVALID_PARTIAL",
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).error().kind());
  }

  /** retry 后的旧 execution late callback：attempt fence 拒绝，no-op。 */
  @Test
  void lateCallbackAfterRetryIsNoOp() {
    ToolProcessorTestSupport.Fixture fixture =
        startedFixture(ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolGateway.Listener oldListener = fixture.gateway.listener(fixture.toolInvocationId);

    oldListener.onFailed(
        new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "first"), true));
    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());

    fixture.clock.advance(Duration.ofSeconds(5));
    fixture.gateway.queueStart(new ToolGateway.Started(new ToolProcessorTestSupport.FakeHandle()));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, fixture.clock.instant())));

    oldListener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("late")));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        2, ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt());
    assertEquals(
        5, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** Stop deleteWork 后到达的 late partial：ownership 校验失败，不发布、不写 durable，并关闭本地执行。 */
  @Test
  void partialAfterWorkDeletionIsLostWithoutPublishing() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.deleteToolWork(fixture);

    listener.onPartial(ToolProcessorTestSupport.partialResult("call-1"));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertTrue(fixture.sink.events.isEmpty());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** onSucceeded(null)：非法 terminal result，确定性 FAILED(INVALID_RESULT)。 */
  @Test
  void onSucceededNullFailsWithInvalidResult() {
    ToolProcessorTestSupport.Fixture fixture = startedFixture();
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onSucceeded(null);

    ToolInvocation tool = ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId);
    assertEquals(ToolInvocationStatus.FAILED, tool.status());
    assertEquals("INVALID_RESULT", tool.error().kind());
    assertEquals(
        2,
        ToolProcessorTestSupport.threadWork(fixture.store, fixture.baseline.threadId())
            .wakeVersion());
    assertNull(ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId));
  }

  /** onFailed(null)：callback 管线异常只关闭本地执行，不反写任何 durable 状态。 */
  @Test
  void onFailedNullAbandonsWithoutDurableWrite() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);

    listener.onFailed(null);

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).error());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** Stop 的 durable 终止（RUNNING -&gt; UNKNOWN）后到达的 late retryable 失败：retry 事务校验失败，仅本地关闭。 */
  @Test
  void retryAfterStopCancelsLocalExecution() {
    ToolProcessorTestSupport.Fixture fixture =
        ToolProcessorTestSupport.fixture(
            ToolProcessorTestSupport.RETRY_TWICE, ToolSideEffect.READ_ONLY);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.unknown(
                new ToolInvocationError("CANCELLED", "stopped"), ToolProcessorTestSupport.NOW));

    listener.onFailed(new ToolGateway.Failure(new ToolInvocationError("TRANSIENT", "boom"), true));

    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
    assertEquals(
        2, ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId()).revision());
  }

  /** Stop 的 durable 终止后到达的 late success：commitSuccess 校验失败，仅本地关闭，绝不写 SUCCEEDED。 */
  @Test
  void successAfterStopCancelsLocalExecution() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.unknown(
                new ToolInvocationError("CANCELLED", "stopped"), ToolProcessorTestSupport.NOW));

    listener.onSucceeded(
        ToolProcessorTestSupport.successResult("call-1", new TextToolContent("answer")));

    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }

  /** Stop 的 durable 终止后到达的 late terminal（onUnknown）：commitTerminal 校验失败，仅本地关闭。 */
  @Test
  void terminalAfterStopCancelsLocalExecution() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool -> tool.markApprovalNotRequired(ToolProcessorTestSupport.NOW));
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    fixture.gateway.queueStart(new ToolGateway.Started(handle));
    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
    ToolGateway.Listener listener = fixture.gateway.listener(fixture.toolInvocationId);
    ToolProcessorTestSupport.transition(
        fixture.store,
        fixture.toolInvocationId,
        tool ->
            tool.unknown(
                new ToolInvocationError("CANCELLED", "stopped"), ToolProcessorTestSupport.NOW));

    listener.onUnknown(new ToolInvocationError("UNCERTAIN", "outcome unknown"));

    assertEquals(
        ToolInvocationStatus.UNKNOWN,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        "CANCELLED",
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).error().kind());
    assertTrue(handle.isCancelled());
    assertFalse(fixture.processor.hasActiveExecution());
  }
}
