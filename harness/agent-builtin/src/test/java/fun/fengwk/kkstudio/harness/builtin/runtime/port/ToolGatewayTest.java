package fun.fengwk.kkstudio.harness.builtin.runtime.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.port.PortTestData;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** ToolGateway public contract：preflight outcomes、admission certainty 与 value validation。 */
class ToolGatewayTest {

  @Test
  void preflightResultIsSealedWithExactlyAllowAskDeny() {
    List<Class<?>> permitted = List.of(ToolGateway.PreflightResult.class.getPermittedSubclasses());
    assertEquals(3, permitted.size());
    assertTrue(permitted.contains(ToolGateway.Allow.class));
    assertTrue(permitted.contains(ToolGateway.Ask.class));
    assertTrue(permitted.contains(ToolGateway.Deny.class));
  }

  @Test
  void admissionIsSealedWithExactlyAdmittedBusyRejected() {
    List<Class<?>> permitted = List.of(ToolGateway.Admission.class.getPermittedSubclasses());
    assertEquals(3, permitted.size());
    assertTrue(permitted.contains(ToolGateway.Admitted.class));
    assertTrue(permitted.contains(ToolGateway.Busy.class));
    assertTrue(permitted.contains(ToolGateway.Rejected.class));
  }

  @Test
  void completionIsSealedWithExactlyFourTerminalOutcomes() {
    List<Class<?>> permitted = List.of(ToolGateway.Completion.class.getPermittedSubclasses());
    assertEquals(4, permitted.size());
    assertTrue(permitted.contains(ToolGateway.Succeeded.class));
    assertTrue(permitted.contains(ToolGateway.Failed.class));
    assertTrue(permitted.contains(ToolGateway.Cancelled.class));
    assertTrue(permitted.contains(ToolGateway.Unknown.class));
  }

  @Test
  void executionFreezesTheKeyAndTheRequest() {
    ToolGateway.RemoteExecutionObserver observer = recordingObserver();
    ToolGateway.Execution execution =
        new ToolGateway.Execution(
            UUID.fromString("00000000-0000-0000-0000-000000000009"),
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            UUID.fromString("00000000-0000-0000-0000-00000000000b"),
            1,
            PortTestData.toolRequest(),
            ToolApproval.notRequired(),
            observer);
    assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000009"), execution.invocationId());
    assertEquals(UUID.fromString("00000000-0000-0000-0000-000000000007"), execution.threadId());
    assertEquals(
        UUID.fromString("00000000-0000-0000-0000-00000000000b"), execution.assistantEntryId());
    assertEquals(1, execution.attempt());
    assertEquals(PortTestData.toolRequest(), execution.request());
    assertEquals(ToolApproval.notRequired(), execution.approval());
    assertSame(observer, execution.remoteExecutions());
    assertNull(execution.expectedFile());
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.Execution(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1,
                null,
                ToolApproval.notRequired(),
                observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.Execution(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1,
                PortTestData.toolRequest(),
                null,
                observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.Execution(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1,
                PortTestData.toolRequest(),
                ToolApproval.notRequired(),
                null));

    ExpectedFile expectedFile =
        new ExpectedFile(
            "/tmp/file.txt", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    ToolEditReview review = new ToolEditReview(expectedFile, "diff");
    ToolApproval reviewedApproval =
        new ToolApproval(
            true,
            ToolApprovalDecision.ALLOWED,
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "user",
            "approved",
            Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-07-01T00:00:01Z"),
            review);
    ToolGateway.Execution reviewedExecution =
        new ToolGateway.Execution(
            UUID.fromString("00000000-0000-0000-0000-000000000009"),
            UUID.fromString("00000000-0000-0000-0000-000000000007"),
            UUID.fromString("00000000-0000-0000-0000-00000000000b"),
            1,
            PortTestData.toolRequest(),
            reviewedApproval,
            observer);
    assertSame(reviewedApproval, reviewedExecution.approval());
    assertEquals(expectedFile, reviewedExecution.expectedFile());
  }

  @Test
  void askValidatesTheCanonicalReason() {
    assertEquals("needs confirmation", new ToolGateway.Ask("needs confirmation").reason());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Ask(null));
    assertThrows(IllegalArgumentException.class, () -> new ToolGateway.Ask(" "));
    assertThrows(IllegalArgumentException.class, () -> new ToolGateway.Ask(" padded "));
    assertThrows(IllegalArgumentException.class, () -> new ToolGateway.Ask("r".repeat(1025)));
  }

  @Test
  void allowAndDenyValidateTheirFacts() {
    ToolInvocationError error = new ToolInvocationError("DENIED", "no");
    assertEquals(new ToolGateway.Allow(), new ToolGateway.Allow());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Deny(null));
    assertEquals(error, new ToolGateway.Deny(error).error());
  }

  @Test
  void admissionOutcomesValidateTheirFacts() {
    ToolGateway.Run run = noopRun();
    assertSame(run, new ToolGateway.Admitted(run).run());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Admitted(null));
    ToolInvocationError error = new ToolInvocationError("BUSY", "busy");
    assertThrows(NullPointerException.class, () -> new ToolGateway.Busy(null));
    assertThrows(IllegalArgumentException.class, () -> new ToolGateway.Busy(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new ToolGateway.Busy(Duration.ofSeconds(-1)));
    assertThrows(
        IllegalArgumentException.class, () -> new ToolGateway.Busy(Duration.ofNanos(1_500_000)));
    assertEquals(Duration.ofSeconds(3), new ToolGateway.Busy(Duration.ofSeconds(3)).retryAfter());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Rejected(null));
    assertEquals(error, new ToolGateway.Rejected(error).error());
  }

  @Test
  void completionsValidateTheirFacts() {
    ToolInvocationError error = new ToolInvocationError("RATE_LIMITED", "slow down");
    assertThrows(NullPointerException.class, () -> new ToolGateway.Succeeded(null));
    assertThrows(NullPointerException.class, () -> new ToolGateway.Cancelled(null));
    assertThrows(NullPointerException.class, () -> new ToolGateway.Unknown(null));
    assertEquals(error, new ToolGateway.Cancelled(error).error());
    assertEquals(error, new ToolGateway.Unknown(error).error());
    ToolGateway.Failed retryable = new ToolGateway.Failed(error, true);
    assertEquals(error, retryable.error());
    assertTrue(retryable.retryable());
    assertFalse(new ToolGateway.Failed(error, false).retryable());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Failed(null, true));
  }

  @Test
  void preflightAndAdmitAcceptValidInputs() {
    ToolGateway gateway =
        new ToolGateway() {
          @Override
          public PreflightResult preflight(ToolInvocationRequest request) {
            return new ToolGateway.Allow();
          }

          @Override
          public Admission admit(Execution execution) {
            return new ToolGateway.Admitted(noopRun());
          }

          @Override
          public Run reattach(ReattachExecution execution) {
            return noopRun();
          }
        };
    assertEquals(new ToolGateway.Allow(), gateway.preflight(PortTestData.toolRequest()));
    ToolGateway.Admission result =
        gateway.admit(
            new ToolGateway.Execution(
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000007"),
                1,
                PortTestData.toolRequest(),
                ToolApproval.notRequired(),
                recordingObserver()));
    assertTrue(result instanceof ToolGateway.Admitted);
    ToolGateway.Run run = ((ToolGateway.Admitted) result).run();
    assertNotNull(run.execute(partial -> {}));
    run.close();

    UUID invocationId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    EnvironmentId envId = new EnvironmentId(UUID.randomUUID());
    ToolGateway.ReattachExecution reattachExecution =
        new ToolGateway.ReattachExecution(
            invocationId,
            threadId,
            PortTestData.toolRequest(),
            envId,
            UUID.fromString("00000000-0000-0000-0000-000000000008"),
            Duration.ofSeconds(30),
            recordingObserver());
    ToolGateway.Run reattachRun = gateway.reattach(reattachExecution);
    assertNotNull(reattachRun.execute(partial -> {}));
    reattachRun.close();
  }

  @Test
  void reattachExecutionValidation() {
    UUID invocationId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    ToolInvocationRequest request = PortTestData.toolRequest();
    EnvironmentId envId = new EnvironmentId(UUID.randomUUID());
    UUID remoteExecutionId = UUID.randomUUID();
    Duration timeout = Duration.ofSeconds(10);
    ToolGateway.RemoteExecutionObserver observer = recordingObserver();

    ToolGateway.ReattachExecution valid =
        new ToolGateway.ReattachExecution(
            invocationId, threadId, request, envId, remoteExecutionId, timeout, observer);
    assertSame(invocationId, valid.invocationId());
    assertSame(threadId, valid.threadId());
    assertSame(request, valid.request());
    assertSame(envId, valid.environmentId());
    assertSame(remoteExecutionId, valid.remoteExecutionId());
    assertEquals(timeout, valid.timeout());
    assertSame(observer, valid.remoteExecutions());

    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.ReattachExecution(
                null, threadId, request, envId, remoteExecutionId, timeout, observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.ReattachExecution(
                invocationId, null, request, envId, remoteExecutionId, timeout, observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.ReattachExecution(
                invocationId, threadId, null, envId, remoteExecutionId, timeout, observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.ReattachExecution(
                invocationId, threadId, request, null, remoteExecutionId, timeout, observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.ReattachExecution(
                invocationId, threadId, request, envId, null, timeout, observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.ReattachExecution(
                invocationId, threadId, request, envId, remoteExecutionId, null, observer));
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolGateway.ReattachExecution(
                invocationId, threadId, request, envId, remoteExecutionId, timeout, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolGateway.ReattachExecution(
                invocationId,
                threadId,
                request,
                envId,
                remoteExecutionId,
                Duration.ofNanos(500),
                observer));
  }

  /** 测试显式提供的观察者，不代替生产登记实现。 */
  private static ToolGateway.RemoteExecutionObserver recordingObserver() {
    return new ToolGateway.RemoteExecutionObserver() {
      @Override
      public boolean tryStart(UUID remoteExecutionId, EnvironmentId environmentId) {
        return true;
      }

      @Override
      public void confirmExit(UUID remoteExecutionId) {}

      @Override
      public void confirmNotStarted(UUID remoteExecutionId) {}
    };
  }

  private static ToolGateway.Run noopRun() {
    return new ToolGateway.Run() {
      @Override
      public ToolGateway.Completion execute(Consumer<ToolResult> progress) {
        return new ToolGateway.Unknown(new ToolInvocationError("NOOP", "noop"));
      }

      @Override
      public void close() {
        // 幂等释放
      }
    };
  }
}
