package fun.fengwk.kkstudio.core.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.kernel.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.kernel.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class EnvironmentDaemonGatewayFinalTest {
  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 9001L;
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final String GATEWAY_TOKEN = "gateway-test-token";

  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonToolCapabilitiesCodec capabilitiesCodec = new DaemonToolCapabilitiesCodec();
  private final DaemonToolResultCodec resultCodec = new DaemonToolResultCodec();

  @Test
  void readyClaimsFrozenInvocationAndCompletionWakesOwningThread() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection connection = fixture.connectReady("connection-a");

    assertEquals(
        List.of(DaemonMessageType.WELCOME, DaemonMessageType.INVOKE),
        messageTypes(connection.envelopes()));
    DaemonEnvelope invoke = connection.envelopes().get(1);
    assertEquals(Long.toString(INVOCATION_ID), invoke.invocationId());
    assertTrue(invoke.payloadJson().contains("\"toolName\":\"read\""));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals("gateway-test-environment-env-42", fixture.transactions.workerToken);

    fixture.gateway.receive(connection.connectionId(), completed(3, resultPayload("done")));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertEquals("provider-call", fixture.transactions.result.toolCallId());
    assertEquals("done", text(fixture.transactions.result));
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 7001L)), fixture.activations);
    assertCompletion(fixture, InvocationStatus.SUCCEEDED, null);
  }

  @Test
  void partialIsRealtimeOnlyWhileActivityIsDurablyFenced() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection connection = fixture.connectReady("connection-partial");

    fixture.gateway.receive(connection.connectionId(), partial(3, resultPayload("chunk")));

    assertEquals(1, fixture.realtimeEvents.size());
    RealtimeEvent.ToolPartial event = (RealtimeEvent.ToolPartial) fixture.realtimeEvents.get(0);
    assertEquals(INVOCATION_ID, event.toolInvocationId());
    assertEquals("provider-call", event.partial().toolCallId());
    assertEquals("chunk", text(event.partial()));
    assertEquals(List.of(NOW), fixture.transactions.activities);
    assertNull(fixture.transactions.terminalStatus);
  }

  @Test
  void lostActivityFenceDropsTransientHandleAndAllowsRecoveryScan() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection connection = fixture.connectReady("connection-lost-activity");
    fixture.transactions.activityOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;

    fixture.gateway.receive(connection.connectionId(), partial(3, resultPayload("chunk")));
    fixture.gateway.pollOnce();

    assertEquals(2, fixture.transactions.findNextCalls);
    assertEquals(1, fixture.transactions.claimCalls);
    assertTrue(fixture.realtimeEvents.isEmpty());
    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));
    assertNull(fixture.transactions.terminalStatus);
  }

  @Test
  void partialArtifactIsRejectedWithoutDurablePersistence() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection connection = fixture.connectReady("connection-partial-artifact");
    String payload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new ArtifactToolContent(new ArtifactRef("daemon-local", "text/plain", 2))),
                false,
                "{}",
                false),
            ignored -> new byte[] {1, 2});

    fixture.gateway.receive(connection.connectionId(), partial(3, payload));

    assertTrue(connection.closed);
    assertTrue(fixture.artifacts.saved.isEmpty());
    assertNull(fixture.transactions.terminalStatus);
  }

  @Test
  void recoveredLeaseBecomesUnknownWithoutRemoteInvoke() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.transactions.recoveredLease = true;
    FakeConnection connection = fixture.connectReady("connection-recovered");

    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LEASE_EXPIRED", fixture.transactions.error.kind());
    assertEquals(
        "Tool ownership was lost; side effect result is unknown.",
        fixture.transactions.error.message());
    assertCompletion(
        fixture,
        InvocationStatus.UNKNOWN,
        "Tool ownership was lost; side effect result is unknown.");
  }

  @Test
  void expiredRunningEnvironmentRecoversWithoutReadyConnection() {
    ToolDescriptor descriptor = descriptor(ToolSideEffect.NON_IDEMPOTENT);
    Fixture fixture = new Fixture(descriptor);
    fixture.transactions.expiredRunning = running(descriptor, "expired-owner");
    fixture.transactions.recoveredLease = true;

    fixture.gateway.pollOnce();

    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("gateway-test-environment-recovery", fixture.transactions.workerToken);
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 7001L)), fixture.activations);
  }

  @Test
  void capabilityDriftFailsFrozenInvocationBeforeRemoteInvoke() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.advertisedDescriptor = descriptor(ToolSideEffect.IDEMPOTENT);
    FakeConnection connection = fixture.connectReady("connection-drift");

    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertTrue(fixture.transactions.error.message().contains("does not match frozen invocation"));
  }

  @Test
  void remoteFailureAndCancellationUseDistinctTerminalTransitions() {
    Fixture failed = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection failedConnection = failed.connectReady("connection-failed");
    failed.gateway.receive(failedConnection.connectionId(), failed(3, "remote failed"));
    assertEquals(InvocationStatus.FAILED, failed.transactions.terminalStatus);
    assertEquals("remote failed", failed.transactions.error.message());

    Fixture cancelled = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection cancelledConnection = cancelled.connectReady("connection-cancelled");
    cancelled.gateway.receive(
        cancelledConnection.connectionId(), cancelled(3, "cancelled by owner"));
    assertEquals(InvocationStatus.CANCELLED, cancelled.transactions.terminalStatus);
    assertNull(cancelled.transactions.error);
  }

  @Test
  void remoteArtifactsAreRebasedToGlobalArtifactIds() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection connection = fixture.connectReady("connection-artifact");
    String payload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new ArtifactToolContent(new ArtifactRef("daemon-local", "text/plain", 2))),
                false,
                "{}",
                false),
            ignored -> new byte[] {1, 2});

    fixture.gateway.receive(connection.connectionId(), completed(3, payload));

    ArtifactToolContent content =
        (ArtifactToolContent) fixture.transactions.result.contents().get(0);
    assertEquals("global-artifact", content.artifact().artifactId());
    assertEquals(List.of("text/plain:identity:2"), fixture.artifacts.saved);
  }

  @Test
  void artifactPersistenceFailureBecomesDeterministicTerminalFailure() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.artifacts.failure = new IllegalStateException("artifact database unavailable");
    FakeConnection connection = fixture.connectReady("connection-artifact-failure");
    String payload =
        resultCodec.encodeResult(
            new ToolResult(
                Long.toString(INVOCATION_ID),
                List.of(new ArtifactToolContent(new ArtifactRef("daemon-local", "text/plain", 2))),
                false,
                "{}",
                false),
            ignored -> new byte[] {1, 2});

    fixture.gateway.receive(connection.connectionId(), completed(3, payload));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("RESULT_PERSISTENCE_FAILED", fixture.transactions.error.kind());
    assertEquals("artifact database unavailable", fixture.transactions.error.message());
  }

  @Test
  void invokeSendFailureLeavesDurableLeaseForRecovery() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection connection = new FakeConnection("connection-send-failure");
    connection.failOnSend(2);

    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson()));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    assertTrue(connection.closed);
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(0, fixture.transactions.releaseCalls);
    assertNull(fixture.transactions.terminalStatus);
  }

  @Test
  void disconnectAfterClaimReleasesUnstartedQueuedWithoutTerminalizing() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection connection = new FakeConnection("connection-drop-after-claim");
    fixture.transactions.afterClaim =
        () -> {
          connection.close();
          fixture.gateway.close(connection.connectionId());
        };

    fixture.gateway.open(connection);
    fixture.gateway.receive(connection.connectionId(), hello(0));
    fixture.gateway.receive(connection.connectionId(), capabilities(1, fixture.capabilitiesJson()));
    fixture.gateway.receive(connection.connectionId(), ready(2));

    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(1, fixture.transactions.releaseCalls);
    assertEquals(InvocationStatus.QUEUED, fixture.transactions.releasedStatus);
    assertNull(fixture.transactions.releasedNextAttemptAt);
    assertNull(fixture.transactions.terminalStatus);
    assertFalse(messageTypes(connection.envelopes()).contains(DaemonMessageType.INVOKE));
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, INVOCATION_ID)),
        fixture.activations);
  }

  @Test
  void activeDeadlineSendsCancelAndConvergesToUnknown() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    FakeConnection connection = fixture.connectReady("connection-deadline");

    // running() freezes deadline at NOW+60s and lease until NOW+30s; advance past deadline.
    fixture.now.set(NOW.plusSeconds(61));
    fixture.gateway.pollOnce();

    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LEASE_EXPIRED", fixture.transactions.error.kind());
    assertEquals(
        "Tool deadline elapsed; remote side effect result is unknown.",
        fixture.transactions.error.message());
  }

  @Test
  void activePollingRenewsAtConfiguredCadenceAndLostFenceDropsHandle() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.renewOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    FakeConnection connection = fixture.connectReady("connection-renew");

    fixture.gateway.pollOnce();
    fixture.gateway.pollOnce();

    assertEquals(1, fixture.transactions.renewCalls);
    assertEquals(2, fixture.transactions.findNextCalls);
    assertTrue(messageTypes(connection.envelopes()).contains(DaemonMessageType.CANCEL));
  }

  @Test
  void protocolViolationsCloseOnlyOffenderBeforeDurableClaim() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection invalidToken = new FakeConnection("invalid-token");
    fixture.gateway.open(invalidToken);
    fixture.gateway.receive(
        invalidToken.connectionId(),
        envelope(
            DaemonMessageType.HELLO,
            null,
            0,
            "{\"daemonId\":\"daemon-a\",\"protocolVersion\":1,\"gatewayToken\":\"bad\"}"));
    assertTrue(invalidToken.closed);

    FakeConnection invalidSequence = new FakeConnection("invalid-sequence");
    fixture.gateway.open(invalidSequence);
    fixture.gateway.receive(invalidSequence.connectionId(), hello(0));
    fixture.gateway.receive(
        invalidSequence.connectionId(), capabilities(2, fixture.capabilitiesJson()));
    assertTrue(invalidSequence.closed);
    assertEquals(0, fixture.transactions.claimCalls);
  }

  @Test
  void duplicateInboundFrameIsIgnoredButConflictingReuseClosesConnection() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    FakeConnection duplicate = new FakeConnection("duplicate");
    fixture.gateway.open(duplicate);
    String hello = hello(0);
    fixture.gateway.receive(duplicate.connectionId(), hello);
    fixture.gateway.receive(duplicate.connectionId(), hello);
    assertFalse(duplicate.closed);
    assertEquals(List.of(DaemonMessageType.WELCOME), messageTypes(duplicate.envelopes()));

    fixture.gateway.receive(
        duplicate.connectionId(),
        envelope(DaemonMessageType.ERROR, null, 0, "{\"message\":\"conflict\"}"));
    assertTrue(duplicate.closed);
  }

  private Fixture fixture(ToolSideEffect sideEffect) {
    return new Fixture(descriptor(sideEffect));
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

  private ToolInvocation queued(ToolDescriptor descriptor) {
    return new ToolInvocation(
        INVOCATION_ID,
        7001L,
        8001L,
        0,
        "provider-call",
        descriptor,
        "{}",
        ToolExecutionLocation.ENVIRONMENT,
        ENVIRONMENT_NAME,
        11L,
        InvocationStatus.QUEUED,
        1,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        NOW,
        null,
        null);
  }

  private ToolInvocation running(ToolDescriptor descriptor, String workerToken) {
    return new ToolInvocation(
        INVOCATION_ID,
        7001L,
        8001L,
        0,
        "provider-call",
        descriptor,
        "{}",
        ToolExecutionLocation.ENVIRONMENT,
        ENVIRONMENT_NAME,
        11L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease(workerToken, NOW.plusSeconds(30)),
        NOW.plusSeconds(60),
        NOW,
        null,
        null,
        null,
        NOW.minusSeconds(1),
        NOW,
        null);
  }

  private String resultPayload(String text) {
    return resultCodec.encodeResult(
        new ToolResult(
            Long.toString(INVOCATION_ID), List.of(new TextToolContent(text)), false, "{}", false),
        ignored -> new byte[0]);
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private void assertCompletion(Fixture fixture, InvocationStatus status, String errorMessage) {
    assertEquals(1, fixture.observations.size());
    ToolCompleted completed = (ToolCompleted) fixture.observations.get(0);
    assertEquals(INVOCATION_ID, completed.invocationId());
    assertEquals(7001L, completed.threadId());
    assertEquals(status, completed.status());
    assertEquals(errorMessage, completed.error());
    assertEquals(NOW, completed.occurredAt());
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

  private String partial(long sequence, String payload) {
    return envelope(DaemonMessageType.PARTIAL, Long.toString(INVOCATION_ID), sequence, payload);
  }

  private String completed(long sequence, String payload) {
    return envelope(DaemonMessageType.COMPLETED, Long.toString(INVOCATION_ID), sequence, payload);
  }

  private String failed(long sequence, String message) {
    return envelope(
        DaemonMessageType.FAILED,
        Long.toString(INVOCATION_ID),
        sequence,
        "{\"message\":\"" + message + "\"}");
  }

  private String cancelled(long sequence, String reason) {
    return envelope(
        DaemonMessageType.CANCELLED,
        Long.toString(INVOCATION_ID),
        sequence,
        "{\"reason\":\"" + reason + "\"}");
  }

  private String envelope(
      DaemonMessageType messageType, String invocationId, long sequence, String payload) {
    return envelopeCodec.encode(
        new DaemonEnvelope(
            DaemonProtocol.VERSION_1,
            messageType,
            ENVIRONMENT_NAME,
            invocationId,
            sequence,
            payload));
  }

  private static List<DaemonMessageType> messageTypes(List<DaemonEnvelope> envelopes) {
    return envelopes.stream().map(DaemonEnvelope::messageType).toList();
  }

  private final class Fixture {
    private final RecordingTransactions transactions;
    private final MemoryArtifacts artifacts = new MemoryArtifacts();
    private final List<RealtimeEvent> realtimeEvents = new ArrayList<>();
    private final List<ExecutionTarget> activations = new ArrayList<>();
    private final List<HarnessLifecycleObservation> observations = new ArrayList<>();
    private final LiveEnvironmentRegistry environmentRegistry =
        new LiveEnvironmentRegistry(capabilitiesCodec);
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private final EnvironmentDaemonGateway gateway;
    private ToolDescriptor advertisedDescriptor;

    private Fixture(ToolDescriptor descriptor) {
      advertisedDescriptor = descriptor;
      transactions = new RecordingTransactions(queued(descriptor));
      HarnessRuntimeProperties runtimeProperties = new HarnessRuntimeProperties();
      runtimeProperties.setWorkerId("gateway-test");
      EnvironmentGatewayProperties gatewayProperties = new EnvironmentGatewayProperties();
      gatewayProperties.setDaemonToken(GATEWAY_TOKEN);
      gateway =
          new EnvironmentDaemonGateway(
              environmentRegistry,
              transactions,
              new ToolInterceptorChain(List.of(), List.of()),
              artifacts,
              new HarnessLifecycleObservers(List.of(observations::add)),
              realtimeEvents::add,
              activations::add,
              capabilitiesCodec,
              runtimeProperties,
              gatewayProperties,
              ToolWorkerConfig.DEFAULT,
              new Clock() {
                @Override
                public ZoneId getZone() {
                  return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                  return this;
                }

                @Override
                public Instant instant() {
                  return now.get();
                }
              });
    }

    private FakeConnection connectReady(String connectionId) {
      FakeConnection connection = new FakeConnection(connectionId);
      gateway.open(connection);
      gateway.receive(connection.connectionId(), hello(0));
      gateway.receive(connection.connectionId(), capabilities(1, capabilitiesJson()));
      gateway.receive(connection.connectionId(), ready(2));
      return connection;
    }

    private String capabilitiesJson() {
      return capabilitiesCodec.encode(
          new DaemonToolCapabilitiesCodec.DaemonToolCapabilities(
              List.of(advertisedDescriptor), List.of()));
    }
  }

  private final class RecordingTransactions implements ToolInvocationTransactions {
    private final ToolInvocation candidate;
    private ToolInvocation expiredRunning;
    private boolean recoveredLease;
    private boolean claimed;
    private boolean expiredClaimed;
    private int findNextCalls;
    private int claimCalls;
    private String workerToken;
    private int renewCalls;
    private ToolInvocationUpdateOutcome renewOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome activityOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome releaseOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private Runnable afterClaim = () -> {};
    private final List<Instant> activities = new ArrayList<>();
    private int releaseCalls;
    private InvocationStatus releasedStatus;
    private Instant releasedNextAttemptAt;
    private InvocationStatus terminalStatus;
    private ToolResult result;
    private ToolInvocationError error;

    private RecordingTransactions(ToolInvocation candidate) {
      this.candidate = candidate;
    }

    @Override
    public Optional<ToolInvocation> findClaimable(long invocationId, Instant now) {
      return Optional.empty();
    }

    @Override
    public Optional<ToolInvocation> findNextClaimable(
        ToolExecutionLocation location, String environmentName, Instant now) {
      findNextCalls++;
      if (claimed) {
        return Optional.empty();
      }
      return location == ToolExecutionLocation.ENVIRONMENT
              && ENVIRONMENT_NAME.equals(environmentName)
          ? Optional.of(candidate)
          : Optional.empty();
    }

    @Override
    public Optional<ToolInvocation> findNextExpiredRunning(
        ToolExecutionLocation location, Instant now) {
      return location == ToolExecutionLocation.ENVIRONMENT && !expiredClaimed
          ? Optional.ofNullable(expiredRunning)
          : Optional.empty();
    }

    @Override
    public Optional<ClaimedToolInvocation> claim(
        long invocationId,
        String workerToken,
        Duration executionTimeout,
        Duration workerLeaseDuration,
        Instant now) {
      claimCalls++;
      claimed = true;
      if (workerToken.endsWith("-environment-recovery")) {
        expiredClaimed = true;
      }
      this.workerToken = workerToken;
      assertEquals(INVOCATION_ID, invocationId);
      ToolDescriptor descriptor =
          expiredClaimed && expiredRunning != null
              ? expiredRunning.descriptor()
              : candidate.descriptor();
      assertEquals(descriptor.timeout(), executionTimeout);
      ClaimedToolInvocation ownership =
          new ClaimedToolInvocation(running(descriptor, workerToken), recoveredLease);
      afterClaim.run();
      return Optional.of(ownership);
    }

    @Override
    public ToolInvocationUpdateOutcome renew(
        ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now) {
      renewCalls++;
      return renewOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome recordActivity(
        ClaimedToolInvocation claimed, Instant activityAt, Instant now) {
      activities.add(activityAt);
      return activityOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome releaseUnstarted(
        ClaimedToolInvocation ownership,
        InvocationStatus previousStatus,
        Instant nextAttemptAt,
        Instant now) {
      releaseCalls++;
      releasedStatus = previousStatus;
      releasedNextAttemptAt = nextAttemptAt;
      this.claimed = false;
      return releaseOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeSuccess(
        ClaimedToolInvocation claimed,
        Supplier<ToolResult> resultSupplier,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalStatus = InvocationStatus.SUCCEEDED;
      this.result = resultSupplier.get();
      return ToolInvocationUpdateOutcome.APPLIED;
    }

    @Override
    public ToolInvocationUpdateOutcome completeFailure(
        ClaimedToolInvocation claimed,
        ToolInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalStatus = InvocationStatus.FAILED;
      this.error = error;
      return ToolInvocationUpdateOutcome.APPLIED;
    }

    @Override
    public ToolInvocationUpdateOutcome completeCancelled(
        ClaimedToolInvocation claimed, Instant lastObservedActivityAt, Instant now) {
      terminalStatus = InvocationStatus.CANCELLED;
      return ToolInvocationUpdateOutcome.APPLIED;
    }

    @Override
    public ToolInvocationUpdateOutcome completeUnknown(
        ClaimedToolInvocation claimed,
        ToolInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalStatus = InvocationStatus.UNKNOWN;
      this.error = error;
      return ToolInvocationUpdateOutcome.APPLIED;
    }

    @Override
    public ToolInvocationUpdateOutcome scheduleRetry(
        ClaimedToolInvocation claimed,
        Instant nextAttemptAt,
        Instant lastObservedActivityAt,
        Instant now) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
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

  private static final class MemoryArtifacts implements ArtifactStore {
    private final List<String> saved = new ArrayList<>();
    private RuntimeException failure;

    @Override
    public ArtifactRef save(String mediaType, String encoding, byte[] content) {
      if (failure != null) {
        throw failure;
      }
      saved.add(mediaType + ":" + encoding + ":" + content.length);
      return new ArtifactRef("global-artifact", mediaType, content.length);
    }

    @Override
    public Optional<Artifact> find(String artifactId) {
      return Optional.empty();
    }
  }
}
