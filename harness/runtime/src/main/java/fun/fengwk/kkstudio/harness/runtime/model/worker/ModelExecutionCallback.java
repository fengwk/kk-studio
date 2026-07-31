package fun.fengwk.kkstudio.harness.runtime.model.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * In-flight lifecycle for one claimed Model invocation.
 *
 * <p>This callback owns Provider listener delivery, the local handle lifecycle, safe stream
 * snapshot fencing and SSE ordering, plus heartbeat/deadline/idle/activity-flush watchdogs. {@link
 * ModelTerminalCompleter} owns the durable terminal and retry decisions, and {@link
 * ModelStreamAccumulator} owns stream reconciliation.
 *
 * <p>A terminal callback acquires {@link #terminal} before durable work. Watchdogs deliberately
 * remain armed until terminal persistence has returned so a slow terminal transaction still renews
 * its valid durable lease. Every local exit conditionally releases this exact callback from the
 * owning worker map.
 */
@Slf4j
final class ModelExecutionCallback implements ModelExecutionListener {
  private final ClaimedModelInvocation claimed;
  private final ModelExecutionResource resource;
  private final ModelInvocationTransactions transactions;
  private final ModelTerminalCompleter terminalCompleter;
  private final RealtimeEventSink realtimeEventSink;
  private final ModelWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Consumer<ModelExecutionCallback> ownerRelease;
  private final AtomicBoolean terminal = new AtomicBoolean();
  private final AtomicBoolean active = new AtomicBoolean(true);
  private final ModelStreamAccumulator streamAccumulator = new ModelStreamAccumulator();

  private long lastPublishedSequence;
  private long lastSafeSequence;
  private ModelExecutionHandle handle;
  private Instant lastObservedActivityAt;
  private Instant pendingActivityAt;
  private boolean cancelHandleOnAttach;
  private ScheduledFuture<?> heartbeatFuture;
  private ScheduledFuture<?> deadlineFuture;
  private ScheduledFuture<?> idleFuture;
  private ScheduledFuture<?> activityFlushFuture;

  ModelExecutionCallback(
      ClaimedModelInvocation claimed,
      ModelExecutionResource resource,
      ModelInvocationTransactions transactions,
      ModelTerminalCompleter terminalCompleter,
      RealtimeEventSink realtimeEventSink,
      ModelWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Consumer<ModelExecutionCallback> ownerRelease) {
    this.claimed = Objects.requireNonNull(claimed, "claimed");
    this.resource = Objects.requireNonNull(resource, "resource");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.terminalCompleter = Objects.requireNonNull(terminalCompleter, "terminalCompleter");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.ownerRelease = Objects.requireNonNull(ownerRelease, "ownerRelease");
    this.lastObservedActivityAt = claimed.invocation().lastActivityAt();
  }

  void start() {
    if (!clock.instant().isBefore(claimed.invocation().deadlineAt())) {
      onError(
          new ProviderException(ProviderErrorKind.TRANSIENT, "model execution deadline exceeded"));
      return;
    }
    try {
      scheduleTimers();
    } catch (RejectedExecutionException failure) {
      onError(
          new ProviderException(
              ProviderErrorKind.TRANSIENT, "cannot schedule model execution watchdogs", failure));
      return;
    }
    if (terminal.get()) {
      return;
    }
    try {
      ModelExecutionHandle executionHandle =
          resource
              .executor()
              .execute(
                  new ModelExecutionRequest(
                      claimed.invocation().id(),
                      claimed.invocation().attempt(),
                      claimed.invocation().request(),
                      claimed.invocation().deadlineAt()),
                  this);
      setHandle(executionHandle);
    } catch (ProviderException failure) {
      onError(failure);
    } catch (RuntimeException failure) {
      onError(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "cannot start model execution: " + message(failure, "unknown execution failure"),
              failure));
    }
  }

  @Override
  public void onDelta(ProviderStreamEvent delta) {
    if (terminal.get()) {
      return;
    }
    boolean abandon = false;
    try {
      ProviderException timeout;
      synchronized (this) {
        if (terminal.get()) {
          return;
        }
        Instant activityAt = clock.instant();
        timeout = timeoutAt(activityAt);
        if (timeout == null) {
          long sequence = nextSequence(lastPublishedSequence);
          streamAccumulator.append(delta);
          SafeStreamSnapshot pendingSnapshot = streamAccumulator.snapshotSafe(sequence);
          lastObservedActivityAt = activityAt;
          pendingActivityAt = activityAt;
          if (!scheduleActivityFlushLocked()) {
            return;
          }
          if (!scheduleIdleTimeoutLocked()) {
            return;
          }
          ModelInvocationUpdateOutcome outcome =
              transactions.recordSafeStreamSnapshot(
                  claimed, pendingSnapshot, activityAt, clock.instant());
          if (outcome != ModelInvocationUpdateOutcome.APPLIED) {
            // The adapter has already returned, so cancel only after releasing this monitor. A
            // transport cancel may synchronously re-enter a callback.
            log.warn(
                "safe stream snapshot fenced out for invocation {}", claimed.invocation().id());
            abandon = true;
            return;
          }
          lastPublishedSequence = sequence;
          lastSafeSequence = sequence;
          appendDelta(delta, sequence, activityAt);
          return;
        }
      }
      onError(timeout);
    } catch (RuntimeException failure) {
      onError(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid provider stream event: " + message(failure, "unknown event failure"),
              failure));
    } finally {
      if (abandon) {
        abandon();
      }
    }
  }

  @Override
  public void onComplete(ProviderResponse response) {
    if (!terminal.compareAndSet(false, true)) {
      return;
    }
    try {
      Objects.requireNonNull(response, "response");
      ProviderException timeout;
      synchronized (this) {
        timeout = timeoutAt(clock.instant());
      }
      if (timeout != null) {
        persistFailure(timeout);
        return;
      }
      if (response.stopReason() == ProviderStopReason.CANCELLED) {
        persistCancelled();
        return;
      }
      ModelStreamAccumulator.Completion completion;
      Instant completedAt;
      synchronized (this) {
        completion = streamAccumulator.complete(response);
        ModelResponseValidator.validate(claimed.invocation().request(), completion.response());
        completedAt = clock.instant();
      }
      persistSuccess(completion.response(), completion.gaps(), completedAt);
    } catch (RuntimeException failure) {
      persistFailure(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "invalid provider response: " + message(failure, "unknown response failure"),
              failure));
    }
  }

  @Override
  public void onError(ProviderException error) {
    if (!terminal.compareAndSet(false, true)) {
      return;
    }
    ProviderException timeout;
    synchronized (this) {
      timeout = timeoutAt(clock.instant());
    }
    if (timeout != null) {
      persistFailure(timeout);
      return;
    }
    if (error == null) {
      persistFailure(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "provider returned a null failure"));
      return;
    }
    persistFailure(error);
  }

  private void persistSuccess(
      ProviderResponse response, List<ProviderStreamEvent> finalGaps, Instant completedAt) {
    boolean cancel = true;
    try {
      // Write text/thinking before the terminal fence so a concurrent stop/reconcile can read the
      // complete safe SSE prefix.
      List<SequencedDelta> sequencedFinalGaps = sequenceFinalGaps(finalGaps);
      long finalSequence =
          sequencedFinalGaps.isEmpty()
              ? lastPublishedSequence
              : sequencedFinalGaps.getLast().sequence();
      SafeStreamSnapshot finalSnapshot = streamAccumulator.snapshotSafe(finalSequence);
      if (finalSnapshot.hasContent() || finalSequence > lastSafeSequence) {
        ModelInvocationUpdateOutcome snapshotOutcome =
            transactions.recordSafeStreamSnapshot(
                claimed, finalSnapshot, completedAt, clock.instant());
        if (snapshotOutcome != ModelInvocationUpdateOutcome.APPLIED) {
          log.warn(
              "final safe stream snapshot fenced out for invocation {}", claimed.invocation().id());
        } else {
          lastSafeSequence = finalSequence;
        }
      }
      if (terminalCompleter.completeSuccess(claimed, response, lastObservedActivityAt())) {
        for (SequencedDelta gap : sequencedFinalGaps) {
          lastPublishedSequence = gap.sequence();
          appendDelta(gap.event(), gap.sequence(), completedAt);
        }
        cancel = false;
      }
    } catch (RuntimeException failure) {
      log.warn("cannot persist model success for {}", claimed.invocation().id(), failure);
    } finally {
      finishLocal(cancel);
    }
  }

  private void persistFailure(ProviderException error) {
    try {
      terminalCompleter.completeFailureOrRetry(claimed, error, lastObservedActivityAt());
    } finally {
      finishLocal(true);
    }
  }

  private void persistCancelled() {
    try {
      terminalCompleter.completeCancelled(claimed, lastObservedActivityAt());
    } finally {
      finishLocal(true);
    }
  }

  private void scheduleTimers() {
    synchronized (this) {
      if (terminal.get()) {
        return;
      }
      try {
        heartbeatFuture =
            scheduler.scheduleAtFixedRate(
                this::heartbeat,
                config.heartbeatInterval().toMillis(),
                config.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        deadlineFuture =
            scheduler.schedule(
                this::deadlineExpired,
                delayMillisUntil(claimed.invocation().deadlineAt()),
                TimeUnit.MILLISECONDS);
        Instant idleAt =
            lastObservedActivityAt.plus(resource.timeoutPolicy().modelCallIdleTimeout());
        idleFuture =
            scheduler.schedule(this::idleExpired, delayMillisUntil(idleAt), TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException failure) {
        cancel(heartbeatFuture);
        cancel(deadlineFuture);
        cancel(idleFuture);
        heartbeatFuture = null;
        deadlineFuture = null;
        idleFuture = null;
        throw failure;
      }
    }
  }

  private ScheduledFuture<?> scheduleOnce(Runnable task, long delayMillis, String description) {
    try {
      return scheduler.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException failure) {
      log.warn("cannot schedule {} for {}", description, claimed.invocation().id(), failure);
      abandon();
      return null;
    }
  }

  private boolean scheduleActivityFlushLocked() {
    if (activityFlushFuture != null && !activityFlushFuture.isDone()) {
      return true;
    }
    activityFlushFuture =
        scheduleOnce(
            this::flushActivity, config.activityFlushInterval().toMillis(), "model activity flush");
    return activityFlushFuture != null;
  }

  private boolean scheduleIdleTimeoutLocked() {
    if (idleFuture != null) {
      idleFuture.cancel(false);
    }
    Instant idleAt = lastObservedActivityAt.plus(resource.timeoutPolicy().modelCallIdleTimeout());
    idleFuture =
        scheduleOnce(this::idleExpired, delayMillisUntil(idleAt), "model execution idle timeout");
    return idleFuture != null;
  }

  private void heartbeat() {
    if (!active.get()) {
      return;
    }
    try {
      if (transactions.renew(claimed, config.workerLeaseDuration(), clock.instant())
          != ModelInvocationUpdateOutcome.APPLIED) {
        abandon();
      }
    } catch (RuntimeException failure) {
      log.warn("cannot renew model worker lease for {}", claimed.invocation().id(), failure);
      abandon();
    }
  }

  private void flushActivity() {
    Instant activityAt;
    synchronized (this) {
      activityFlushFuture = null;
      if (terminal.get() || pendingActivityAt == null) {
        return;
      }
      activityAt = pendingActivityAt;
    }
    try {
      if (transactions.recordActivity(claimed, activityAt, clock.instant())
          != ModelInvocationUpdateOutcome.APPLIED) {
        abandon();
        return;
      }
    } catch (RuntimeException failure) {
      log.warn("cannot record model activity for {}", claimed.invocation().id(), failure);
      abandon();
      return;
    }
    synchronized (this) {
      if (pendingActivityAt != null && !pendingActivityAt.isAfter(activityAt)) {
        pendingActivityAt = null;
      }
      if (!terminal.get() && pendingActivityAt != null) {
        scheduleActivityFlushLocked();
      }
    }
  }

  private void deadlineExpired() {
    synchronized (this) {
      if (terminal.get()) {
        return;
      }
      Instant now = clock.instant();
      if (now.isBefore(claimed.invocation().deadlineAt())) {
        deadlineFuture =
            scheduleOnce(
                this::deadlineExpired,
                delayMillisUntil(claimed.invocation().deadlineAt()),
                "model execution deadline");
        return;
      }
    }
    onError(
        new ProviderException(ProviderErrorKind.TRANSIENT, "model execution deadline exceeded"));
  }

  private void idleExpired() {
    synchronized (this) {
      if (terminal.get()) {
        return;
      }
      Instant idleAt = lastObservedActivityAt.plus(resource.timeoutPolicy().modelCallIdleTimeout());
      if (clock.instant().isBefore(idleAt)) {
        scheduleIdleTimeoutLocked();
        return;
      }
    }
    onError(new ProviderException(ProviderErrorKind.TRANSIENT, "model execution idle timed out"));
  }

  private ProviderException timeoutAt(Instant now) {
    if (!now.isBefore(claimed.invocation().deadlineAt())) {
      return new ProviderException(
          ProviderErrorKind.TRANSIENT, "model execution deadline exceeded");
    }
    Instant idleAt = lastObservedActivityAt.plus(resource.timeoutPolicy().modelCallIdleTimeout());
    if (!now.isBefore(idleAt)) {
      return new ProviderException(ProviderErrorKind.TRANSIENT, "model execution idle timed out");
    }
    return null;
  }

  private synchronized Instant lastObservedActivityAt() {
    return lastObservedActivityAt;
  }

  private List<SequencedDelta> sequenceFinalGaps(List<ProviderStreamEvent> finalGaps) {
    long sequence = lastPublishedSequence;
    List<SequencedDelta> sequenced = new ArrayList<>(finalGaps.size());
    for (ProviderStreamEvent gap : finalGaps) {
      sequence = nextSequence(sequence);
      sequenced.add(new SequencedDelta(gap, sequence));
    }
    return List.copyOf(sequenced);
  }

  private static long nextSequence(long current) {
    if (current == Long.MAX_VALUE) {
      throw new IllegalStateException("model delta sequence overflow");
    }
    return current + 1;
  }

  private long delayMillisUntil(Instant target) {
    Instant now = clock.instant();
    if (!now.isBefore(target)) {
      return 0L;
    }
    return Math.max(1L, Duration.between(now, target).toMillis());
  }

  private synchronized void setHandle(ModelExecutionHandle value) {
    handle = Objects.requireNonNull(value, "model execution handle");
    if (cancelHandleOnAttach) {
      cancelHandleLocked();
    }
  }

  /** Abandons only this process-local execution after shutdown or a lost durable fence. */
  void abandon() {
    active.set(false);
    terminal.compareAndSet(false, true);
    finishLocal(true);
  }

  private void finishLocal(boolean cancelHandle) {
    active.set(false);
    synchronized (this) {
      if (cancelHandle) {
        cancelHandleLocked();
      }
      cancel(heartbeatFuture);
      cancel(deadlineFuture);
      cancel(idleFuture);
      cancel(activityFlushFuture);
    }
    ownerRelease.accept(this);
  }

  private void appendDelta(ProviderStreamEvent event, long sequence, Instant createdAt) {
    try {
      realtimeEventSink.append(
          new RealtimeEvent.ModelDelta(
              claimed.invocation().threadId(),
              claimed.invocation().id(),
              claimed.invocation().attempt(),
              sequence,
              event,
              createdAt));
    } catch (RuntimeException failure) {
      log.warn(
          "realtime model delta projection failed for invocation {}",
          claimed.invocation().id(),
          failure);
    }
  }

  private void cancelHandleLocked() {
    cancelHandleOnAttach = true;
    if (handle == null) {
      return;
    }
    try {
      handle.cancel();
    } catch (RuntimeException failure) {
      log.warn(
          "cannot cancel local model execution handle for {}", claimed.invocation().id(), failure);
    }
  }

  private static void cancel(ScheduledFuture<?> future) {
    if (future != null) {
      future.cancel(false);
    }
  }

  private static String message(Throwable failure, String fallback) {
    if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
      return fallback;
    }
    return failure.getMessage();
  }

  private record SequencedDelta(ProviderStreamEvent event, long sequence) {
    private SequencedDelta {
      event = Objects.requireNonNull(event, "event");
      if (sequence <= 0) {
        throw new IllegalArgumentException("sequence must be positive");
      }
    }
  }
}
