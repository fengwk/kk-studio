package fun.fengwk.kkstudio.core.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseEnvironmentToolInvocationWorkerStore;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolWorkerConfig;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Unit contracts for the transport-neutral durable Environment Daemon gateway. */
class EnvironmentDaemonGatewayTest {

  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 9001L;
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final String GATEWAY_TOKEN = "gateway-test-token";

  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonToolCapabilitiesCodec capabilitiesCodec = new DaemonToolCapabilitiesCodec();
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();

  /** HELLO/CAPABILITIES/READY canonicalizes facts and immediately pulls exactly one invocation. */
  @Test
  void pullsInvocationAfterReadyAndPersistsRemotePartialAndCompletion() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection connection = new FakeConnection("connection-a");
    fixture.gateway.open(connection);

    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    List<DaemonEnvelope> outbound = connection.envelopes();
    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE), messageTypes(outbound));
    DaemonEnvelope invoke = outbound.get(1);
    assertEquals(Long.toString(INVOCATION_ID), invoke.invocationId());
    assertTrue(invoke.payloadJson().contains("\"toolName\":\"read\""));
    assertTrue(invoke.payloadJson().contains("\"timeoutMillis\":"));
    verify(fixture.transactions).start(eq(fixture.claimed), eq(NOW));

    fixture.gateway.receive(connection.connectionId(), started(3));
    String partialPayload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(
                    new TextToolContent("chunk"),
                    new ArtifactToolContent(new ArtifactRef("local", "text/plain", 2))),
                false,
                "{}",
                false),
            ignored -> new byte[] {1, 2});
    fixture.gateway.receive(connection.connectionId(), partial(4, partialPayload));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ToolResult>> partials = ArgumentCaptor.forClass(List.class);
    verify(fixture.transactions).appendPartial(eq(fixture.claimed), partials.capture(), eq(NOW));
    ToolResult storedPartial = partials.getValue().get(0);
    assertEquals("provider-call", storedPartial.toolCallId());
    ArtifactToolContent artifact = (ArtifactToolContent) storedPartial.contents().get(1);
    assertEquals("global-artifact", artifact.artifact().artifactId());
    verify(fixture.artifactStore).save("text/plain", "identity", new byte[] {1, 2});

    String completionPayload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new TextToolContent("done")),
                false,
                "{}",
                false),
            ignored -> new byte[0]);
    fixture.gateway.receive(connection.connectionId(), completed(5, completionPayload));

    ArgumentCaptor<ToolResult> terminal = ArgumentCaptor.forClass(ToolResult.class);
    verify(fixture.transactions)
        .terminate(
            eq(fixture.claimed),
            eq(ToolInvocationStatus.SUCCEEDED),
            terminal.capture(),
            isNull(),
            eq(NOW));
    assertEquals("provider-call", terminal.getValue().toolCallId());
    assertEquals("done", ((TextToolContent) terminal.getValue().contents().get(0)).text());
  }

  /**
   * A lost partial-write lease drops only the transient active handle so the durable lease
   * recovers.
   */
  @Test
  void dropsActiveHandleWhenPartialCannotBePersisted() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    when(fixture.invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any()))
        .thenReturn(Optional.of(fixture.claimed), Optional.empty());
    when(fixture.transactions.appendPartial(eq(fixture.claimed), any(), eq(NOW))).thenReturn(false);
    FakeConnection connection = new FakeConnection("connection-lost-partial-lease");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));
    String payload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new TextToolContent("chunk")),
                false,
                "{}",
                false),
            ignored -> new byte[0]);
    fixture.gateway.receive(connection.connectionId(), partial(3, payload));
    fixture.gateway.pollOnce();

    verify(fixture.transactions).appendPartial(eq(fixture.claimed), any(), eq(NOW));
    verify(fixture.invocationStore, times(2))
        .claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any());
    verify(fixture.transactions, never())
        .terminate(eq(fixture.claimed), any(), any(), any(), any());
  }

  /**
   * Reusing a transport ID closes the stale transport before the replacement can bind an
   * Environment.
   */
  @Test
  void replacesExistingConnectionWithTheSameTransportId() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection first = new FakeConnection("shared-connection");
    FakeConnection replacement = new FakeConnection("shared-connection");
    fixture.gateway.open(first);
    fixture.gateway.open(replacement);

    assertTrue(first.closed);
    fixture.gateway.receive(replacement.connectionId(), hello(0));
    assertFalse(replacement.closed);
  }

  /**
   * Abort state is relayed as CANCEL and daemon CANCELLED reaches the durable terminal transition.
   */
  @Test
  void relaysCancellationAndCompletesCancelledInvocation() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection connection = new FakeConnection("connection-cancel");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    ToolInvocation cancellationRequested =
        withStatus(fixture.invocation, ToolInvocationStatus.CANCEL_REQUESTED);
    when(fixture.invocationStore.find(INVOCATION_ID))
        .thenReturn(Optional.of(cancellationRequested));
    when(fixture.invocationStore.heartbeat(any(), eq(NOW), any())).thenReturn(true);
    fixture.gateway.pollOnce();

    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));
    fixture.gateway.receive(connection.connectionId(), cancelled(3, "cancelled by user"));

    verify(fixture.transactions)
        .terminate(
            eq(fixture.claimed),
            eq(ToolInvocationStatus.CANCELLED),
            any(ToolResult.class),
            eq("cancelled by user"),
            eq(NOW));
  }

  /**
   * Polling remains responsive while durable lease writes follow the configured heartbeat cadence.
   */
  @Test
  void rateLimitsActiveLeaseHeartbeats() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    when(fixture.invocationStore.find(INVOCATION_ID)).thenReturn(Optional.of(fixture.invocation));
    when(fixture.invocationStore.heartbeat(any(), eq(NOW), any())).thenReturn(true);
    FakeConnection connection = new FakeConnection("connection-heartbeat-cadence");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    fixture.gateway.pollOnce();
    fixture.gateway.pollOnce();

    verify(fixture.invocationStore).heartbeat(any(), eq(NOW), any());
  }

  /** Non-idempotent recovered work becomes UNKNOWN instead of being sent to a new daemon. */
  @Test
  void marksRecoveredNonIdempotentInvocationUnknownWithoutInvoke() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT, true);
    FakeConnection connection = new FakeConnection("connection-recovered");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(fixture.claimed),
            eq(ToolInvocationStatus.UNKNOWN),
            any(ToolResult.class),
            eq("non-idempotent invocation lease expired"),
            eq(NOW));
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /** Frozen NON_IDEMPOTENT recovery wins before a concurrent cancellation request. */
  @Test
  void prioritizesRecoveredNonIdempotentOverCancellation() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT, true);
    ToolInvocation cancelled = withStatus(fixture.invocation, ToolInvocationStatus.RUNNING);
    ClaimedToolInvocation claimed = new ClaimedToolInvocation(cancelled, true);
    when(fixture.invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any()))
        .thenReturn(Optional.of(claimed));
    FakeConnection connection = new FakeConnection("connection-recovered-cancelled");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(claimed),
            eq(ToolInvocationStatus.UNKNOWN),
            any(ToolResult.class),
            eq("non-idempotent invocation lease expired"),
            eq(NOW));
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /** Frozen NON_IDEMPOTENT recovery wins before deadline evaluation. */
  @Test
  void prioritizesRecoveredNonIdempotentOverDeadline() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT, true);
    ToolInvocation expired = withDeadline(fixture.invocation, NOW.minusMillis(1));
    ClaimedToolInvocation claimed = new ClaimedToolInvocation(expired, true);
    when(fixture.invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any()))
        .thenReturn(Optional.of(claimed));
    FakeConnection connection = new FakeConnection("connection-recovered-expired");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(claimed),
            eq(ToolInvocationStatus.UNKNOWN),
            any(ToolResult.class),
            eq("non-idempotent invocation lease expired"),
            eq(NOW));
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /** Frozen NON_IDEMPOTENT recovery wins before current Environment descriptor resolution. */
  @Test
  void prioritizesRecoveredNonIdempotentOverDescriptorAvailability() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT, true);
    FakeConnection connection = new FakeConnection("connection-recovered-unavailable");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(fixture.claimed),
            eq(ToolInvocationStatus.UNKNOWN),
            any(ToolResult.class),
            eq("non-idempotent invocation lease expired"),
            eq(NOW));
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /**
   * STARTED replay and HEARTBEAT are transport facts; FAILED is converted to one durable terminal.
   */
  @Test
  void acceptsReplayHeartbeatAndFailedTerminal() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection connection = new FakeConnection("connection-failed");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    fixture.gateway.receive(connection.connectionId(), startedReplayed(3));
    fixture.gateway.receive(connection.connectionId(), heartbeat(4));
    fixture.gateway.receive(connection.connectionId(), failed(5, "remote tool failed"));

    verify(fixture.transactions)
        .terminate(
            eq(fixture.claimed),
            eq(ToolInvocationStatus.FAILED),
            any(ToolResult.class),
            eq("remote tool failed"),
            eq(NOW));
  }

  /**
   * First connected name wins: a later same-name HELLO is rejected without displacing the original,
   * while invalid phase ordering closes only the offender.
   */
  @Test
  void rejectsLaterSameNameHelloAndInvalidPhaseOrder() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection first = new FakeConnection("connection-first");
    FakeConnection later = new FakeConnection("connection-later");
    fixture.gateway.open(first);
    fixture.gateway.receive(first.connectionId(), hello(0));
    fixture.gateway.open(later);
    fixture.gateway.receive(later.connectionId(), hello(0));
    assertFalse(first.closed);
    assertTrue(later.closed);

    FakeConnection invalid = new FakeConnection("connection-invalid-phase");
    fixture.gateway.open(invalid);
    fixture.gateway.receive(invalid.connectionId(), ready(0));
    assertTrue(invalid.closed);
  }

  /** Invalid daemon credentials are rejected before an Environment heartbeat can be persisted. */
  @Test
  void rejectsInvalidDaemonTokenBeforeBindingEnvironment() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection connection = new FakeConnection("connection-invalid-token");
    fixture.gateway.open(connection);

    fixture.gateway.receive(
        connection.connectionId(),
        envelope(
            DaemonMessageType.HELLO,
            null,
            0,
            "{\"daemonId\":\"daemon-a\",\"protocolVersion\":1,\"gatewayToken\":\"wrong\"}"));

    assertTrue(connection.closed);
  }

  /**
   * A deadline that elapsed before dispatch becomes durable FAILED instead of an invalid remote
   * INVOKE.
   */
  @Test
  void failsExpiredInvocationBeforeRemoteDispatch() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    ToolInvocation expired = withDeadline(fixture.invocation, NOW);
    ClaimedToolInvocation expiredClaimed = new ClaimedToolInvocation(expired, false);
    when(fixture.invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any()))
        .thenReturn(Optional.of(expiredClaimed));
    when(fixture.transactions.terminate(eq(expiredClaimed), any(), any(), any(), eq(NOW)))
        .thenReturn(true);
    FakeConnection connection = new FakeConnection("connection-expired");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(expiredClaimed),
            eq(ToolInvocationStatus.FAILED),
            any(ToolResult.class),
            eq("Tool execution deadline exceeded."),
            eq(NOW));
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /**
   * Corrupt persisted arguments fail before start so a daemon is never left waiting for an INVOKE.
   */
  @Test
  void failsMalformedFrozenArgumentsBeforeStartingRemoteInvocation() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    ClaimedToolInvocation malformedClaimed =
        new ClaimedToolInvocation(withArguments(fixture.invocation, "[]"), false);
    when(fixture.invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any()))
        .thenReturn(Optional.of(malformedClaimed));
    when(fixture.transactions.terminate(eq(malformedClaimed), any(), any(), any(), eq(NOW)))
        .thenReturn(true);
    FakeConnection connection = new FakeConnection("connection-malformed-arguments");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(malformedClaimed),
            eq(ToolInvocationStatus.FAILED),
            any(ToolResult.class),
            eq("frozen Environment tool arguments must be a JSON object"),
            eq(NOW));
    verify(fixture.transactions, never()).start(eq(malformedClaimed), any());
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /**
   * A transport send failure leaves no forged terminal result and closes the transient handle for
   * lease recovery.
   */
  @Test
  void closesConnectionWhenInvokeSendFailsWithoutTerminatingInvocation() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection connection = new FakeConnection("connection-send-failure");
    connection.failOnSend(2);
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    assertTrue(connection.closed);
    verify(fixture.transactions).start(eq(fixture.claimed), eq(NOW));
    verify(fixture.transactions, never())
        .terminate(eq(fixture.claimed), any(), any(), any(), any());
  }

  /** A claimed cancellation is terminalized locally and never reaches a remote daemon. */
  @Test
  void cancelsClaimedInvocationBeforeDispatch() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    ClaimedToolInvocation cancelledClaimed =
        new ClaimedToolInvocation(
            withStatus(fixture.invocation, ToolInvocationStatus.CANCEL_REQUESTED), false);
    when(fixture.invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any()))
        .thenReturn(Optional.of(cancelledClaimed));
    when(fixture.transactions.terminate(eq(cancelledClaimed), any(), any(), any(), eq(NOW)))
        .thenReturn(true);
    FakeConnection connection = new FakeConnection("connection-cancel-before-dispatch");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(cancelledClaimed),
            eq(ToolInvocationStatus.CANCELLED),
            any(ToolResult.class),
            eq("Tool execution cancelled."),
            eq(NOW));
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /**
   * Frozen capability loss is durable FAILED, preserving the Run rather than emitting an invalid
   * INVOKE.
   */
  @Test
  void failsWhenEnvironmentNoLongerAdvertisesFrozenCapability() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    String emptyCapabilitiesJson =
        capabilitiesCodec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(), List.of()));
    FakeConnection connection = new FakeConnection("connection-capability-lost");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, emptyCapabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    verify(fixture.transactions)
        .terminate(
            eq(fixture.claimed),
            eq(ToolInvocationStatus.FAILED),
            any(ToolResult.class),
            anyString(),
            eq(NOW));
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
  }

  /**
   * A failing after hook replaces remote success with durable FAILED rather than losing the
   * callback.
   */
  @Test
  void convertsAfterHookFailureToFailedTerminal() {
    ToolInterceptorChain failingChain =
        new ToolInterceptorChain(
            List.of(),
            List.of(
                context -> {
                  throw new IllegalStateException("after hook failed");
                }));
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false, failingChain);
    FakeConnection connection = new FakeConnection("connection-after-hook");
    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(connection.connectionId(), ready(2));
    String payload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new TextToolContent("done")),
                false,
                "{}",
                false),
            ignored -> new byte[0]);
    fixture.gateway.receive(connection.connectionId(), completed(3, payload));

    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    verify(fixture.transactions)
        .terminate(
            eq(fixture.claimed),
            eq(ToolInvocationStatus.FAILED),
            any(ToolResult.class),
            errorCaptor.capture(),
            eq(NOW));
    assertTrue(errorCaptor.getValue().contains("after hook failed"));
  }

  /**
   * Protocol scope and handshake payload violations close a connection without invoking the worker.
   */
  @Test
  void rejectsMismatchedEnvironmentAndInvalidHandshakePayload() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection invalidHello = new FakeConnection("connection-invalid-hello");
    fixture.gateway.open(invalidHello);
    fixture.gateway.receive(
        invalidHello.connectionId(),
        envelope(
            DaemonMessageType.HELLO,
            null,
            0,
            "{\"daemonId\":\"d\",\"protocolVersion\":2,\"gatewayToken\":\""
                + GATEWAY_TOKEN
                + "\"}"));
    assertTrue(invalidHello.closed);

    FakeConnection mismatch = new FakeConnection("connection-mismatch");
    fixture.gateway.open(mismatch);
    fixture.gateway.receive(mismatch.connectionId(), hello(0));
    fixture.gateway.receive(
        mismatch.connectionId(),
        envelopeForEnvironment(
            DaemonMessageType.CAPABILITIES,
            ENVIRONMENT_NAME + "-other",
            null,
            1,
            fixture.capabilitiesJson));
    assertTrue(mismatch.closed);
    verify(fixture.invocationStore, never()).claimDue(anyString(), anyString(), any(), any());
  }

  /** READY and STARTED payload shapes are strict even after a valid handshake. */
  @Test
  void rejectsInvalidReadyAndStartedPayloads() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection invalidReady = new FakeConnection("connection-invalid-ready");
    fixture.gateway.open(invalidReady);
    fixture.gateway.receive(invalidReady.connectionId(), hello(0));
    fixture.gateway.receive(invalidReady.connectionId(), capabilities(1, fixture.capabilitiesJson));
    fixture.gateway.receive(
        invalidReady.connectionId(),
        envelope(DaemonMessageType.READY, null, 2, "{\"pull\":false}"));
    assertTrue(invalidReady.closed);

    Fixture startedFixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection invalidStarted = new FakeConnection("connection-invalid-started");
    startedFixture.gateway.open(invalidStarted);
    startedFixture.gateway.receive(invalidStarted.connectionId(), hello(0));
    startedFixture.gateway.receive(
        invalidStarted.connectionId(), capabilities(1, startedFixture.capabilitiesJson));
    startedFixture.gateway.receive(invalidStarted.connectionId(), ready(2));
    startedFixture.gateway.receive(
        invalidStarted.connectionId(),
        envelope(
            DaemonMessageType.STARTED, Long.toString(INVOCATION_ID), 3, "{\"replayed\":false}"));
    assertTrue(invalidStarted.closed);
  }

  /** ACK and ERROR are transport-only facts but still require their v1 payload contracts. */
  @Test
  void rejectsInvalidAckAndErrorPayloads() {
    Fixture ackFixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection invalidAck = new FakeConnection("connection-invalid-ack");
    ackFixture.gateway.open(invalidAck);
    ackFixture.gateway.receive(invalidAck.connectionId(), hello(0));
    ackFixture.gateway.receive(
        invalidAck.connectionId(), capabilities(1, ackFixture.capabilitiesJson));
    ackFixture.gateway.receive(invalidAck.connectionId(), ready(2));
    ackFixture.gateway.receive(
        invalidAck.connectionId(), envelope(DaemonMessageType.ACK, null, 3, "{}"));
    assertTrue(invalidAck.closed);

    Fixture errorFixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection invalidError = new FakeConnection("connection-invalid-error");
    errorFixture.gateway.open(invalidError);
    errorFixture.gateway.receive(invalidError.connectionId(), hello(0));
    errorFixture.gateway.receive(
        invalidError.connectionId(),
        envelope(DaemonMessageType.ERROR, null, 1, "{\"message\":\"\"}"));
    assertTrue(invalidError.closed);
  }

  /**
   * Wrong direction and non-contiguous sequence close only the offending connection before DB
   * mutation.
   */
  @Test
  void rejectsWrongDirectionAndSequenceBeforeDurableMutation() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY, false);
    FakeConnection wrongDirection = new FakeConnection("connection-wrong-direction");
    fixture.gateway.open(wrongDirection);
    fixture.gateway.receive(
        wrongDirection.connectionId(),
        envelope(DaemonMessageType.INVOKE, Long.toString(INVOCATION_ID), 0, "{}"));
    assertTrue(wrongDirection.closed);
    verify(fixture.invocationStore, never()).claimDue(anyString(), anyString(), any(), any());

    FakeConnection sequence = new FakeConnection("connection-sequence");
    fixture.gateway.open(sequence);
    fixture.gateway.receive(sequence.connectionId(), hello(0));
    fixture.gateway.receive(sequence.connectionId(), capabilities(2, fixture.capabilitiesJson));
    assertTrue(sequence.closed);
  }

  private Fixture fixture(ToolSideEffect sideEffect, boolean recovered) {
    return fixture(sideEffect, recovered, new ToolInterceptorChain(List.of(), List.of()));
  }

  private Fixture fixture(
      ToolSideEffect sideEffect, boolean recovered, ToolInterceptorChain interceptorChain) {
    LiveEnvironmentRegistry environmentRegistry = new LiveEnvironmentRegistry(capabilitiesCodec);
    DatabaseEnvironmentToolInvocationWorkerStore invocationStore =
        mock(DatabaseEnvironmentToolInvocationWorkerStore.class);
    ToolInvocationTransactions transactions = mock(ToolInvocationTransactions.class);
    ArtifactStore artifactStore = mock(ArtifactStore.class);
    ToolDescriptor descriptor = descriptor(sideEffect);
    String capabilitiesJson =
        capabilitiesCodec.encode(
            new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(List.of(descriptor), List.of()));
    ToolInvocation invocation = invocation(sideEffect);
    ClaimedToolInvocation claimed = new ClaimedToolInvocation(invocation, recovered);
    when(invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), eq(NOW), any()))
        .thenReturn(Optional.of(claimed));
    when(invocationStore.listDueEnvironmentCandidates(eq(NOW), anyInt())).thenReturn(List.of());
    when(transactions.start(eq(claimed), eq(NOW))).thenReturn(true);
    when(transactions.appendPartial(eq(claimed), any(), eq(NOW))).thenReturn(true);
    when(transactions.terminate(eq(claimed), any(), any(), any(), eq(NOW))).thenReturn(true);
    when(artifactStore.save("text/plain", "identity", new byte[] {1, 2}))
        .thenReturn(new ArtifactRef("global-artifact", "text/plain", 2));

    HarnessRuntimeProperties runtimeProperties = new HarnessRuntimeProperties();
    runtimeProperties.setWorkerId("gateway-test");
    EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
    gatewayProperties.setDaemonToken(GATEWAY_TOKEN);
    EnvironmentDaemonGateway gateway =
        new EnvironmentDaemonGateway(
            environmentRegistry,
            invocationStore,
            transactions,
            interceptorChain,
            artifactStore,
            new HarnessLifecycleObservers(List.of()),
            capabilitiesCodec,
            runtimeProperties,
            gatewayProperties,
            ToolWorkerConfig.DEFAULT,
            Clock.fixed(NOW, ZoneOffset.UTC));
    return new Fixture(
        gateway,
        environmentRegistry,
        invocationStore,
        transactions,
        artifactStore,
        invocation,
        claimed,
        capabilitiesJson);
  }

  private ToolInvocation invocation(ToolSideEffect sideEffect) {
    return new ToolInvocation(
        INVOCATION_ID,
        7001L,
        8001L,
        0,
        "provider-call",
        "read",
        "1",
        ToolExecutionLocation.ENVIRONMENT,
        ENVIRONMENT_NAME,
        "{}",
        ToolInvocationStatus.RUNNING,
        PermissionAction.ALLOW,
        null,
        sideEffect,
        NOW.plusSeconds(60),
        "gateway-test-environment-env-42",
        NOW.plusSeconds(30),
        null,
        null,
        null,
        NOW,
        NOW,
        null,
        NOW);
  }

  private ToolInvocation withStatus(ToolInvocation source, ToolInvocationStatus status) {
    return new ToolInvocation(
        source.id(),
        source.threadId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.toolCallId(),
        source.toolName(),
        source.toolVersion(),
        source.location(),
        source.environmentName(),
        source.argumentsJson(),
        status,
        source.permissionAction(),
        source.permissionDecision(),
        source.sideEffect(),
        source.deadlineAt(),
        source.leaseOwner(),
        source.leaseUntil(),
        NOW,
        source.resultJson(),
        source.errorMessage(),
        source.createdAt(),
        source.startedAt(),
        source.finishedAt(),
        source.updatedAt());
  }

  private ToolInvocation withDeadline(ToolInvocation source, Instant deadlineAt) {
    return new ToolInvocation(
        source.id(),
        source.threadId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.toolCallId(),
        source.toolName(),
        source.toolVersion(),
        source.location(),
        source.environmentName(),
        source.argumentsJson(),
        source.status(),
        source.permissionAction(),
        source.permissionDecision(),
        source.sideEffect(),
        deadlineAt,
        source.leaseOwner(),
        source.leaseUntil(),
        source.cancelRequestedAt(),
        source.resultJson(),
        source.errorMessage(),
        source.createdAt(),
        source.startedAt(),
        source.finishedAt(),
        source.updatedAt());
  }

  private ToolInvocation withArguments(ToolInvocation source, String argumentsJson) {
    return new ToolInvocation(
        source.id(),
        source.threadId(),
        source.assistantEntryId(),
        source.ordinal(),
        source.toolCallId(),
        source.toolName(),
        source.toolVersion(),
        source.location(),
        source.environmentName(),
        argumentsJson,
        source.status(),
        source.permissionAction(),
        source.permissionDecision(),
        source.sideEffect(),
        source.deadlineAt(),
        source.leaseOwner(),
        source.leaseUntil(),
        source.cancelRequestedAt(),
        source.resultJson(),
        source.errorMessage(),
        source.createdAt(),
        source.startedAt(),
        source.finishedAt(),
        source.updatedAt());
  }

  private ToolDescriptor descriptor(ToolSideEffect sideEffect) {
    return new ToolDescriptor(
        "read",
        "1",
        "read",
        "read",
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolExecutionLocation.ENVIRONMENT,
        sideEffect,
        Duration.ofSeconds(30));
  }

  private String hello(long sequence) {
    return envelope(
        DaemonMessageType.HELLO,
        null,
        sequence,
        "{\"daemonId\":\"daemon-a\",\"protocolVersion\":1,\"gatewayToken\":\""
            + GATEWAY_TOKEN
            + "\"}");
  }

  private String capabilities(long sequence, String payload) {
    return envelope(DaemonMessageType.CAPABILITIES, null, sequence, payload);
  }

  private String ready(long sequence) {
    return envelope(DaemonMessageType.READY, null, sequence, "{\"pull\":true}");
  }

  private String started(long sequence) {
    return envelope(DaemonMessageType.STARTED, Long.toString(INVOCATION_ID), sequence, "{}");
  }

  private String startedReplayed(long sequence) {
    return envelope(
        DaemonMessageType.STARTED, Long.toString(INVOCATION_ID), sequence, "{\"replayed\":true}");
  }

  private String heartbeat(long sequence) {
    return envelope(DaemonMessageType.HEARTBEAT, null, sequence, "{}");
  }

  private String partial(long sequence, String payload) {
    return envelope(DaemonMessageType.PARTIAL, Long.toString(INVOCATION_ID), sequence, payload);
  }

  private String completed(long sequence, String payload) {
    return envelope(DaemonMessageType.COMPLETED, Long.toString(INVOCATION_ID), sequence, payload);
  }

  private String cancelled(long sequence, String reason) {
    return envelope(
        DaemonMessageType.CANCELLED,
        Long.toString(INVOCATION_ID),
        sequence,
        "{\"reason\":\"" + reason + "\"}");
  }

  private String failed(long sequence, String message) {
    return envelope(
        DaemonMessageType.FAILED,
        Long.toString(INVOCATION_ID),
        sequence,
        "{\"message\":\"" + message + "\"}");
  }

  private String envelope(
      DaemonMessageType messageType, String invocationId, long sequence, String payload) {
    return envelopeForEnvironment(messageType, ENVIRONMENT_NAME, invocationId, sequence, payload);
  }

  private String envelopeForEnvironment(
      DaemonMessageType messageType,
      String environmentName,
      String invocationId,
      long sequence,
      String payload) {
    return envelopeCodec.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_1,
            messageType,
            environmentName,
            invocationId,
            sequence,
            payload));
  }

  private List<DaemonMessageType> messageTypes(List<DaemonEnvelope> envelopes) {
    return envelopes.stream().map(DaemonEnvelope::messageType).toList();
  }

  private final class FakeConnection implements EnvironmentDaemonConnection {
    private final String id;
    private final List<String> sent = new ArrayList<>();
    private boolean closed;
    private int sendCount;
    private int failOnSend = -1;

    private FakeConnection(String id) {
      this.id = id;
    }

    @Override
    public String connectionId() {
      return id;
    }

    @Override
    public boolean isOpen() {
      return !closed;
    }

    @Override
    public void sendText(String text) {
      sendCount++;
      if (sendCount == failOnSend) {
        throw new IllegalStateException("simulated send failure");
      }
      sent.add(text);
    }

    @Override
    public void close() {
      closed = true;
    }

    private List<DaemonEnvelope> envelopes() {
      return sent.stream().map(envelopeCodec::decode).toList();
    }

    private void failOnSend(int sendNumber) {
      failOnSend = sendNumber;
    }
  }

  private record Fixture(
      EnvironmentDaemonGateway gateway,
      LiveEnvironmentRegistry environmentRegistry,
      DatabaseEnvironmentToolInvocationWorkerStore invocationStore,
      ToolInvocationTransactions transactions,
      ArtifactStore artifactStore,
      ToolInvocation invocation,
      ClaimedToolInvocation claimed,
      String capabilitiesJson) {}
}
