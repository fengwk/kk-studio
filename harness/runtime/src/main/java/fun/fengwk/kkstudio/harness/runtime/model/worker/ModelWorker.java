package fun.fengwk.kkstudio.harness.runtime.model.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.SafeStreamSnapshot;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Callback-driven durable ModelInvocation worker.
 *
 * <p>The worker never waits for a Provider terminal callback. It first resolves a short-lived
 * execution resource, then atomically claims the Invocation, and finally delegates I/O to {@link
 * ModelExecutor}. All callbacks are fenced through {@link ModelInvocationTransactions}; a failed or
 * late CAS only tears down the process-local handle. The worker never writes Entry/head/Usage
 * directly. {@code workerTokenSupplier} must return a fresh non-blank token for every claim.
 *
 * <p>调度完全由 {@code harness_execution_target} 统一表达：transaction adapter 在同一事务内原子写入 Invocation
 * 与目标行（claim reschedule / renew reschedule / scheduleRetry reschedule / terminal delete+schedule
 * Thread）。本 worker 因此没有 outbound activation dependency，也不本地调度 retry wake；
 * lease/deadline/idle/activity-flush watchdog 仍是 process-local best-effort。
 */
@Slf4j
public final class ModelWorker {

  private final ModelInvocationTransactions transactions;
  private final ModelExecutionResolver executionResolver;
  private final InvocationRetryPolicyResolver retryPolicyResolver;
  private final RealtimeEventSink realtimeEventSink;
  private final ModelWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Supplier<String> workerTokenSupplier;
  private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();

  public ModelWorker(
      ModelInvocationTransactions transactions,
      ModelExecutionResolver executionResolver,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ModelWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Supplier<String> workerTokenSupplier) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.executionResolver = Objects.requireNonNull(executionResolver, "executionResolver");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.workerTokenSupplier = Objects.requireNonNull(workerTokenSupplier, "workerTokenSupplier");
  }

  /**
   * Processes one direct dispatch for a specified durable Invocation.
   *
   * @return {@code true} only when this process successfully claimed an Invocation and either
   *     dispatched it or made a conservative terminal transition; {@code false} when no current
   *     claimable work exists
   */
  public boolean dispatch(long invocationId) {
    return claim(invocationId, false);
  }

  /**
   * Claims one durable ModelInvocation on the caller thread and starts Provider I/O on this
   * worker's scheduler.
   *
   * <p>Execution-target dispatchers use this method so their durable claim is complete before the
   * target scan proceeds, while no Provider I/O runs on the dispatcher thread.
   *
   * @return {@code true} only when this process successfully claimed an Invocation and either
   *     queued its local execution or durably converged a pre-I/O failure; {@code false} when no
   *     current claimable work exists
   */
  public boolean activate(long invocationId) {
    return claim(invocationId, true);
  }

  private boolean claim(long invocationId, boolean deferExternalStart) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    return transactions
        .findClaimable(invocationId, clock.instant())
        .map(candidate -> claimAndDispatch(candidate, deferExternalStart))
        .orElse(false);
  }

  /** Returns whether this JVM currently owns a local external Model execution handle. */
  public boolean hasActiveExecution() {
    return !executions.isEmpty();
  }

  /**
   * Cancels only process-local handles during shutdown. Durable rows remain RUNNING until their
   * durable state remains unchanged after the process-local handle is abandoned.
   */
  public void stop() {
    List.copyOf(executions.values()).forEach(Execution::abandon);
  }

  private boolean claimAndDispatch(ModelInvocation candidate, boolean deferExternalStart) {
    String workerToken = nextWorkerToken();
    ModelExecutionResource resource = null;
    RuntimeException resolutionFailure = null;
    if (candidate.status() != InvocationStatus.RUNNING) {
      try {
        resource = executionResolver.resolve(candidate.request());
      } catch (RuntimeException error) {
        resolutionFailure = error;
      }
    }
    ModelCallTimeoutPolicy timeoutPolicy =
        resource == null ? ModelCallTimeoutPolicy.DEFAULT : resource.timeoutPolicy();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(
            candidate.id(),
            workerToken,
            timeoutPolicy,
            config.workerLeaseDuration(),
            clock.instant());
    if (claimed.isEmpty()) {
      return false;
    }
    ClaimedModelInvocation ownership = claimed.orElseThrow();
    requireClaimToken(ownership, workerToken);

    if (ownership.recoveredLease()) {
      markRecoveredLeaseUnknown(ownership);
      return true;
    }
    if (resolutionFailure != null) {
      failExecutionSetup(ownership, resolutionFailure);
      return true;
    }
    if (resource == null) {
      failExecutionSetup(
          ownership, new IllegalStateException("model execution resource is unavailable"));
      return true;
    }
    dispatchClaimed(ownership, resource, deferExternalStart);
    return true;
  }

  private String nextWorkerToken() {
    String token = workerTokenSupplier.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("workerTokenSupplier returned a blank token");
    }
    return token;
  }

  private static void requireClaimToken(ClaimedModelInvocation claimed, String expectedToken) {
    if (!claimed.invocation().workerLease().token().equals(expectedToken)) {
      throw new IllegalStateException(
          "claim returned a lease token different from the requested token");
    }
  }

  private void markRecoveredLeaseUnknown(ClaimedModelInvocation claimed) {
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT,
            "model worker lease expired; provider outcome cannot be confirmed");
    try {
      transactions.completeUnknown(
          claimed, error, claimed.invocation().lastActivityAt(), clock.instant());
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist UNKNOWN for recovered model invocation {}",
          claimed.invocation().id(),
          failure);
    }
  }

  private void failExecutionSetup(ClaimedModelInvocation claimed, RuntimeException failure) {
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.INVALID_REQUEST,
            "cannot resolve model execution resource: "
                + message(failure, "unknown setup failure"));
    try {
      transactions.completeFailure(
          claimed, error, claimed.invocation().lastActivityAt(), clock.instant());
    } catch (RuntimeException terminalFailure) {
      log.warn(
          "cannot persist model execution setup failure for {}",
          claimed.invocation().id(),
          terminalFailure);
    }
  }

  private void dispatchClaimed(
      ClaimedModelInvocation claimed, ModelExecutionResource resource, boolean deferExternalStart) {
    Execution execution = new Execution(claimed, resource);
    Execution existing = executions.putIfAbsent(claimed.invocation().id(), execution);
    if (existing != null) {
      existing.abandon();
      ModelInvocationError error =
          new ModelInvocationError(
              ProviderErrorKind.TRANSIENT,
              "a conflicting local model execution retained this invocation handle");
      try {
        transactions.completeUnknown(
            claimed, error, claimed.invocation().lastActivityAt(), clock.instant());
      } catch (RuntimeException failure) {
        log.warn("cannot persist conflicting local model execution", failure);
      }
      return;
    }
    if (!deferExternalStart) {
      execution.start();
      return;
    }
    try {
      scheduler.execute(execution::start);
    } catch (RejectedExecutionException rejection) {
      execution.onError(
          new ProviderException(
              ProviderErrorKind.TRANSIENT,
              "cannot submit model execution to worker scheduler",
              rejection));
    }
  }

  private void appendDelta(
      ModelInvocation invocation, ProviderStreamEvent event, long sequence, Instant createdAt) {
    try {
      realtimeEventSink.append(
          new RealtimeEvent.ModelDelta(
              invocation.threadId(),
              invocation.id(),
              invocation.attempt(),
              sequence,
              event,
              createdAt));
    } catch (RuntimeException failure) {
      log.warn(
          "realtime model delta projection failed for invocation {}", invocation.id(), failure);
    }
  }

  private static String message(Throwable failure, String fallback) {
    if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
      return fallback;
    }
    return failure.getMessage();
  }

  private final class Execution implements ModelExecutionListener {
    private final ClaimedModelInvocation claimed;
    private final ModelExecutionResource resource;
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

    private Execution(ClaimedModelInvocation claimed, ModelExecutionResource resource) {
      this.claimed = claimed;
      this.resource = resource;
      this.lastObservedActivityAt = claimed.invocation().lastActivityAt();
    }

    private void start() {
      if (!clock.instant().isBefore(claimed.invocation().deadlineAt())) {
        onError(
            new ProviderException(
                ProviderErrorKind.TRANSIENT, "model execution deadline exceeded"));
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
      FenceDecision decision = FenceDecision.SKIP;
      try {
        ProviderException timeout;
        SafeStreamSnapshot pendingSnapshot;
        synchronized (this) {
          if (terminal.get()) {
            return;
          }
          Instant activityAt = clock.instant();
          timeout = timeoutAt(activityAt);
          if (timeout == null) {
            long sequence = nextSequence(lastPublishedSequence);
            streamAccumulator.append(delta);
            pendingSnapshot = streamAccumulator.snapshotSafe(sequence);
            lastObservedActivityAt = activityAt;
            pendingActivityAt = activityAt;
            if (!scheduleActivityFlushLocked()) {
              return;
            }
            if (!scheduleIdleTimeoutLocked()) {
              return;
            }
            Instant persistAt = activityAt;
            ModelInvocationUpdateOutcome outcome =
                transactions.recordSafeStreamSnapshot(
                    claimed, pendingSnapshot, persistAt, clock.instant());
            if (outcome != ModelInvocationUpdateOutcome.APPLIED) {
              // Fenced snapshot write lost ownership of the row — do NOT publish this delta.
              // The adapter has already returned, so we exit the synchronized block and run
              // abandon() from the finally clause below; that keeps the Execution monitor
              // unlocked before we touch the Provider handle or remove ourselves from the
              // executions map. Subsequent Provider callbacks will observe terminal == true
              // and be dropped without being projected as additional deltas.
              log.warn(
                  "safe stream snapshot fenced out for invocation {}", claimed.invocation().id());
              decision = FenceDecision.ABANDON;
              return;
            }
            lastPublishedSequence = sequence;
            lastSafeSequence = sequence;
            decision = FenceDecision.PUBLISH_DELTA;
            appendDelta(claimed.invocation(), delta, sequence, activityAt);
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
        // 离开 synchronized 块后再调用可能回调 Provider/transport 的 abandon，避免重入同一 Execution 的 monitor。
        if (decision == FenceDecision.ABANDON) {
          abandon();
        }
      }
    }

    /**
     * {@code onDelta} 在 synchronized 块内只能决定是否 abandon；具体清理延迟到块外以避免重入 monitor 与持有 Thread 锁的
     * transport cancel 调用发生死锁。{@link #SKIP} 表示 timeout/error 已接管或该 delta 不带可发布内容； {@link
     * #PUBLISH_DELTA} 表示 snapshot fence 成功，已 SSE publish；{@link #ABANDON} 表示 fence LOST， 必须本地
     * abandon。
     */
    private enum FenceDecision {
      SKIP,
      PUBLISH_DELTA,
      ABANDON
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
        // 在 finalize 之前先把 final response 内的 text/thinking 写入 snapshot，确保 stop 与
        // terminal/apply 并发时也能读到完整 SSE 累积。
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
                "final safe stream snapshot fenced out for invocation {}",
                claimed.invocation().id());
          } else {
            lastSafeSequence = finalSequence;
          }
        }
        if (transactions.completeSuccess(
                claimed, response, lastObservedActivityAt(), clock.instant())
            == ModelInvocationUpdateOutcome.APPLIED) {
          for (SequencedDelta gap : sequencedFinalGaps) {
            lastPublishedSequence = gap.sequence();
            appendDelta(claimed.invocation(), gap.event(), gap.sequence(), completedAt);
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
      if (error.kind() == ProviderErrorKind.TRANSIENT) {
        scheduleRetryOrFail(error);
        return;
      }
      if (error.kind() == ProviderErrorKind.CANCELLED) {
        persistCancelled();
        return;
      }
      persistFinalFailure(error);
    }

    private void scheduleRetryOrFail(ProviderException error) {
      InvocationRetryPolicy policy;
      try {
        policy = Objects.requireNonNull(retryPolicyResolver.resolve(), "retry policy");
      } catch (RuntimeException failure) {
        persistFinalFailure(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                "cannot resolve invocation retry policy: "
                    + message(failure, "unknown policy failure"),
                failure));
        return;
      }
      int retryOrdinal = claimed.invocation().attempt();
      Instant now = clock.instant();
      if (policy.allowsRetry(retryOrdinal)) {
        Instant retryAt = now.plus(policy.delayBeforeRetry(retryOrdinal));
        if (retryAt.isBefore(claimed.invocation().deadlineAt())) {
          try {
            transactions.scheduleRetry(claimed, retryAt, lastObservedActivityAt(), now);
          } catch (RuntimeException failure) {
            log.warn("cannot persist model retry for {}", claimed.invocation().id(), failure);
          } finally {
            finishLocal(true);
          }
          return;
        }
      }
      persistFinalFailure(error);
    }

    private void persistFinalFailure(ProviderException error) {
      ModelInvocationError snapshot =
          new ModelInvocationError(error.kind(), message(error, "model provider failed"));
      try {
        transactions.completeFailure(claimed, snapshot, lastObservedActivityAt(), clock.instant());
      } catch (RuntimeException failure) {
        log.warn("cannot persist model failure for {}", claimed.invocation().id(), failure);
      } finally {
        finishLocal(true);
      }
    }

    private void persistCancelled() {
      try {
        transactions.completeCancelled(claimed, lastObservedActivityAt(), clock.instant());
      } catch (RuntimeException failure) {
        log.warn("cannot persist model cancellation for {}", claimed.invocation().id(), failure);
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
              scheduler.schedule(
                  this::idleExpired, delayMillisUntil(idleAt), TimeUnit.MILLISECONDS);
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
              this::flushActivity,
              config.activityFlushInterval().toMillis(),
              "model activity flush");
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
        Instant idleAt =
            lastObservedActivityAt.plus(resource.timeoutPolicy().modelCallIdleTimeout());
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

    private void abandon() {
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
      executions.remove(claimed.invocation().id(), this);
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
            "cannot cancel local model execution handle for {}",
            claimed.invocation().id(),
            failure);
      }
    }

    private static void cancel(ScheduledFuture<?> future) {
      if (future != null) {
        future.cancel(false);
      }
    }
  }

  private record SequencedDelta(ProviderStreamEvent event, long sequence) {
    private SequencedDelta {
      event = Objects.requireNonNull(event, "event");
      if (sequence <= 0) {
        throw new IllegalArgumentException("sequence must be positive");
      }
    }
  }

  private static final class ModelStreamAccumulator {
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<Integer, PartialToolCall> partialToolCalls = new HashMap<>();

    private void append(ProviderStreamEvent event) {
      Objects.requireNonNull(event, "event");
      if (event instanceof ProviderStreamEvent.TextDelta delta) {
        text.append(delta.text());
      } else if (event instanceof ProviderStreamEvent.ThinkingDelta delta) {
        thinking.append(delta.text());
      } else if (event instanceof ProviderStreamEvent.ToolCallDelta delta) {
        partialToolCalls
            .computeIfAbsent(delta.index(), ignored -> new PartialToolCall())
            .append(delta);
      }
    }

    /** 返回当前已累积的 text + thinking 安全快照；tool-call fragment 永远不参与。 */
    private SafeStreamSnapshot snapshotSafe(long sequence) {
      return new SafeStreamSnapshot(text.toString(), thinking.toString(), sequence);
    }

    private Completion complete(ProviderResponse response) {
      List<ProviderStreamEvent> gaps = new ArrayList<>();
      appendTextGap(gaps, text, response.text());
      appendThinkingGap(gaps, thinking, response.thinking());
      if (partialToolCalls.keySet().stream()
          .anyMatch(index -> index >= response.toolCalls().size())) {
        throw new IllegalArgumentException("final response omits a streamed tool call");
      }
      for (int index = 0; index < response.toolCalls().size(); index++) {
        ProviderToolCall complete = response.toolCalls().get(index);
        ProviderStreamEvent.ToolCallDelta gap =
            partialToolCalls
                .computeIfAbsent(index, ignored -> new PartialToolCall())
                .gap(index, complete);
        if (gap != null) {
          gaps.add(gap);
        }
      }
      ProviderResponse durableResponse = response;
      if (response.thinking().isEmpty() && thinking.length() > 0) {
        durableResponse = withThinking(response, thinking.toString());
      }
      return new Completion(durableResponse, List.copyOf(gaps));
    }

    private static boolean appendThinkingGap(
        List<ProviderStreamEvent> gaps, StringBuilder received, String complete) {
      String finalValue = complete == null ? "" : complete;
      String partial = received.toString();
      if (finalValue.isEmpty()) {
        return false;
      }
      if (!finalValue.startsWith(partial)) {
        throw new IllegalArgumentException("final response conflicts with streamed thinking");
      }
      if (finalValue.length() <= received.length()) {
        return false;
      }
      String gap = finalValue.substring(received.length());
      received.append(gap);
      gaps.add(new ProviderStreamEvent.ThinkingDelta(gap));
      return true;
    }

    private static ProviderResponse withThinking(ProviderResponse response, String thinking) {
      return new ProviderResponse(
          response.text(),
          thinking,
          response.toolCalls(),
          response.stopReason(),
          response.usage(),
          response.cost(),
          response.requestId(),
          response.serviceTier(),
          response.rawUsageJson());
    }

    private static void appendTextGap(
        List<ProviderStreamEvent> gaps, StringBuilder received, String complete) {
      String finalValue = complete == null ? "" : complete;
      String partial = received.toString();
      if (!finalValue.startsWith(partial)) {
        throw new IllegalArgumentException("final response conflicts with streamed text");
      }
      if (finalValue.length() <= received.length()) {
        return;
      }
      String gap = finalValue.substring(received.length());
      received.append(gap);
      gaps.add(new ProviderStreamEvent.TextDelta(gap));
    }

    /** Holds the effective final response and the trailing SSE gaps to publish. */
    static final class Completion {
      private final ProviderResponse response;
      private final List<ProviderStreamEvent> gaps;

      Completion(ProviderResponse response, List<ProviderStreamEvent> gaps) {
        this.response = response;
        this.gaps = gaps;
      }

      ProviderResponse response() {
        return response;
      }

      List<ProviderStreamEvent> gaps() {
        return gaps;
      }
    }
  }

  private static final class PartialToolCall {
    private final StringBuilder id = new StringBuilder();
    private final StringBuilder name = new StringBuilder();
    private final StringBuilder arguments = new StringBuilder();

    private void append(ProviderStreamEvent.ToolCallDelta delta) {
      appendIdentity(id, delta.id());
      appendIdentity(name, delta.name());
      if (delta.argumentsJson() != null) {
        arguments.append(delta.argumentsJson());
      }
    }

    private ProviderStreamEvent.ToolCallDelta gap(int index, ProviderToolCall complete) {
      String idGap = gap(id, complete.id());
      String nameGap = gap(name, complete.name());
      String argumentsGap = gap(arguments, complete.argumentsJson());
      if (idGap == null && nameGap == null && argumentsGap == null) {
        return null;
      }
      return new ProviderStreamEvent.ToolCallDelta(index, idGap, nameGap, argumentsGap);
    }

    private static void appendIdentity(StringBuilder target, String value) {
      if (value == null || value.isBlank()) {
        return;
      }
      if (target.length() == 0) {
        target.append(value);
        return;
      }
      String current = target.toString();
      if (value.equals(current) || current.startsWith(value)) {
        return;
      }
      if (value.startsWith(current)) {
        target.append(value, current.length(), value.length());
        return;
      }
      throw new IllegalArgumentException(
          "streamed tool call identity conflicts: " + current + " vs " + value);
    }

    private static String gap(StringBuilder received, String complete) {
      String partial = received.toString();
      if (!complete.startsWith(partial)) {
        throw new IllegalArgumentException("final tool call conflicts with streamed data");
      }
      if (complete.length() == partial.length()) {
        return null;
      }
      String gap = complete.substring(partial.length());
      received.append(gap);
      return gap;
    }
  }
}
