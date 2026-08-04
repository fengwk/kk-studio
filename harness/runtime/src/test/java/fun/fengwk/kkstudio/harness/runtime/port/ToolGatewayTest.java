package fun.fengwk.kkstudio.harness.runtime.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.List;

/** ToolGateway public contract: preflight outcomes, admission certainty and value validation. */
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
  void startResultIsSealedWithExactlyFiveAdmissionOutcomes() {
    List<Class<?>> permitted = List.of(ToolGateway.StartResult.class.getPermittedSubclasses());
    assertEquals(5, permitted.size());
    assertTrue(permitted.contains(ToolGateway.Started.class));
    assertTrue(permitted.contains(ToolGateway.Busy.class));
    assertTrue(permitted.contains(ToolGateway.Overloaded.class));
    assertTrue(permitted.contains(ToolGateway.Rejected.class));
    assertTrue(permitted.contains(ToolGateway.Indeterminate.class));
  }

  @Test
  void executionFreezesTheKeyAndTheRequest() {
    ToolGateway.Execution execution = new ToolGateway.Execution(9L, 2, PortTestData.toolRequest());
    assertEquals(9L, execution.invocationId());
    assertEquals(2, execution.proposedAttempt());
    assertEquals(PortTestData.toolRequest(), execution.request());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolGateway.Execution(0L, 1, PortTestData.toolRequest()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolGateway.Execution(1L, 0, PortTestData.toolRequest()));
    assertThrows(NullPointerException.class, () -> new ToolGateway.Execution(1L, 1, null));
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
  void startedAndBusyOverloadedRejectedIndeterminateValidateTheirFacts() {
    ToolGateway.Handle handle = () -> {};
    ToolGateway.Started started = new ToolGateway.Started(handle);
    assertNotNull(started.handle());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Started(null));
    ToolInvocationError error = new ToolInvocationError("BUSY", "busy");
    assertThrows(NullPointerException.class, () -> new ToolGateway.Busy(null));
    assertThrows(IllegalArgumentException.class, () -> new ToolGateway.Busy(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> new ToolGateway.Busy(Duration.ofSeconds(-1)));
    assertThrows(NullPointerException.class, () -> new ToolGateway.Overloaded(null));
    assertThrows(IllegalArgumentException.class, () -> new ToolGateway.Overloaded(Duration.ZERO));
    assertEquals(
        Duration.ofSeconds(3), new ToolGateway.Overloaded(Duration.ofSeconds(3)).retryAfter());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Rejected(null));
    assertEquals(error, new ToolGateway.Rejected(error).error());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Indeterminate(null));
    assertEquals(error, new ToolGateway.Indeterminate(error).error());
  }

  @Test
  void listenerSurfaceCoversPartialAndTheThreeTerminalOutcomes() {
    ToolGateway.Listener listener =
        new ToolGateway.Listener() {
          @Override
          public void onPartial(ToolResult partial) {}

          @Override
          public void onSucceeded(ToolResult result) {}

          @Override
          public void onFailed(ToolGateway.Failure failure) {}

          @Override
          public void onUnknown(ToolInvocationError error) {}
        };
    listener.onPartial(new ToolResult("call-1", List.of(), false, "{}", false));
    listener.onSucceeded(null);
    listener.onFailed(null);
    listener.onUnknown(null);
  }

  @Test
  void failureCarriesAnExplicitRetryableFact() {
    ToolInvocationError error = new ToolInvocationError("RATE_LIMITED", "slow down");
    ToolGateway.Failure retryable = new ToolGateway.Failure(error, true);
    assertEquals(error, retryable.error());
    assertTrue(retryable.retryable());
    ToolGateway.Failure permanent = new ToolGateway.Failure(error, false);
    assertFalse(permanent.retryable());
    assertThrows(NullPointerException.class, () -> new ToolGateway.Failure(null, true));
  }

  @Test
  void preflightAndStartAcceptValidInputs() {
    ToolGateway gateway =
        new ToolGateway() {
          @Override
          public PreflightResult preflight(ToolInvocationRequest request, boolean yoloEnabled) {
            return new ToolGateway.Allow();
          }

          @Override
          public StartResult start(Execution execution, Listener listener) {
            return new ToolGateway.Started(() -> {});
          }
        };
    assertEquals(new ToolGateway.Allow(), gateway.preflight(PortTestData.toolRequest(), true));
    ToolGateway.StartResult result =
        gateway.start(new ToolGateway.Execution(5L, 1, PortTestData.toolRequest()), noopListener());
    assertTrue(result instanceof ToolGateway.Started);
  }

  private static ToolGateway.Listener noopListener() {
    return new ToolGateway.Listener() {
      @Override
      public void onPartial(ToolResult partial) {}

      @Override
      public void onSucceeded(ToolResult result) {}

      @Override
      public void onFailed(ToolGateway.Failure failure) {}

      @Override
      public void onUnknown(ToolInvocationError error) {}
    };
  }
}
