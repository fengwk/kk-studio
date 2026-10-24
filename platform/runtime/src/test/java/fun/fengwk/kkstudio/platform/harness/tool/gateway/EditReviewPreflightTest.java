package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link EditReviewPreflight}：只读预览准备、成功 Ask 构造以及各种 transport / 畸形 / 超大结果降级为 Deny。 */
class EditReviewPreflightTest {

  private static final EnvironmentId ENV_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String VALID_SHA =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String VALID_PATH = "/srv/repo/App.java";
  private static final String VALID_DIFF = "-1|old\n+1|new";
  private static final String VALID_REVIEW_JSON =
      "{\"path\":\""
          + VALID_PATH
          + "\",\"diff\":\"-1|old\\n+1|new\",\"sha256\":\""
          + VALID_SHA
          + "\"}";

  @Test
  void successfulPreviewReturnsAskWithFrozenReview() {
    AtomicBoolean executed = new AtomicBoolean();
    EnvironmentCapabilityTransport transport =
        (envId, request, listener) -> {
          executed.set(true);
          assertEquals(ENV_ID, envId);
          assertEquals(EnvironmentCapabilityIds.FS_EDIT_PREVIEW, request.descriptor().id());
          // 预览不是 Thread Tool body：退出通知只应被安全忽略。
          listener.onExitConfirmed();
          listener.onComplete(
              new EnvironmentCapabilityResult(
                  request.call().id(),
                  List.of(new JsonResultContent(VALID_REVIEW_JSON)),
                  false,
                  "{}"));
          return new FakeHandle();
        };

    ToolGateway.PreflightResult result =
        EditReviewPreflight.prepare(
            transport, createRequest(), "Permission rules require approval");

    assertTrue(executed.get());
    ToolGateway.Ask ask = assertInstanceOf(ToolGateway.Ask.class, result);
    assertEquals("Permission rules require approval", ask.reason());
    ToolEditReview review = ask.editReview();
    assertEquals(new ExpectedFile(VALID_PATH, VALID_SHA), review.expectedFile());
    assertEquals(VALID_DIFF, review.diff());
  }

  @Test
  void transportThrowsExceptionDirectlyReturnsDeny() {
    EnvironmentCapabilityTransport busyTransport =
        (envId, request, listener) -> {
          throw new EnvironmentCapabilityBusyException("daemon busy");
        };
    ToolGateway.PreflightResult busyResult =
        EditReviewPreflight.prepare(busyTransport, createRequest(), "reason");
    ToolGateway.Deny busyDeny = assertInstanceOf(ToolGateway.Deny.class, busyResult);
    assertEquals("EDIT_REVIEW_FAILED", busyDeny.error().kind());
    assertTrue(busyDeny.error().message().contains("daemon busy"));

    EnvironmentCapabilityTransport unavailableTransport =
        (envId, request, listener) -> {
          throw new EnvironmentCapabilityUnavailableException("daemon offline");
        };
    ToolGateway.PreflightResult unavailResult =
        EditReviewPreflight.prepare(unavailableTransport, createRequest(), "reason");
    ToolGateway.Deny unavailDeny = assertInstanceOf(ToolGateway.Deny.class, unavailResult);
    assertEquals("EDIT_REVIEW_FAILED", unavailDeny.error().kind());
    assertTrue(unavailDeny.error().message().contains("daemon offline"));
  }

  @Test
  void transportReturningNullHandleReturnsDeny() {
    EnvironmentCapabilityTransport nullHandleTransport = (envId, request, listener) -> null;
    ToolGateway.PreflightResult result =
        EditReviewPreflight.prepare(nullHandleTransport, createRequest(), "reason");
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("EDIT_REVIEW_FAILED", deny.error().kind());
    assertTrue(
        deny.error().message().contains("Edit preview transport returned no execution handle"));
  }

  @Test
  void transportListenerReportsErrorReturnsDeny() {
    EnvironmentCapabilityTransport errorTransport =
        (envId, request, listener) -> {
          listener.onError(new IllegalStateException("preview failed on daemon"));
          return new FakeHandle();
        };
    ToolGateway.PreflightResult result =
        EditReviewPreflight.prepare(errorTransport, createRequest(), "reason");
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("EDIT_REVIEW_FAILED", deny.error().kind());
    assertTrue(deny.error().message().contains("preview failed on daemon"));
  }

  @Test
  void previewResultErrorReturnsDeny() {
    // 带有 text error description
    EnvironmentCapabilityTransport errorResultTransport =
        (envId, request, listener) -> {
          listener.onComplete(
              new EnvironmentCapabilityResult(
                  request.call().id(),
                  List.of(new TextResultContent("File not found on disk")),
                  true,
                  "{}"));
          return new FakeHandle();
        };
    ToolGateway.PreflightResult result =
        EditReviewPreflight.prepare(errorResultTransport, createRequest(), "reason");
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("EDIT_REVIEW_FAILED", deny.error().kind());
    assertTrue(deny.error().message().contains("File not found on disk"));

    // error 为 true 但没有 text content
    EnvironmentCapabilityTransport blankErrorTransport =
        (envId, request, listener) -> {
          listener.onComplete(
              new EnvironmentCapabilityResult(request.call().id(), List.of(), true, "{}"));
          return new FakeHandle();
        };
    ToolGateway.PreflightResult blankResult =
        EditReviewPreflight.prepare(blankErrorTransport, createRequest(), "reason");
    ToolGateway.Deny blankDeny = assertInstanceOf(ToolGateway.Deny.class, blankResult);
    assertTrue(
        blankDeny.error().message().contains("Edit preview failed without an error description"));
  }

  @Test
  void previewResultWithMismatchedCallIdOrMalformedContentReturnsDeny() {
    // Mismatched call ID
    EnvironmentCapabilityTransport mismatchIdTransport =
        (envId, request, listener) -> {
          listener.onComplete(
              new EnvironmentCapabilityResult(
                  "different-call-id",
                  List.of(new JsonResultContent(VALID_REVIEW_JSON)),
                  false,
                  "{}"));
          return new FakeHandle();
        };
    ToolGateway.PreflightResult mismatchResult =
        EditReviewPreflight.prepare(mismatchIdTransport, createRequest(), "reason");
    ToolGateway.Deny mismatchDeny = assertInstanceOf(ToolGateway.Deny.class, mismatchResult);
    assertTrue(mismatchDeny.error().message().contains("mismatched call ID"));

    // null result
    EnvironmentCapabilityTransport nullResultTransport =
        (envId, request, listener) -> {
          listener.onComplete(null);
          return new FakeHandle();
        };
    ToolGateway.PreflightResult nullResult =
        EditReviewPreflight.prepare(nullResultTransport, createRequest(), "reason");
    assertInstanceOf(ToolGateway.Deny.class, nullResult);

    // Non-JSON content
    EnvironmentCapabilityTransport nonJsonTransport =
        (envId, request, listener) -> {
          listener.onComplete(
              new EnvironmentCapabilityResult(
                  request.call().id(), List.of(new TextResultContent("not-json")), false, "{}"));
          return new FakeHandle();
        };
    ToolGateway.PreflightResult nonJsonResult =
        EditReviewPreflight.prepare(nonJsonTransport, createRequest(), "reason");
    ToolGateway.Deny nonJsonDeny = assertInstanceOf(ToolGateway.Deny.class, nonJsonResult);
    assertTrue(
        nonJsonDeny
            .error()
            .message()
            .contains("Edit preview must return one complete JSON review"));

    // Multiple contents
    EnvironmentCapabilityTransport multipleContentTransport =
        (envId, request, listener) -> {
          listener.onComplete(
              new EnvironmentCapabilityResult(
                  request.call().id(),
                  List.of(new JsonResultContent(VALID_REVIEW_JSON), new TextResultContent("extra")),
                  false,
                  "{}"));
          return new FakeHandle();
        };
    ToolGateway.PreflightResult multipleResult =
        EditReviewPreflight.prepare(multipleContentTransport, createRequest(), "reason");
    assertInstanceOf(ToolGateway.Deny.class, multipleResult);
  }

  @Test
  void previewResultExceedingSizeLimitReturnsDeny() {
    String hugeDiff = "x".repeat(ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES - 200);
    String hugeReviewJson =
        "{\"path\":\""
            + VALID_PATH
            + "\",\"diff\":\""
            + hugeDiff
            + "\",\"sha256\":\""
            + VALID_SHA
            + "\"}";

    EnvironmentCapabilityTransport hugeTransport =
        (envId, request, listener) -> {
          listener.onComplete(
              new EnvironmentCapabilityResult(
                  request.call().id(),
                  List.of(new JsonResultContent(hugeReviewJson)),
                  false,
                  "{}"));
          return new FakeHandle();
        };

    ToolGateway.PreflightResult result =
        EditReviewPreflight.prepare(hugeTransport, createRequest(), "reason");
    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals("EDIT_REVIEW_FAILED", deny.error().kind());
    assertTrue(deny.error().message().contains("exceeds the result limit"));
  }

  @Test
  void interruptedThreadCancelsHandleAndRestoresFlag() {
    FakeHandle handle = new FakeHandle();
    EnvironmentCapabilityTransport transport =
        (envId, request, listener) -> {
          // 不调用 listener，直接返回 handle；外部 thread 处于 interrupted 状态
          return handle;
        };

    Thread.currentThread().interrupt();
    try {
      ToolGateway.PreflightResult result =
          EditReviewPreflight.prepare(transport, createRequest(), "reason");
      ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
      assertEquals("EDIT_REVIEW_FAILED", deny.error().kind());
      assertTrue(handle.cancelled.get());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      // 清除中断标记，避免影响后续测试
      Thread.interrupted();
    }
  }

  private static ToolInvocationRequest createRequest() {
    ToolContribution contribution =
        ToolGatewayTestSupport.defaultCatalog().findTool("edit").orElseThrow();
    ContributorBinding contributor =
        new ContributorBinding(
            contribution.id().contributorId().value(), contribution.id().localName(), List.of());
    String args = "{\"path\":\"/srv/repo/App.java\",\"old_string\":\"old\",\"new_string\":\"new\"}";
    return new ToolInvocationRequest(
        new ToolCall("call-1", "edit", args),
        new ToolBinding(
            contribution.definition(), contributor, EnvironmentSupport.REQUIRED, ENV_ID, "env-1"));
  }

  private static final class FakeHandle implements EnvironmentCapabilityExecutionHandle {
    final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public void terminate(EnvironmentCapabilityTerminationCause cause) {
      Objects.requireNonNull(cause, "cause");
      cancel();
    }

    @Override
    public Duration terminalDeliveryGrace() {
      return Duration.ZERO;
    }
  }
}
