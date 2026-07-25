package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.kernel.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.kernel.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallInterceptor;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class ToolWorkerFinalTest {
  private static RemoteToolTransport noopTransport() {
    return (environmentName, request, listener) -> {
      throw new RemoteToolUnavailableException("no remote transport in platform fixture");
    };
  }

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void shutdownSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  @Test
  void validatesPositiveInvocationId() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    assertThrows(IllegalArgumentException.class, () -> fixture.worker.dispatch(0));
  }

  @Test
  void environmentUnavailableBeforeSendReleasesClaimWithoutFailure() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), "env-a");
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new RemoteToolUnavailableException(environmentName + " offline");
        });

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(1, fixture.transactions.releaseUnstartedCalls);
    assertNull(fixture.transactions.terminalStatus);
    assertEquals(0, fixture.tool.executions);
  }

  @Test
  void environmentUncertainSendCompletesUnknownImmediately() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.transactions.candidate = queued(environmentDescriptor(), "env-a");
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new RemoteToolSendUncertainException("maybe delivered");
        });

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(1, fixture.transactions.claimCalls);
    assertEquals(0, fixture.transactions.releaseUnstartedCalls);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("REMOTE_UNCERTAIN", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution("env-a"));
  }

  @Test
  void asyncUncertainDisconnectCompletesUnknownWithoutRetryEvenWhenIdempotent() {
    Fixture fixture = fixture(ToolSideEffect.IDEMPOTENT);
    fixture.transactions.candidate = queued(environmentDescriptor(), "env-a");
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.retryPolicy =
        new InvocationRetryPolicy(
            3, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    AtomicReference<ToolExecutionListener> remoteListener = new AtomicReference<>();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          remoteListener.set(listener);
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    remoteListener
        .get()
        .onError(new RemoteToolSendUncertainException("connection lost mid-flight"));

    assertEquals(0, fixture.transactions.retryCalls);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("REMOTE_UNCERTAIN", fixture.transactions.error.kind());
    assertFalse(fixture.worker.hasActiveExecution("env-a"));
  }

  @Test
  void environmentSlotReleasedWhenClaimedExecutionThrowsUnexpectedly() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), "env-a");
    fixture.transactions.descriptor = environmentDescriptor();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          throw new IllegalStateException("transport exploded");
        });

    assertTrue(fixture.worker.dispatch(1));
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution("env-a"));
  }

  @Test
  void environmentRemoteCompleteExternalizesInlineBinaryArtifactLazily() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), "env-a");
    fixture.transactions.descriptor = environmentDescriptor();
    AtomicReference<ToolExecutionListener> remoteListener = new AtomicReference<>();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          remoteListener.set(listener);
          return new ToolExecutionHandle() {
            @Override
            public void cancel() {}

            @Override
            public boolean isCancelled() {
              return false;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    remoteListener
        .get()
        .onComplete(
            new ToolResult(
                "call-1",
                List.of(new BinaryToolContent("text/plain", new byte[] {9, 9})),
                false,
                "{}",
                false));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertTrue(fixture.transactions.result.contents().get(0) instanceof ArtifactToolContent);
    assertArrayEquals(new byte[] {9, 9}, fixture.artifacts.content);
  }

  @Test
  void defaultConstructorRunsWithoutLifecycleObservers() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
    schedulers.add(scheduler);
    ToolWorker worker =
        new ToolWorker(
            fixture.transactions,
            (name, version) -> fixture.registryTool.map(value -> (Tool) value),
            noopTransport(),
            fixture.interceptorChain,
            fixture.artifacts,
            () -> fixture.retryPolicy,
            fixture.realtimeEvents::add,
            fixture.activations::add,
            ToolWorkerConfig.DEFAULT,
            Clock.fixed(NOW, ZoneOffset.UTC),
            scheduler,
            () -> "worker-token");

    assertTrue(worker.dispatch(1));
    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertFalse(worker.hasActiveExecution());
  }

  @Test
  void dispatchesFrozenPlatformToolAndCommitsSuccessBeforeThreadActivation() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.worker.hasActiveExecution());
    assertEquals(1L, fixture.tool.request.context().invocationId());
    assertEquals(2L, fixture.tool.request.context().threadId());
    assertEquals("{}", fixture.tool.request.call().argumentsJson());

    fixture.tool.listener.onComplete(result("done"));

    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertEquals("done", text(fixture.transactions.result));
    assertEquals(List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 2L)), fixture.activations);
    assertCompletion(fixture, InvocationStatus.SUCCEEDED, null);
    assertFalse(fixture.tool.handle.cancelled);
  }

  @Test
  void publishesPartialAsLossyRealtimeProjectionAndRecordsDurableActivity() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            1,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(result("partial"));

    assertEquals(1, fixture.realtimeEvents.size());
    RealtimeEvent.ToolPartial partial = (RealtimeEvent.ToolPartial) fixture.realtimeEvents.get(0);
    assertEquals(2L, partial.threadId());
    assertEquals(1L, partial.toolInvocationId());
    assertEquals(1, partial.attempt());
    assertEquals("partial", text(partial.partial()));
    assertEquals(List.of(NOW), fixture.transactions.activities);

    fixture.tool.listener.onComplete(result("done"));
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
  }

  @Test
  void lostPartialFenceSuppressesRealtimeAndNeverPersistsPartialArtifacts() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.activityOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            1,
            1,
            1));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(result("partial-large-output"));

    assertTrue(fixture.realtimeEvents.isEmpty());
    assertNull(fixture.artifacts.content);
    assertTrue(fixture.tool.handle.cancelled);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void isolatesRealtimeAndActivationProjectionFailuresFromDurableSuccess() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.realtimeFailure = true;
    fixture.activationFailure = true;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            1,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onPartial(result("partial"));
    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.terminalStatus);
    assertCompletion(fixture, InvocationStatus.SUCCEEDED, null);
  }

  @Test
  void recoveredLeaseBecomesUnknownWithoutReplayingAnyToolSideEffect() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.transactions.recoveredLease = true;

    assertTrue(fixture.worker.dispatch(1));

    assertEquals(0, fixture.tool.executions);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LEASE_EXPIRED", fixture.transactions.error.kind());
    assertEquals(
        "Tool ownership was lost; execution result is unknown.",
        fixture.transactions.error.message());
    assertEquals(List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 2L)), fixture.activations);
    assertCompletion(
        fixture, InvocationStatus.UNKNOWN, "Tool ownership was lost; execution result is unknown.");
  }

  @Test
  void missingOrDriftedFrozenDescriptorFailsWithoutExecution() {
    Fixture missing = fixture(ToolSideEffect.READ_ONLY);
    missing.registryTool = Optional.empty();
    missing.rebuildWorker();
    assertTrue(missing.worker.dispatch(1));
    assertEquals(InvocationStatus.FAILED, missing.transactions.terminalStatus);
    assertEquals(0, missing.tool.executions);

    Fixture drifted = fixture(ToolSideEffect.READ_ONLY);
    drifted.registryTool = Optional.of(new RecordingTool(descriptor(ToolSideEffect.IDEMPOTENT)));
    drifted.rebuildWorker();
    assertTrue(drifted.worker.dispatch(1));
    assertEquals(InvocationStatus.FAILED, drifted.transactions.terminalStatus);
    assertEquals(0, drifted.registryTool.orElseThrow().executions);
  }

  @Test
  void idempotentFailureSchedulesRetryAndSignalsInvocationInsteadOfThread() {
    Fixture fixture = fixture(ToolSideEffect.IDEMPOTENT);
    fixture.retryPolicy =
        new InvocationRetryPolicy(
            1, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(2), Duration.ofSeconds(2));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onError(new IllegalStateException("temporary"));

    assertEquals(1, fixture.transactions.retryCalls);
    assertEquals(NOW.plusSeconds(2), fixture.transactions.nextAttemptAt);
    assertNull(fixture.transactions.terminalStatus);
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, 1L)), fixture.activations);
    assertTrue(fixture.observations.isEmpty());
    assertTrue(fixture.tool.handle.cancelled);
  }

  @Test
  void nonIdempotentFailureNeverRetriesAndWakesOwningThread() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    fixture.retryPolicy =
        new InvocationRetryPolicy(
            3, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onError(new IllegalStateException("side effect uncertain"));

    assertEquals(0, fixture.transactions.retryCalls);
    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("side effect uncertain", fixture.transactions.error.message());
    assertEquals(List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 2L)), fixture.activations);
  }

  @Test
  void lostTerminalOwnershipCancelsLocalHandleAndPublishesNothing() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.terminalOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("late"));

    assertTrue(fixture.tool.handle.cancelled);
    assertTrue(fixture.activations.isEmpty());
    assertTrue(fixture.observations.isEmpty());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void lostTerminalOwnershipDoesNotRunAfterInterceptorOrPersistArtifacts() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    int[] afterCalls = {0};
    fixture.interceptorChain =
        new ToolInterceptorChain(
            List.of(),
            List.of(
                context -> {
                  afterCalls[0]++;
                  return context.result();
                }));
    fixture.transactions.terminalOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            1,
            1));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("large-output"));

    assertEquals(0, afterCalls[0]);
    assertNull(fixture.artifacts.content);
    assertTrue(fixture.tool.handle.cancelled);
  }

  @Test
  void afterInterceptorFailureIsPersistedAsFailure() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    AfterToolCallInterceptor failing =
        context -> {
          throw new IllegalStateException("after failed");
        };
    fixture.interceptorChain = new ToolInterceptorChain(List.of(), List.of(failing));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("done"));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("AFTER_INTERCEPTOR_FAILED", fixture.transactions.error.kind());
    assertTrue(fixture.transactions.error.message().contains("after failed"));
    assertFalse(fixture.tool.handle.cancelled);
  }

  @Test
  void artifactPersistenceFailureBecomesDeterministicFailure() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.artifacts.failure = new IllegalStateException("artifact database unavailable");
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            1,
            1));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("large-output"));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.terminalStatus);
    assertEquals("RESULT_PERSISTENCE_FAILED", fixture.transactions.error.kind());
    assertEquals("artifact database unavailable", fixture.transactions.error.message());
  }

  @Test
  void externalizesLargeOutputWithUtf8SafePreview() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            1,
            5));

    assertTrue(fixture.worker.dispatch(1));
    fixture.tool.listener.onComplete(result("😀😀"));

    assertEquals("😀\n[full output stored as artifact]", text(fixture.transactions.result));
    assertTrue(fixture.transactions.result.contents().get(1) instanceof ArtifactToolContent);
    assertEquals("😀😀", new String(fixture.artifacts.content, StandardCharsets.UTF_8));
  }

  @Test
  void heartbeatOwnershipLossCancelsLocalHandleWithoutForgingTerminalState() throws Exception {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.renewOutcome = ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        new ToolWorkerConfig(
            Duration.ofSeconds(1),
            Duration.ofMillis(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(1),
            8192,
            8192,
            1024));

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.transactions.renewed.await(1, TimeUnit.SECONDS));
    assertTrue(fixture.tool.handle.cancelledLatch.await(1, TimeUnit.SECONDS));
    assertNull(fixture.transactions.terminalStatus);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (fixture.worker.hasActiveExecution() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void dispatchNextUsesRecoveryQueryAndStopOnlyAbandonsProcessLocalHandle() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);

    assertTrue(fixture.worker.dispatchNext());
    assertEquals(ToolExecutionLocation.PLATFORM, fixture.transactions.queriedLocation);
    assertNull(fixture.transactions.queriedEnvironmentName);
    assertFalse(fixture.worker.dispatchNext());
    assertEquals(1, fixture.transactions.findNextCalls);
    fixture.worker.stop();

    assertTrue(fixture.tool.handle.cancelled);
    assertNull(fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  @Test
  void recoverNextExpiredFencesStalePlatformHandleAndIgnoresLateComplete() {
    Fixture fixture = fixture(ToolSideEffect.NON_IDEMPOTENT);
    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.worker.hasActiveExecution());

    fixture.transactions.recoveredLease = true;
    fixture.transactions.expiredCandidate =
        running(descriptor(ToolSideEffect.NON_IDEMPOTENT), "old");
    assertTrue(fixture.worker.recoverNextExpired(ToolExecutionLocation.PLATFORM));

    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LEASE_EXPIRED", fixture.transactions.error.kind());
    assertTrue(fixture.tool.handle.cancelled);
    assertFalse(fixture.worker.hasActiveExecution());

    fixture.transactions.terminalStatus = null;
    fixture.tool.listener.onComplete(result("late"));
    assertNull(fixture.transactions.terminalStatus);
  }

  @Test
  void recoverNextExpiredFencesStaleEnvironmentHandleAndClearsSlot() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    fixture.transactions.candidate = queued(environmentDescriptor(), "env-a");
    fixture.transactions.descriptor = environmentDescriptor();
    AtomicReference<ToolExecutionListener> remoteListener = new AtomicReference<>();
    fixture.rebuildWorkerWithTransport(
        (environmentName, request, listener) -> {
          remoteListener.set(listener);
          return new ToolExecutionHandle() {
            private boolean cancelled;

            @Override
            public void cancel() {
              cancelled = true;
            }

            @Override
            public boolean isCancelled() {
              return cancelled;
            }
          };
        });

    assertTrue(fixture.worker.dispatch(1));
    assertTrue(fixture.worker.hasActiveExecution("env-a"));

    fixture.transactions.recoveredLease = true;
    fixture.transactions.expiredCandidate = running(environmentDescriptor(), "old");
    assertTrue(fixture.worker.recoverNextExpired(ToolExecutionLocation.ENVIRONMENT));

    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertFalse(fixture.worker.hasActiveExecution("env-a"));
    remoteListener.get().onComplete(result("late-remote"));
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
  }

  @Test
  void localConflictAbandonsStaleHandleAndUnknownsNewClaimWithoutRerunningTool() {
    Fixture fixture = fixture(ToolSideEffect.READ_ONLY);
    assertTrue(fixture.worker.dispatch(1));
    int executionsAfterFirst = fixture.tool.executions;

    // Simulate reclaim of the same invocation while the stale local handle is still mapped.
    fixture.transactions.recoveredLease = false;
    fixture.transactions.forceLocalConflict = true;
    assertTrue(fixture.worker.dispatch(1));

    assertEquals(executionsAfterFirst, fixture.tool.executions);
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.terminalStatus);
    assertEquals("LOCAL_CONFLICT", fixture.transactions.error.kind());
    assertTrue(fixture.tool.handle.cancelled);
    assertFalse(fixture.worker.hasActiveExecution());
  }

  private Fixture fixture(ToolSideEffect sideEffect) {
    Fixture fixture = new Fixture(sideEffect);
    fixture.rebuildWorker();
    return fixture;
  }

  private static ToolResult result(String text) {
    return new ToolResult("call-1", List.of(new TextToolContent(text)), false, "{}", false);
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static ToolDescriptor descriptor(ToolSideEffect sideEffect) {
    return new ToolDescriptor(
        "tool",
        "1",
        "test tool",
        null,
        new ToolParamsSchema("input", Map.of(), Set.of(), false),
        sideEffect,
        Duration.ofSeconds(30));
  }

  private static ToolDescriptor environmentDescriptor() {
    return new ToolDescriptor(
        "environmentTool",
        "1",
        "environment tool",
        null,
        new ToolParamsSchema("input", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static ToolInvocation queued(ToolDescriptor descriptor, String environmentName) {
    return new ToolInvocation(
        1L,
        2L,
        3L,
        0,
        "call-1",
        descriptor,
        "{}",
        environmentName == null
            ? ToolExecutionLocation.PLATFORM
            : ToolExecutionLocation.ENVIRONMENT,
        environmentName,
        7L,
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

  private static ToolInvocation running(ToolDescriptor descriptor, String token) {
    String environmentName = descriptor.name().startsWith("environment") ? "env-a" : null;
    ToolExecutionLocation location =
        environmentName == null
            ? ToolExecutionLocation.PLATFORM
            : ToolExecutionLocation.ENVIRONMENT;
    return new ToolInvocation(
        1L,
        2L,
        3L,
        0,
        "call-1",
        descriptor,
        "{}",
        location,
        environmentName,
        7L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease(token, NOW.plusSeconds(10)),
        NOW.plusSeconds(30),
        NOW,
        null,
        null,
        null,
        NOW.minusSeconds(1),
        NOW,
        null);
  }

  private static void assertCompletion(
      Fixture fixture, InvocationStatus status, String errorMessage) {
    assertEquals(1, fixture.observations.size());
    ToolCompleted completed = (ToolCompleted) fixture.observations.get(0);
    assertEquals(1L, completed.invocationId());
    assertEquals(2L, completed.threadId());
    assertEquals(status, completed.status());
    assertEquals(errorMessage, completed.error());
    assertEquals(NOW, completed.occurredAt());
  }

  private final class Fixture {
    private final RecordingTransactions transactions;
    private final RecordingTool tool;
    private final MemoryArtifacts artifacts = new MemoryArtifacts();
    private final List<RealtimeEvent> realtimeEvents = new ArrayList<>();
    private final List<ExecutionTarget> activations = new ArrayList<>();
    private final List<HarnessLifecycleObservation> observations = new ArrayList<>();

    private ToolInterceptorChain interceptorChain = new ToolInterceptorChain(List.of(), List.of());
    private Optional<RecordingTool> registryTool;
    private InvocationRetryPolicy retryPolicy =
        new InvocationRetryPolicy(
            0, InvocationRetryBackoffStrategy.FIXED, Duration.ofSeconds(1), Duration.ofSeconds(1));
    private boolean realtimeFailure;
    private boolean activationFailure;
    private ToolWorker worker;
    private RemoteToolTransport transport = noopTransport();

    private Fixture(ToolSideEffect sideEffect) {
      ToolDescriptor descriptor = descriptor(sideEffect);
      transactions = new RecordingTransactions(queued(descriptor, null), descriptor);
      tool = new RecordingTool(descriptor);
      registryTool = Optional.of(tool);
    }

    private void rebuildWorker() {
      rebuildWorker(ToolWorkerConfig.DEFAULT);
    }

    private void rebuildWorkerWithTransport(RemoteToolTransport transport) {
      this.transport = transport;
      rebuildWorker(ToolWorkerConfig.DEFAULT);
    }

    private void rebuildWorker(ToolWorkerConfig config) {
      ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);
      schedulers.add(scheduler);
      worker =
          new ToolWorker(
              transactions,
              (name, version) -> registryTool.map(value -> (Tool) value),
              transport,
              interceptorChain,
              artifacts,
              () -> retryPolicy,
              event -> {
                if (realtimeFailure) {
                  throw new IllegalStateException("redis unavailable");
                }
                realtimeEvents.add(event);
              },
              target -> {
                if (activationFailure) {
                  throw new IllegalStateException("signal unavailable");
                }
                activations.add(target);
              },
              config,
              Clock.fixed(NOW, ZoneOffset.UTC),
              scheduler,
              new HarnessLifecycleObservers(List.of(observations::add)),
              () -> "worker-token");
    }
  }

  private static final class RecordingTransactions implements ToolInvocationTransactions {
    private ToolInvocation candidate;
    private ToolInvocation expiredCandidate;
    private ToolDescriptor descriptor;
    private boolean recoveredLease;
    private boolean forceLocalConflict;
    private int claimCalls;
    private int findNextCalls;
    private ToolExecutionLocation queriedLocation;
    private String queriedEnvironmentName;
    private ToolInvocationUpdateOutcome renewOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome activityOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome terminalOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private ToolInvocationUpdateOutcome retryOutcome = ToolInvocationUpdateOutcome.APPLIED;
    private final CountDownLatch renewed = new CountDownLatch(1);
    private final List<Instant> activities = new ArrayList<>();
    private int retryCalls;
    private int releaseUnstartedCalls;
    private Instant nextAttemptAt;
    private InvocationStatus terminalStatus;
    private ToolResult result;
    private ToolInvocationError error;

    private RecordingTransactions(ToolInvocation candidate, ToolDescriptor descriptor) {
      this.candidate = candidate;
      this.descriptor = descriptor;
    }

    @Override
    public Optional<ToolInvocation> findClaimable(long invocationId, Instant now) {
      return candidate.id() == invocationId ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public Optional<ToolInvocation> findNextClaimable(
        ToolExecutionLocation location, String environmentName, Instant now) {
      findNextCalls++;
      queriedLocation = location;
      queriedEnvironmentName = environmentName;
      return candidate.location() == location
              && Objects.equals(candidate.environmentName(), environmentName)
          ? Optional.of(candidate)
          : Optional.empty();
    }

    @Override
    public Optional<ToolInvocation> findNextExpiredRunning(
        ToolExecutionLocation location, Instant now) {
      if (expiredCandidate != null && expiredCandidate.location() == location) {
        return Optional.of(expiredCandidate);
      }
      return Optional.empty();
    }

    @Override
    public Optional<ClaimedToolInvocation> claim(
        long invocationId,
        String workerToken,
        Duration executionTimeout,
        Duration workerLeaseDuration,
        Instant now) {
      claimCalls++;
      assertEquals(1L, invocationId);
      assertEquals("worker-token", workerToken);
      assertEquals(descriptor.timeout(), executionTimeout);
      assertTrue(workerLeaseDuration.isPositive());
      boolean recovered = recoveredLease || forceLocalConflict;
      // forceLocalConflict still claims with recovered=false so dispatchClaimed hits putIfAbsent.
      if (forceLocalConflict) {
        recovered = false;
      }
      return Optional.of(new ClaimedToolInvocation(running(descriptor, workerToken), recovered));
    }

    @Override
    public ToolInvocationUpdateOutcome renew(
        ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now) {
      renewed.countDown();
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
        ClaimedToolInvocation claimed,
        InvocationStatus previousStatus,
        Instant nextAttemptAt,
        Instant now) {
      releaseUnstartedCalls++;
      return ToolInvocationUpdateOutcome.APPLIED;
    }

    @Override
    public ToolInvocationUpdateOutcome completeSuccess(
        ClaimedToolInvocation claimed,
        Supplier<ToolResult> resultSupplier,
        Instant lastObservedActivityAt,
        Instant now) {
      if (terminalOutcome != ToolInvocationUpdateOutcome.APPLIED) {
        return terminalOutcome;
      }
      terminalStatus = InvocationStatus.SUCCEEDED;
      this.result = resultSupplier.get();
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeFailure(
        ClaimedToolInvocation claimed,
        ToolInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalStatus = InvocationStatus.FAILED;
      this.error = error;
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeCancelled(
        ClaimedToolInvocation claimed, Instant lastObservedActivityAt, Instant now) {
      terminalStatus = InvocationStatus.CANCELLED;
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome completeUnknown(
        ClaimedToolInvocation claimed,
        ToolInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      terminalStatus = InvocationStatus.UNKNOWN;
      this.error = error;
      return terminalOutcome;
    }

    @Override
    public ToolInvocationUpdateOutcome scheduleRetry(
        ClaimedToolInvocation claimed,
        Instant nextAttemptAt,
        Instant lastObservedActivityAt,
        Instant now) {
      retryCalls++;
      this.nextAttemptAt = nextAttemptAt;
      return retryOutcome;
    }
  }

  private static final class RecordingTool implements Tool {
    private final ToolDescriptor descriptor;
    private final Handle handle = new Handle();
    private ToolExecutionRequest request;
    private ToolExecutionListener listener;
    private int executions;

    private RecordingTool(ToolDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      executions++;
      this.request = request;
      this.listener = listener;
      return handle;
    }
  }

  private static final class Handle implements ToolExecutionHandle {
    private final CountDownLatch cancelledLatch = new CountDownLatch(1);
    private volatile boolean cancelled;

    @Override
    public void cancel() {
      cancelled = true;
      cancelledLatch.countDown();
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }
  }

  private static final class MemoryArtifacts implements ArtifactStore {
    private byte[] content;
    private RuntimeException failure;

    @Override
    public ArtifactRef save(String mediaType, String encoding, byte[] content) {
      if (failure != null) {
        throw failure;
      }
      this.content = content.clone();
      return new ArtifactRef("artifact-1", mediaType, content.length);
    }

    @Override
    public Optional<Artifact> find(String artifactId) {
      return Optional.empty();
    }
  }
}
