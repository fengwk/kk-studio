package fun.fengwk.kkstudio.harness.runtime.model.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ModelWorkerTest {
  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(ScheduledExecutorService::shutdownNow);
  }

  /**
   * A claim returns immediately, while callbacks persist only the Invocation and wake its Thread.
   */
  @Test
  void dispatchesAsyncProviderResponseWithoutWritingThreadStateDirectly() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    assertEquals(1, fixture.executor.executeCalls);
    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertEquals(fixture.transactions.current.deadlineAt(), fixture.executor.request.deadlineAt());
    assertEquals(1, fixture.executor.request.attempt());
    assertEquals("1:1", fixture.executor.request.idempotencyKey());

    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("ans"));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertEquals("answer", fixture.transactions.current.result().text());
    assertEquals(
        List.of(
            new ExecutionTarget(
                ExecutionTargetKind.THREAD, fixture.transactions.current.threadId())),
        fixture.notifier.targets);
    assertEquals(
        List.of(new ProviderStreamEvent.TextDelta("ans"), new ProviderStreamEvent.TextDelta("wer")),
        modelDeltas(fixture.sink.events));
    assertEquals(List.of(1, 1), modelDeltaAttempts(fixture.sink.events));
    assertFalse(fixture.executor.handle.isCancelled());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /**
   * A transient Provider error only schedules the durable Invocation; it does not wake the Thread.
   */
  @Test
  void schedulesTransientRetryAndEmitsOnlyDelayedInvocationSignal() throws Exception {
    Fixture fixture = fixture();
    fixture.retryPolicy = retryPolicy(1, Duration.ofMillis(20));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.TRANSIENT, "unavailable"));

    assertEquals(InvocationStatus.RETRY_WAIT, fixture.transactions.current.status());
    assertEquals(1, fixture.transactions.scheduleRetryCalls);
    assertTrue(fixture.executor.handle.isCancelled());
    await(fixture.notifier.notified);
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 1L)),
        fixture.notifier.targets);
  }

  /** Realtime consumers can reject partial output left by an earlier failed attempt. */
  @Test
  void tagsRealtimeDeltasWithTheirProviderAttempt() {
    MutableClock clock = new MutableClock(NOW);
    Fixture fixture = fixture(clock);
    fixture.retryPolicy = retryPolicy(1, Duration.ofMillis(1));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("old"));
    fixture.executor.listener.onError(new ProviderException(ProviderErrorKind.TRANSIENT, "retry"));

    clock.set(NOW.plusMillis(1));
    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("new"));

    assertEquals(List.of(1, 2), modelDeltaAttempts(fixture.sink.events));
    assertEquals(2, fixture.executor.request.attempt());
    assertEquals("1:2", fixture.executor.request.idempotencyKey());
    fixture.worker.stop();
  }

  /** A retry that cannot finish before the original total deadline becomes a final failure. */
  @Test
  void doesNotResetTotalDeadlineForRetry() {
    Fixture fixture = fixture();
    fixture.resource = resource(Duration.ofMillis(50), Duration.ofSeconds(1));
    fixture.retryPolicy = retryPolicy(1, Duration.ofSeconds(1));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.TRANSIENT, "unavailable"));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(0, fixture.transactions.scheduleRetryCalls);
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 2L)), fixture.notifier.targets);
  }

  /**
   * An expired RUNNING lease is terminalized as UNKNOWN instead of replaying external Provider I/O.
   */
  @Test
  void marksRecoveredRunningInvocationUnknownWithoutProviderExecution() {
    Fixture fixture = fixture();
    fixture.transactions.current = fixture.transactions.expiredRunning();
    fixture.transactions.recoverRunningLease = true;
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.current.status());
    assertEquals(1, fixture.transactions.completeUnknownCalls);
    assertEquals(0, fixture.executor.executeCalls);
    assertEquals(0, fixture.resolverCalls);
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 2L)), fixture.notifier.targets);
  }

  /** Realtime projection failure is lossy by design and cannot prevent durable success. */
  @Test
  void isolatesRealtimeSinkFailureFromDurableSuccess() {
    Fixture fixture = fixture();
    fixture.sink.failure = new IllegalStateException("redis unavailable");

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("answer"));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertEquals(
        List.of(new ExecutionTarget(ExecutionTargetKind.THREAD, 2L)), fixture.notifier.targets);
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** A post-commit activation transport failure cannot roll back a completed Invocation. */
  @Test
  void isolatesActivationNotifierFailureFromDurableSuccess() {
    Fixture fixture = fixture();
    fixture.notifier.failure = new IllegalStateException("pubsub unavailable");

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertTrue(fixture.notifier.targets.isEmpty());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /**
   * Lost terminal ownership cancels the local handle and never emits a wake for a stale callback.
   */
  @Test
  void stopsLocalHandleWhenTerminalCallbackLosesOwnership() {
    Fixture fixture = fixture();
    fixture.transactions.terminalOutcome = ModelInvocationUpdateOutcome.LOST_OWNERSHIP;

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));
    fixture.executor.listener.onError(new ProviderException(ProviderErrorKind.TRANSIENT, "late"));

    assertEquals(1, fixture.transactions.terminalCalls);
    assertTrue(fixture.executor.handle.isCancelled());
    assertTrue(fixture.notifier.targets.isEmpty());
    assertTrue(fixture.sink.events.isEmpty());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /**
   * Local shutdown only releases the process handle; durable lease recovery remains authoritative.
   */
  @Test
  void stopCancelsOnlyLocalExecutionHandle() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.worker.stop();

    assertTrue(fixture.executor.handle.isCancelled());
    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(0, fixture.transactions.terminalCalls);
  }

  /** A broken best-effort transport cancel cannot retain the local ownership slot. */
  @Test
  void isolatesLocalHandleCancellationFailure() {
    Fixture fixture = fixture();
    fixture.executor.handle.cancelFailure = new IllegalStateException("transport unavailable");

    assertTrue(fixture.worker.dispatch(1L));
    fixture.worker.stop();

    assertTrue(fixture.executor.handle.isCancelled());
    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /** A lost heartbeat fence stops the handle without fabricating a terminal durable result. */
  @Test
  void stopsLocalHandleWhenLeaseHeartbeatLosesOwnership() throws Exception {
    Fixture fixture = fixture();
    fixture.transactions.renewOutcome = ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(5), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    await(fixture.transactions.renewed);
    await(fixture.executor.handle.cancelledLatch);
    awaitInactive(fixture.worker);

    assertTrue(fixture.executor.handle.isCancelled());
    assertFalse(fixture.worker.hasActiveExecution());
    assertEquals(0, fixture.transactions.terminalCalls);
  }

  /**
   * Idle watchdog terminalizes an unresponsive attempt and cancels its best-effort local handle.
   */
  @Test
  void terminalizesIdleTimeoutWithoutWaitingForProviderCallback() throws Exception {
    Fixture fixture = fixture(Clock.systemUTC());
    fixture.retryPolicy = retryPolicy(0, Duration.ofMillis(1));
    fixture.resource = resource(Duration.ofSeconds(1), Duration.ofMillis(25));
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    await(fixture.transactions.terminalized);
    await(fixture.executor.handle.cancelledLatch);

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("idle"));
    assertTrue(fixture.executor.handle.isCancelled());
  }

  /** Total watchdog cannot be extended by retry policy or by an absent Provider callback. */
  @Test
  void terminalizesTotalDeadlineWithoutWaitingForProviderCallback() throws Exception {
    Fixture fixture = fixture(Clock.systemUTC());
    fixture.retryPolicy = retryPolicy(0, Duration.ofMillis(1));
    fixture.resource = resource(Duration.ofMillis(25), Duration.ofSeconds(1));
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    await(fixture.transactions.terminalized);
    // finishLocal cancel is best-effort and may complete just after the terminal CAS latch.
    await(fixture.executor.handle.cancelledLatch);

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("deadline"));
    assertTrue(fixture.executor.handle.isCancelled());
  }

  /** Provider delta is the only ordinary callback that persists progress activity. */
  @Test
  void recordsRealProviderDeltaActivityWithoutUsingHeartbeatAsProgress() throws Exception {
    Fixture fixture = fixture();
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("answer"));
    await(fixture.transactions.activityRecorded);
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(1, fixture.transactions.recordActivityCalls);
    assertEquals(0, fixture.transactions.renewCalls);
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
  }

  /**
   * Terminal CAS captures a pending real delta but does not count completion itself as progress.
   */
  @Test
  void carriesPendingDeltaActivityIntoTerminalMutation() {
    MutableClock clock = new MutableClock(NOW);
    Fixture fixture = fixture(clock);
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(10)));

    assertTrue(fixture.worker.dispatch(1L));
    Instant deltaAt = NOW.plusSeconds(1);
    clock.set(deltaAt);
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("answer"));
    clock.set(NOW.plusSeconds(2));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(0, fixture.transactions.recordActivityCalls);
    assertEquals(deltaAt, fixture.transactions.current.lastActivityAt());
  }

  /**
   * Setup resolution failure happens after fencing the Invocation and before any Provider side
   * effect.
   */
  @Test
  void terminalizesExecutionResourceResolutionFailureAfterClaim() {
    Fixture fixture = fixture();
    fixture.resolutionFailure = new IllegalStateException("credential reference unavailable");
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.transactions.current.error().kind());
    assertEquals(0, fixture.executor.executeCalls);
    assertEquals(1, fixture.transactions.completeFailureCalls);
  }

  /**
   * Invalid final data cannot overwrite a valid delta prefix with a fabricated successful response.
   */
  @Test
  void convertsConflictingFinalStreamResponseIntoDurableFailure() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("different"));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.transactions.current.error().kind());
    assertTrue(fixture.executor.handle.isCancelled());
  }

  /** Provider cancellation is a terminal control outcome, not a retryable Provider failure. */
  @Test
  void terminalizesCancelledProviderResponseWithoutRetry() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(response("", ProviderStopReason.CANCELLED));

    assertEquals(InvocationStatus.CANCELLED, fixture.transactions.current.status());
    assertEquals(0, fixture.transactions.scheduleRetryCalls);
    assertTrue(fixture.executor.handle.isCancelled());
  }

  /**
   * A synchronous callback before execute returns its handle must not cancel a successful handle.
   */
  @Test
  void preservesSynchronouslyCompletedHandle() {
    Fixture fixture = fixture();
    fixture.executor.synchronousResponse = response("answer", ProviderStopReason.COMPLETED);

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertFalse(fixture.executor.handle.isCancelled());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /** Concurrent terminal callbacks share one local gate before their durable fencing CAS. */
  @Test
  void acceptsOnlyOneTerminalCallbackDuringConcurrentCompletionAndFailure() throws Exception {
    Fixture fixture = fixture();
    fixture.transactions.blockTerminal = true;

    assertTrue(fixture.worker.dispatch(1L));
    Thread completion =
        new Thread(
            () ->
                fixture.executor.listener.onComplete(
                    response("answer", ProviderStopReason.COMPLETED)));
    completion.start();
    await(fixture.transactions.terminalEntered);

    Thread lateFailure =
        new Thread(
            () ->
                fixture.executor.listener.onError(
                    new ProviderException(ProviderErrorKind.TRANSIENT, "late")));
    lateFailure.start();
    lateFailure.join(1000);
    fixture.transactions.terminalRelease.countDown();
    completion.join(1000);

    assertFalse(completion.isAlive());
    assertFalse(lateFailure.isAlive());
    assertEquals(1, fixture.transactions.terminalCalls);
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
  }

  /** A terminal CAS keeps renewing the valid lease until its durable result is known. */
  @Test
  void keepsHeartbeatAliveWhileTerminalPersistenceIsInFlight() throws Exception {
    Fixture fixture = fixture();
    fixture.transactions.blockTerminal = true;
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    Thread completion =
        new Thread(
            () ->
                fixture.executor.listener.onComplete(
                    response("answer", ProviderStopReason.COMPLETED)));
    completion.start();
    await(fixture.transactions.terminalEntered);
    await(fixture.transactions.renewedAfterTerminalStarted);
    fixture.transactions.terminalRelease.countDown();
    completion.join(1000);

    assertFalse(completion.isAlive());
    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
  }

  /**
   * Invalid dispatch identifiers are rejected before the worker asks the durable transaction port.
   */
  @Test
  void rejectsInvalidDispatchIdentifier() {
    Fixture fixture = fixture();

    assertThrows(IllegalArgumentException.class, () -> fixture.worker.dispatch(0L));
    assertEquals(0, fixture.transactions.findClaimableCalls);
  }

  /** Recovery dispatch uses the same fencing and asynchronous execution path as a direct signal. */
  @Test
  void dispatchesOneDueInvocationForRecoveryPolling() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatchNext());

    assertEquals(1, fixture.executor.executeCalls);
    fixture.worker.stop();
  }

  /** A no-work read and a lost claim are ordinary no-op outcomes rather than worker failures. */
  @Test
  void returnsFalseWhenNoCandidateOrClaimIsAvailable() {
    Fixture fixture = fixture();
    fixture.transactions.candidateAvailable = false;

    assertFalse(fixture.worker.dispatch(1L));
    assertFalse(fixture.worker.dispatchNext());

    fixture.transactions.candidateAvailable = true;
    fixture.transactions.claimOutcome = ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    assertFalse(fixture.worker.dispatch(1L));
    assertEquals(0, fixture.executor.executeCalls);
  }

  /**
   * Blank worker tokens are rejected before a durable claim can create an unfenceable execution.
   */
  @Test
  void rejectsBlankWorkerTokenBeforeClaim() {
    Fixture fixture = fixture();
    fixture.workerToken = " ";
    fixture.rebuildWorker();

    assertThrows(IllegalStateException.class, () -> fixture.worker.dispatch(1L));
    assertEquals(0, fixture.executor.executeCalls);
  }

  /** A transaction adapter returning a different token is rejected before external Provider I/O. */
  @Test
  void rejectsClaimWithUnexpectedWorkerToken() {
    Fixture fixture = fixture();
    fixture.transactions.claimedTokenOverride = "different-worker";

    assertThrows(IllegalStateException.class, () -> fixture.worker.dispatch(1L));
    assertEquals(0, fixture.executor.executeCalls);
  }

  /**
   * A null resolver result is terminalized after claim instead of escaping into the worker loop.
   */
  @Test
  void terminalizesNullExecutionResourceAfterClaim() {
    Fixture fixture = fixture();
    fixture.returnNullResource = true;
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(0, fixture.executor.executeCalls);
  }

  /** A Provider callback carrying null error data is converted into one deterministic failure. */
  @Test
  void terminalizesNullProviderFailure() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(null);

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("null failure"));
  }

  /**
   * A cancelled Provider error takes the cancellation path even when no response snapshot exists.
   */
  @Test
  void terminalizesCancelledProviderFailure() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.CANCELLED, "cancelled"));

    assertEquals(InvocationStatus.CANCELLED, fixture.transactions.current.status());
    assertEquals(0, fixture.transactions.scheduleRetryCalls);
  }

  /**
   * Retry policy lookup failure is itself a durable final error and cannot leave a RUNNING row
   * stuck.
   */
  @Test
  void terminalizesRetryPolicyResolutionFailure() {
    Fixture fixture = fixture();
    fixture.retryPolicyFailure = new IllegalStateException("policy storage unavailable");
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.TRANSIENT, "provider unavailable"));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.transactions.current.error().kind());
  }

  /**
   * Synchronous executor setup exceptions are normalized into a durable invalid-request failure.
   */
  @Test
  void terminalizesSynchronousExecutorFailure() {
    Fixture fixture = fixture();
    fixture.executor.failure = new IllegalStateException("executor setup failed");

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("executor setup failed"));
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /** Streamed thinking is projected lossily, while the final response fills the missing suffix. */
  @Test
  void projectsThinkingDeltasAndFinalThinkingGap() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.ThinkingDelta("thin"));
    fixture.executor.listener.onComplete(
        response("", "thinking", List.of(), ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertEquals("thinking", fixture.transactions.current.result().thinking());
    assertEquals(
        List.of(
            new ProviderStreamEvent.ThinkingDelta("thin"),
            new ProviderStreamEvent.ThinkingDelta("king")),
        modelDeltas(fixture.sink.events));
  }

  /**
   * When the final Provider response omits thinking, already streamed deltas are merged into the
   * durable response without re-emitting them as a trailing SSE gap.
   */
  @Test
  void persistsStreamedThinkingWhenFinalResponseOmitsThinking() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.ThinkingDelta("hidden"));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertEquals("answer", fixture.transactions.current.result().text());
    assertEquals("hidden", fixture.transactions.current.result().thinking());
    assertEquals(
        List.of(
            new ProviderStreamEvent.ThinkingDelta("hidden"),
            new ProviderStreamEvent.TextDelta("answer")),
        modelDeltas(fixture.sink.events));
  }

  /**
   * Tool-call fragments retain source order and receive only the final missing identity/argument
   * suffixes.
   */
  @Test
  void projectsAndCompletesStreamedToolCallFragments() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(
        new ProviderStreamEvent.ToolCallDelta(0, "call", "to", "{\"a\":"));
    fixture.executor.listener.onComplete(
        response(
            "",
            "",
            List.of(new ProviderToolCall("call-1", "tool", "{\"a\":1}")),
            ProviderStopReason.TOOL_CALLS));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertEquals(
        List.of(
            new ProviderStreamEvent.ToolCallDelta(0, "call", "to", "{\"a\":"),
            new ProviderStreamEvent.ToolCallDelta(0, "-1", "ol", "1}")),
        modelDeltas(fixture.sink.events));
  }

  /**
   * Incompatible streamed tool identities fail the Invocation rather than projecting an incoherent
   * call.
   */
  @Test
  void rejectsConflictingStreamedToolCallIdentity() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(
        new ProviderStreamEvent.ToolCallDelta(0, "call-1", "tool", "{}"));
    fixture.executor.listener.onDelta(
        new ProviderStreamEvent.ToolCallDelta(0, "call-2", null, null));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.transactions.current.error().kind());
  }

  /** A final response cannot omit a tool call that was already exposed to realtime consumers. */
  @Test
  void rejectsFinalResponseThatOmitsStreamedToolCall() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(
        new ProviderStreamEvent.ToolCallDelta(0, "call-1", "tool", "{}"));
    fixture.executor.listener.onComplete(response("", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("omits"));
  }

  /**
   * A duplicated local handle is conservatively stopped and terminalized as UNKNOWN under its new
   * fence.
   */
  @Test
  void resolvesConflictingLocalHandleAsUnknown() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.transactions.current = queued(request(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(1, fixture.executor.executeCalls);
    assertTrue(fixture.executor.handle.isCancelled());
    assertEquals(InvocationStatus.UNKNOWN, fixture.transactions.current.status());
    assertEquals(1, fixture.transactions.completeUnknownCalls);
  }

  /**
   * Lost activity fencing stops the external handle without trying to turn a partial stream into a
   * result.
   */
  @Test
  void stopsLocalHandleWhenActivityMutationLosesOwnership() throws Exception {
    Fixture fixture = fixture();
    fixture.transactions.activityOutcome = ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("answer"));
    await(fixture.transactions.activityRecorded);
    await(fixture.executor.handle.cancelledLatch);

    assertTrue(fixture.executor.handle.isCancelled());
    awaitInactive(fixture.worker);
    assertEquals(0, fixture.transactions.terminalCalls);
  }

  /**
   * A retry CAS loss closes only the local handle and leaves durable retry recovery to its winner.
   */
  @Test
  void stopsLocalHandleWhenRetrySchedulingLosesOwnership() {
    Fixture fixture = fixture();
    fixture.retryPolicy = retryPolicy(1, Duration.ofMillis(10));
    fixture.transactions.retryOutcome = ModelInvocationUpdateOutcome.LOST_OWNERSHIP;
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.TRANSIENT, "provider unavailable"));

    assertTrue(fixture.executor.handle.isCancelled());
    assertTrue(fixture.notifier.targets.isEmpty());
    assertEquals(0, fixture.transactions.terminalCalls);
  }

  /** A duplicate delta after terminal state is ignored and cannot reopen a completed Invocation. */
  @Test
  void ignoresDeltaAfterTerminalCallback() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(response("", ProviderStopReason.COMPLETED));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("late"));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertEquals(1, fixture.transactions.terminalCalls);
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** A null final response is normalized into a fenced invalid-request failure. */
  @Test
  void terminalizesNullFinalResponse() {
    Fixture fixture = fixture();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(null);

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(ProviderErrorKind.INVALID_REQUEST, fixture.transactions.current.error().kind());
  }

  /** Provider TOOL_CALLS termination without calls violates the frozen response contract. */
  @Test
  void rejectsToolCallsStopReasonWithoutToolCalls() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(response("", ProviderStopReason.TOOL_CALLS));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("requires tool calls"));
  }

  /**
   * Tool calls must name a Tool frozen in the ProviderRequest rather than a live registry entry.
   */
  @Test
  void rejectsToolCallForUndeclaredFrozenTool() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(
        response(
            "",
            "",
            List.of(new ProviderToolCall("call-1", "other", "{}")),
            ProviderStopReason.TOOL_CALLS));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("undeclared tool"));
  }

  /** Frozen requests cannot hide ambiguous duplicate tool names from response validation. */
  @Test
  void rejectsDuplicateFrozenToolDefinitions() {
    Fixture fixture = fixture();
    ProviderRequest base = request();
    ProviderToolDefinition tool =
        new ProviderToolDefinition("tool", "test tool", "{\"type\":\"object\"}");
    fixture.transactions.current =
        queued(
            new ProviderRequest(
                base.model(),
                base.variant(),
                base.messages(),
                List.of(tool, tool),
                base.cacheControl()),
            NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("duplicate tool"));
  }

  /** Tool arguments are strictly required to remain JSON objects at the Runtime boundary. */
  @Test
  void rejectsNonObjectToolArguments() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(
        response(
            "",
            "",
            List.of(new ProviderToolCall("call-1", "tool", "[]")),
            ProviderStopReason.TOOL_CALLS));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("JSON object"));
  }

  /**
   * Duplicate Provider tool-call IDs are rejected before Reconciler can materialize sibling
   * Invocations.
   */
  @Test
  void rejectsDuplicateProviderToolCallIds() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(
        response(
            "",
            "",
            List.of(
                new ProviderToolCall("call-1", "tool", "{}"),
                new ProviderToolCall("call-1", "tool", "{}")),
            ProviderStopReason.TOOL_CALLS));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("ids must be unique"));
  }

  /**
   * Exact and prefix-repeat tool fragments are idempotent rather than corrupting a ToolCall
   * identity.
   */
  @Test
  void acceptsRepeatedAndPrefixToolCallFragments() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(
        new ProviderStreamEvent.ToolCallDelta(0, "call-1", "tool", "{}"));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.ToolCallDelta(0, "call", "to", null));
    fixture.executor.listener.onDelta(
        new ProviderStreamEvent.ToolCallDelta(0, "call-1", "tool", null));
    fixture.executor.listener.onComplete(
        response(
            "",
            "",
            List.of(new ProviderToolCall("call-1", "tool", "{}")),
            ProviderStopReason.TOOL_CALLS));

    assertEquals(InvocationStatus.SUCCEEDED, fixture.transactions.current.status());
    assertEquals(3, modelDeltas(fixture.sink.events).size());
  }

  /** Executing an already terminalized initial deadline does not start Provider I/O. */
  @Test
  void terminalizesAlreadyExpiredClaimBeforeProviderExecution() {
    Fixture fixture = fixture(new SteppingClock(NOW, NOW.plusSeconds(1)));
    fixture.transactions.deadlineOffset = Duration.ofMillis(1);
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertEquals(0, fixture.executor.executeCalls);
  }

  /**
   * A ProviderException thrown before handle return follows the same terminal callback
   * classification.
   */
  @Test
  void classifiesProviderExceptionThrownByExecutor() {
    Fixture fixture = fixture();
    fixture.executor.failure = new ProviderException(ProviderErrorKind.CANCELLED, "cancelled");

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.CANCELLED, fixture.transactions.current.status());
  }

  /**
   * A null ModelExecutionHandle is an adapter contract error and cannot leave an active local
   * owner.
   */
  @Test
  void terminalizesNullModelExecutionHandle() {
    Fixture fixture = fixture();
    fixture.executor.returnNullHandle = true;

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("model execution handle"));
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /**
   * A synchronous failure before handle return attaches and immediately cancels the late handle.
   */
  @Test
  void cancelsHandleAttachedAfterSynchronousFailure() {
    Fixture fixture = fixture();
    fixture.executor.synchronousFailure =
        new ProviderException(ProviderErrorKind.INVALID_REQUEST, "invalid request");

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.executor.handle.isCancelled());
    assertFalse(fixture.worker.hasActiveExecution());
  }

  /**
   * An activity-port outage stops local I/O and leaves durable recovery to the still-running lease.
   */
  @Test
  void stopsLocalHandleWhenActivityPersistenceThrows() throws Exception {
    Fixture fixture = fixture();
    fixture.transactions.activityFailure = new IllegalStateException("database unavailable");
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("answer"));
    await(fixture.transactions.activityRecorded);
    await(fixture.executor.handle.cancelledLatch);
    awaitInactive(fixture.worker);

    assertEquals(0, fixture.transactions.terminalCalls);
  }

  /** Lease-renew transport failure also tears down only the process-local external execution. */
  @Test
  void stopsLocalHandleWhenLeaseRenewalThrows() throws Exception {
    Fixture fixture = fixture();
    fixture.transactions.renewFailure = new IllegalStateException("database unavailable");
    fixture.rebuildWorker(
        config(Duration.ofSeconds(1), Duration.ofMillis(5), Duration.ofMillis(5)));

    assertTrue(fixture.worker.dispatch(1L));
    await(fixture.transactions.renewed);
    await(fixture.executor.handle.cancelledLatch);
    awaitInactive(fixture.worker);

    assertEquals(0, fixture.transactions.terminalCalls);
  }

  /**
   * A terminal persistence exception cancels the handle without sending a wake for an uncommitted
   * result.
   */
  @Test
  void isolatesTerminalPersistenceFailure() {
    Fixture fixture = fixture();
    fixture.transactions.terminalFailure = new IllegalStateException("database unavailable");

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertTrue(fixture.executor.handle.isCancelled());
    assertTrue(fixture.notifier.targets.isEmpty());
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** Final failure persistence has the same fencing-safe local cleanup as success persistence. */
  @Test
  void isolatesFinalFailurePersistenceFailure() {
    Fixture fixture = fixture();
    fixture.transactions.terminalFailure = new IllegalStateException("database unavailable");

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.AUTHENTICATION, "bad credential"));

    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertTrue(fixture.executor.handle.isCancelled());
    assertTrue(fixture.notifier.targets.isEmpty());
  }

  /** Cancellation persistence failure cannot be mistaken for a committed durable cancellation. */
  @Test
  void isolatesCancellationPersistenceFailure() {
    Fixture fixture = fixture();
    fixture.transactions.terminalFailure = new IllegalStateException("database unavailable");

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.CANCELLED, "cancelled"));

    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertTrue(fixture.executor.handle.isCancelled());
    assertTrue(fixture.notifier.targets.isEmpty());
  }

  /** Retry persistence failure is similarly local-only and cannot forge a delayed signal. */
  @Test
  void isolatesRetryPersistenceFailure() {
    Fixture fixture = fixture();
    fixture.retryPolicy = retryPolicy(1, Duration.ofMillis(10));
    fixture.transactions.retryFailure = new IllegalStateException("database unavailable");
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onError(
        new ProviderException(ProviderErrorKind.TRANSIENT, "provider unavailable"));

    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertTrue(fixture.executor.handle.isCancelled());
    assertTrue(fixture.notifier.targets.isEmpty());
  }

  /** Non-TOOL_CALLS stop reasons cannot carry executable tool calls. */
  @Test
  void rejectsToolCallsWithNonToolCallsStopReason() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(
        response(
            "",
            "",
            List.of(new ProviderToolCall("call-1", "tool", "{}")),
            ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("only TOOL_CALLS"));
  }

  /** Malformed raw tool arguments are rejected independently from valid JSON-array validation. */
  @Test
  void rejectsMalformedToolArgumentsJson() {
    Fixture fixture = fixture();
    fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onComplete(
        response(
            "",
            "",
            List.of(new ProviderToolCall("call-1", "tool", "not-json")),
            ProviderStopReason.TOOL_CALLS));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("must contain JSON"));
  }

  /** Tool arguments must be one unambiguous JSON object, not a permissive parser prefix. */
  @Test
  void rejectsTrailingTokensAndDuplicateToolArgumentFields() {
    for (String argumentsJson : List.of("{} {}", "{\"value\":1,\"value\":2}")) {
      Fixture fixture = fixture();
      fixture.transactions.current = queued(requestWithTool(), NOW.minusSeconds(1));

      assertTrue(fixture.worker.dispatch(1L));
      fixture.executor.listener.onComplete(
          response(
              "",
              "",
              List.of(new ProviderToolCall("call-1", "tool", argumentsJson)),
              ProviderStopReason.TOOL_CALLS));

      assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
      assertTrue(fixture.transactions.current.error().message().contains("must contain JSON"));
    }
  }

  /** Scheduler rejection before Provider I/O is safely retryable and needs no UNKNOWN recovery. */
  @Test
  void schedulesRetryWhenInitialWatchdogRegistrationIsRejected() {
    Fixture fixture = fixture();
    fixture.retryPolicy = retryPolicy(1, Duration.ofMillis(10));
    ScheduledExecutorService rejectedScheduler = new ScheduledThreadPoolExecutor(1);
    rejectedScheduler.shutdownNow();
    fixture.schedulerOverride = rejectedScheduler;
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));

    assertEquals(InvocationStatus.RETRY_WAIT, fixture.transactions.current.status());
    assertEquals(0, fixture.executor.executeCalls);
    assertEquals(1, fixture.transactions.scheduleRetryCalls);
    assertTrue(fixture.notifier.targets.isEmpty());
  }

  /** Scheduler failure after Provider I/O abandons the lease for conservative UNKNOWN recovery. */
  @Test
  void abandonsExecutionWhenActivityTimerRegistrationIsRejected() {
    Fixture fixture = fixture();
    fixture.schedulerOverride = new RejectingOneShotScheduler(3);
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("partial"));

    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertTrue(fixture.executor.handle.isCancelled());
    assertFalse(fixture.worker.hasActiveExecution());
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** A delayed local timer cannot let a response at the durable total deadline win as success. */
  @Test
  void enforcesTotalDeadlineAtCompletionCallbackBoundary() {
    MutableClock clock = new MutableClock(NOW);
    Fixture fixture = fixture(clock);
    fixture.resource = resource(Duration.ofSeconds(5), Duration.ofSeconds(20));
    fixture.retryPolicy = retryPolicy(0, Duration.ofMillis(1));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    clock.set(NOW.plusSeconds(5));
    fixture.executor.listener.onComplete(response("answer", ProviderStopReason.COMPLETED));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("deadline"));
  }

  /** A delta arriving at the idle boundary cannot retroactively reset the expired idle clock. */
  @Test
  void enforcesIdleTimeoutAtDeltaCallbackBoundary() {
    MutableClock clock = new MutableClock(NOW);
    Fixture fixture = fixture(clock);
    fixture.resource = resource(Duration.ofSeconds(20), Duration.ofSeconds(5));
    fixture.retryPolicy = retryPolicy(0, Duration.ofMillis(1));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    clock.set(NOW.plusSeconds(5));
    fixture.executor.listener.onDelta(new ProviderStreamEvent.TextDelta("late"));

    assertEquals(InvocationStatus.FAILED, fixture.transactions.current.status());
    assertTrue(fixture.transactions.current.error().message().contains("idle"));
    assertTrue(fixture.sink.events.isEmpty());
  }

  /** An early/floored deadline timer must re-read Clock instead of forging a timeout. */
  @Test
  void reschedulesDeadlineTimerWhenAuthoritativeClockHasNotReachedDeadline() throws Exception {
    Fixture fixture = fixture(Clock.fixed(NOW, ZoneOffset.UTC));
    fixture.resource = resource(Duration.ofMillis(20), Duration.ofSeconds(1));
    fixture.rebuildWorker();

    assertTrue(fixture.worker.dispatch(1L));
    Thread.sleep(60L);

    assertEquals(InvocationStatus.RUNNING, fixture.transactions.current.status());
    assertTrue(fixture.worker.hasActiveExecution());
    fixture.worker.stop();
  }

  private Fixture fixture() {
    return fixture(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Fixture fixture(Clock clock) {
    Fixture fixture = new Fixture(clock);
    fixture.rebuildWorker();
    return fixture;
  }

  private static ModelWorkerConfig config(
      Duration leaseDuration, Duration heartbeatInterval, Duration activityFlushInterval) {
    return new ModelWorkerConfig(leaseDuration, heartbeatInterval, activityFlushInterval);
  }

  private static InvocationRetryPolicy retryPolicy(int maxRetries, Duration delay) {
    return new InvocationRetryPolicy(
        maxRetries, InvocationRetryBackoffStrategy.FIXED, delay, delay);
  }

  private static ModelExecutionResource resource(Duration totalTimeout, Duration idleTimeout) {
    return new ModelExecutionResource(
        (request, listener) -> {
          throw new AssertionError("fixture executor must be installed before resource creation");
        },
        new ModelCallTimeoutPolicy(totalTimeout, idleTimeout));
  }

  private static List<ProviderStreamEvent> modelDeltas(List<RealtimeEvent> events) {
    return events.stream()
        .filter(RealtimeEvent.ModelDelta.class::isInstance)
        .map(RealtimeEvent.ModelDelta.class::cast)
        .map(RealtimeEvent.ModelDelta::delta)
        .toList();
  }

  private static List<Integer> modelDeltaAttempts(List<RealtimeEvent> events) {
    return events.stream()
        .map(RealtimeEvent.ModelDelta.class::cast)
        .map(RealtimeEvent.ModelDelta::attempt)
        .toList();
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(
        latch.await(2, TimeUnit.SECONDS), "timed out waiting for asynchronous worker action");
  }

  private static void awaitInactive(ModelWorker worker) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (worker.hasActiveExecution() && System.nanoTime() < deadlineNanos) {
      Thread.sleep(1L);
    }
    assertFalse(worker.hasActiveExecution(), "timed out waiting for local handle cleanup");
  }

  private static final class SteppingClock extends Clock {
    private final Instant initial;
    private final Instant advanced;
    private final AtomicInteger reads = new AtomicInteger();

    private SteppingClock(Instant initial, Instant advanced) {
      this.initial = initial;
      this.advanced = advanced;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return reads.incrementAndGet() <= 3 ? initial : advanced;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    private MutableClock(Instant initial) {
      current = new AtomicReference<>(initial);
    }

    private void set(Instant value) {
      current.set(value);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }

  private static ProviderRequest request() {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "default",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelDescriptor model =
        new ModelDescriptor(
            1L,
            2L,
            ProviderType.OPENAI,
            "model",
            false,
            false,
            pricing,
            PromptCachePolicy.disabled());
    return new ProviderRequest(
        model,
        variant,
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("question")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderRequest requestWithTool() {
    ProviderRequest base = request();
    return new ProviderRequest(
        base.model(),
        base.variant(),
        base.messages(),
        List.of(new ProviderToolDefinition("tool", "test tool", "{\"type\":\"object\"}")),
        base.cacheControl());
  }

  private static ProviderResponse response(String text, ProviderStopReason stopReason) {
    return response(text, "", List.of(), stopReason);
  }

  private static ProviderResponse response(
      String text,
      String thinking,
      List<ProviderToolCall> toolCalls,
      ProviderStopReason stopReason) {
    return new ProviderResponse(
        text,
        thinking,
        toolCalls,
        stopReason,
        new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        null,
        null,
        "{}");
  }

  private final class Fixture {
    private final Clock clock;
    private final ProviderRequest request = request();
    private final RecordingTransactions transactions;
    private final RecordingExecutor executor = new RecordingExecutor();
    private final RecordingSink sink = new RecordingSink();
    private final RecordingNotifier notifier = new RecordingNotifier();
    private ModelExecutionResource resource;
    private InvocationRetryPolicy retryPolicy = retryPolicy(0, Duration.ofMillis(1));
    private RuntimeException resolutionFailure;
    private RuntimeException retryPolicyFailure;
    private boolean returnNullResource;
    private String workerToken = "worker-a";
    private ScheduledExecutorService schedulerOverride;
    private int resolverCalls;
    private ModelWorker worker;

    private Fixture(Clock clock) {
      this.clock = clock;
      this.transactions = new RecordingTransactions(request, clock.instant().minusSeconds(1));
      this.resource =
          new ModelExecutionResource(
              executor, new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(30)));
    }

    private void rebuildWorker() {
      rebuildWorker(config(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(5)));
    }

    private void rebuildWorker(ModelWorkerConfig workerConfig) {
      ScheduledExecutorService scheduler =
          schedulerOverride == null ? new ScheduledThreadPoolExecutor(2) : schedulerOverride;
      if (!schedulers.contains(scheduler)) {
        schedulers.add(scheduler);
      }
      worker =
          new ModelWorker(
              transactions,
              request -> {
                resolverCalls++;
                if (resolutionFailure != null) {
                  throw resolutionFailure;
                }
                if (returnNullResource) {
                  return null;
                }
                return new ModelExecutionResource(executor, resource.timeoutPolicy());
              },
              () -> {
                if (retryPolicyFailure != null) {
                  throw retryPolicyFailure;
                }
                return retryPolicy;
              },
              sink,
              notifier,
              workerConfig,
              clock,
              scheduler,
              () -> workerToken);
    }
  }

  private static final class RecordingTransactions implements ModelInvocationTransactions {
    private ModelInvocation current;
    private boolean candidateAvailable = true;
    private boolean recoverRunningLease;
    private String claimedTokenOverride;
    private ModelInvocationUpdateOutcome claimOutcome = ModelInvocationUpdateOutcome.APPLIED;
    private ModelInvocationUpdateOutcome renewOutcome = ModelInvocationUpdateOutcome.APPLIED;
    private ModelInvocationUpdateOutcome activityOutcome = ModelInvocationUpdateOutcome.APPLIED;
    private ModelInvocationUpdateOutcome terminalOutcome = ModelInvocationUpdateOutcome.APPLIED;
    private ModelInvocationUpdateOutcome retryOutcome = ModelInvocationUpdateOutcome.APPLIED;
    private RuntimeException renewFailure;
    private RuntimeException activityFailure;
    private RuntimeException retryFailure;
    private RuntimeException terminalFailure;
    private Duration deadlineOffset;
    private boolean blockTerminal;
    private final CountDownLatch terminalEntered = new CountDownLatch(1);
    private final CountDownLatch terminalRelease = new CountDownLatch(1);
    private final CountDownLatch terminalized = new CountDownLatch(1);
    private final CountDownLatch renewed = new CountDownLatch(1);
    private final CountDownLatch renewedAfterTerminalStarted = new CountDownLatch(1);
    private final CountDownLatch activityRecorded = new CountDownLatch(1);
    private int findClaimableCalls;
    private int renewCalls;
    private int recordActivityCalls;
    private int terminalCalls;
    private int completeFailureCalls;
    private int completeUnknownCalls;
    private int scheduleRetryCalls;

    private RecordingTransactions(ProviderRequest request, Instant createdAt) {
      current = queued(request, createdAt);
    }

    @Override
    public synchronized Optional<ModelInvocation> findClaimable(long invocationId, Instant now) {
      findClaimableCalls++;
      if (!candidateAvailable || current.id() != invocationId) {
        return Optional.empty();
      }
      return Optional.of(current);
    }

    @Override
    public synchronized Optional<ModelInvocation> findNextClaimable(Instant now) {
      return candidateAvailable ? Optional.of(current) : Optional.empty();
    }

    @Override
    public synchronized Optional<ClaimedModelInvocation> claim(
        long invocationId,
        String workerToken,
        ModelCallTimeoutPolicy timeoutPolicy,
        Duration workerLeaseDuration,
        Instant now) {
      if (claimOutcome != ModelInvocationUpdateOutcome.APPLIED || current.id() != invocationId) {
        return Optional.empty();
      }
      if (current.status() == InvocationStatus.RUNNING && !recoverRunningLease) {
        return Optional.empty();
      }
      String leaseToken = claimedTokenOverride == null ? workerToken : claimedTokenOverride;
      ModelInvocation claimed;
      if (current.status() == InvocationStatus.QUEUED) {
        claimed =
            copy(
                current,
                InvocationStatus.RUNNING,
                current.attempt(),
                null,
                new Lease(leaseToken, now.plus(workerLeaseDuration)),
                deadlineOffset == null
                    ? now.plus(timeoutPolicy.modelCallTimeout())
                    : now.plus(deadlineOffset),
                now,
                null,
                null,
                now,
                null);
      } else if (current.status() == InvocationStatus.RETRY_WAIT) {
        claimed =
            copy(
                current,
                InvocationStatus.RUNNING,
                current.attempt() + 1,
                null,
                new Lease(leaseToken, now.plus(workerLeaseDuration)),
                current.deadlineAt(),
                now,
                null,
                null,
                current.startedAt(),
                null);
      } else if (current.status() == InvocationStatus.RUNNING) {
        claimed =
            copy(
                current,
                InvocationStatus.RUNNING,
                current.attempt(),
                null,
                new Lease(leaseToken, now.plus(workerLeaseDuration)),
                current.deadlineAt(),
                current.lastActivityAt(),
                null,
                null,
                current.startedAt(),
                null);
      } else {
        return Optional.empty();
      }
      current = claimed;
      return Optional.of(new ClaimedModelInvocation(claimed, recoverRunningLease));
    }

    @Override
    public synchronized ModelInvocationUpdateOutcome renew(
        ClaimedModelInvocation claimed, Duration workerLeaseDuration, Instant now) {
      renewCalls++;
      renewed.countDown();
      if (terminalEntered.getCount() == 0) {
        renewedAfterTerminalStarted.countDown();
      }
      if (renewFailure != null) {
        throw renewFailure;
      }
      if (renewOutcome != ModelInvocationUpdateOutcome.APPLIED) {
        return renewOutcome;
      }
      current =
          copy(
              current,
              InvocationStatus.RUNNING,
              current.attempt(),
              null,
              new Lease(claimed.invocation().workerLease().token(), now.plus(workerLeaseDuration)),
              current.deadlineAt(),
              current.lastActivityAt(),
              null,
              null,
              current.startedAt(),
              null);
      return ModelInvocationUpdateOutcome.APPLIED;
    }

    @Override
    public synchronized ModelInvocationUpdateOutcome recordActivity(
        ClaimedModelInvocation claimed, Instant activityAt, Instant now) {
      recordActivityCalls++;
      activityRecorded.countDown();
      if (activityFailure != null) {
        throw activityFailure;
      }
      if (activityOutcome != ModelInvocationUpdateOutcome.APPLIED) {
        return activityOutcome;
      }
      Instant effectiveActivity =
          activityAt.isAfter(current.lastActivityAt()) ? activityAt : current.lastActivityAt();
      current =
          copy(
              current,
              InvocationStatus.RUNNING,
              current.attempt(),
              null,
              current.workerLease(),
              current.deadlineAt(),
              effectiveActivity,
              null,
              null,
              current.startedAt(),
              null);
      return ModelInvocationUpdateOutcome.APPLIED;
    }

    @Override
    public ModelInvocationUpdateOutcome completeSuccess(
        ClaimedModelInvocation claimed,
        ProviderResponse result,
        Instant lastObservedActivityAt,
        Instant now) {
      return terminal(InvocationStatus.SUCCEEDED, result, null, lastObservedActivityAt, now);
    }

    @Override
    public ModelInvocationUpdateOutcome completeFailure(
        ClaimedModelInvocation claimed,
        ModelInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      synchronized (this) {
        completeFailureCalls++;
      }
      return terminal(InvocationStatus.FAILED, null, error, lastObservedActivityAt, now);
    }

    @Override
    public ModelInvocationUpdateOutcome completeCancelled(
        ClaimedModelInvocation claimed, Instant lastObservedActivityAt, Instant now) {
      return terminal(InvocationStatus.CANCELLED, null, null, lastObservedActivityAt, now);
    }

    @Override
    public ModelInvocationUpdateOutcome completeUnknown(
        ClaimedModelInvocation claimed,
        ModelInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      synchronized (this) {
        completeUnknownCalls++;
      }
      return terminal(InvocationStatus.UNKNOWN, null, error, lastObservedActivityAt, now);
    }

    @Override
    public synchronized ModelInvocationUpdateOutcome scheduleRetry(
        ClaimedModelInvocation claimed,
        Instant nextAttemptAt,
        Instant lastObservedActivityAt,
        Instant now) {
      scheduleRetryCalls++;
      if (retryFailure != null) {
        throw retryFailure;
      }
      if (retryOutcome != ModelInvocationUpdateOutcome.APPLIED) {
        return retryOutcome;
      }
      current =
          copy(
              current,
              InvocationStatus.RETRY_WAIT,
              current.attempt(),
              nextAttemptAt,
              null,
              current.deadlineAt(),
              latestActivity(current.lastActivityAt(), lastObservedActivityAt),
              null,
              null,
              current.startedAt(),
              null);
      return ModelInvocationUpdateOutcome.APPLIED;
    }

    private ModelInvocationUpdateOutcome terminal(
        InvocationStatus status,
        ProviderResponse result,
        ModelInvocationError error,
        Instant lastObservedActivityAt,
        Instant now) {
      synchronized (this) {
        terminalCalls++;
        terminalEntered.countDown();
      }
      if (blockTerminal) {
        try {
          if (!terminalRelease.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("timed out waiting to release terminal transaction");
          }
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError("terminal transaction interrupted", failure);
        }
      }
      synchronized (this) {
        if (terminalFailure != null) {
          throw terminalFailure;
        }
        if (terminalOutcome != ModelInvocationUpdateOutcome.APPLIED) {
          return terminalOutcome;
        }
        current =
            copy(
                current,
                status,
                current.attempt(),
                null,
                null,
                current.deadlineAt(),
                latestActivity(current.lastActivityAt(), lastObservedActivityAt),
                result,
                error,
                current.startedAt(),
                now);
        terminalized.countDown();
        return ModelInvocationUpdateOutcome.APPLIED;
      }
    }

    private static Instant latestActivity(Instant current, Instant observed) {
      return observed.isAfter(current) ? observed : current;
    }

    private synchronized ModelInvocation expiredRunning() {
      Instant startedAt = current.createdAt().plusMillis(1);
      return copy(
          current,
          InvocationStatus.RUNNING,
          1,
          null,
          new Lease("expired-worker", current.createdAt().plusSeconds(1)),
          current.createdAt().plusSeconds(30),
          startedAt,
          null,
          null,
          startedAt,
          null);
    }
  }

  private static final class RecordingExecutor implements ModelExecutor {
    private final RecordingHandle handle = new RecordingHandle();
    private ModelExecutionListener listener;
    private ModelExecutionRequest request;
    private ProviderResponse synchronousResponse;
    private ProviderException synchronousFailure;
    private RuntimeException failure;
    private boolean returnNullHandle;
    private int executeCalls;

    @Override
    public ModelExecutionHandle execute(
        ModelExecutionRequest request, ModelExecutionListener listener) {
      executeCalls++;
      this.request = request;
      this.listener = listener;
      if (failure != null) {
        throw failure;
      }
      if (synchronousResponse != null) {
        listener.onComplete(synchronousResponse);
      }
      if (synchronousFailure != null) {
        listener.onError(synchronousFailure);
      }
      return returnNullHandle ? null : handle;
    }
  }

  private static final class RecordingHandle implements ModelExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CountDownLatch cancelledLatch = new CountDownLatch(1);
    private RuntimeException cancelFailure;

    @Override
    public void cancel() {
      cancelled.set(true);
      cancelledLatch.countDown();
      if (cancelFailure != null) {
        throw cancelFailure;
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }

  private static final class RejectingOneShotScheduler extends ScheduledThreadPoolExecutor {
    private final AtomicInteger oneShotCalls = new AtomicInteger();
    private final int rejectAt;

    private RejectingOneShotScheduler(int rejectAt) {
      super(2);
      this.rejectAt = rejectAt;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      if (oneShotCalls.incrementAndGet() == rejectAt) {
        throw new RejectedExecutionException("scheduler saturated");
      }
      return super.schedule(command, delay, unit);
    }
  }

  private static final class RecordingSink implements RealtimeEventSink {
    private final List<RealtimeEvent> events = new CopyOnWriteArrayList<>();
    private RuntimeException failure;

    @Override
    public void append(RealtimeEvent event) {
      if (failure != null) {
        throw failure;
      }
      events.add(event);
    }
  }

  private static final class RecordingNotifier implements ActivationNotifier {
    private final List<ExecutionTarget> targets = new CopyOnWriteArrayList<>();
    private final CountDownLatch notified = new CountDownLatch(1);
    private RuntimeException failure;

    @Override
    public void notifyAfterCommit(ExecutionTarget target) {
      if (failure != null) {
        throw failure;
      }
      targets.add(target);
      notified.countDown();
    }
  }

  private static ModelInvocation queued(ProviderRequest request, Instant createdAt) {
    return new ModelInvocation(
        1L,
        2L,
        3L,
        0L,
        request,
        InvocationStatus.QUEUED,
        1,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        createdAt,
        null,
        null);
  }

  private static ModelInvocation copy(
      ModelInvocation source,
      InvocationStatus status,
      int attempt,
      Instant nextAttemptAt,
      Lease workerLease,
      Instant deadlineAt,
      Instant lastActivityAt,
      ProviderResponse result,
      ModelInvocationError error,
      Instant startedAt,
      Instant finishedAt) {
    return new ModelInvocation(
        source.id(),
        source.threadId(),
        source.sourceHeadEntryId(),
        source.executionEpoch(),
        source.request(),
        status,
        attempt,
        nextAttemptAt,
        workerLease,
        deadlineAt,
        lastActivityAt,
        result,
        error,
        null,
        source.createdAt(),
        startedAt,
        finishedAt);
  }
}
