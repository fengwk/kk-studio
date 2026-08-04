package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * In-flight lifecycle of one claimed Tool invocation: listener callbacks, watchdog scheduling,
 * partial-result batching, and the four terminal convergence paths.
 *
 * <p>Instances are created and owned by {@link ToolWorker} for the duration of one Tool execution.
 * Each instance:
 *
 * <ul>
 *   <li>Implements {@link ToolExecutionListener} so the {@code Tool} can deliver {@link
 *       ToolExecutionListener#onPartial onPartial}, {@link ToolExecutionListener#onComplete
 *       onComplete}, and {@link ToolExecutionListener#onError onError} callbacks.
 *   <li>Arms three {@link ScheduledExecutorService scheduler} tasks (heartbeat, deadline, partial
 *       flush) on {@link #schedule() schedule} and cancels them when terminal ownership is acquired
 *       or the callback is locally fenced.
 *   <li>Releases its slot in the owner's process-local map through the {@code ownerRelease}
 *       callback (a {@link Consumer Consumer&lt;ExecutionCallback&gt;} that conditionally removes
 *       this exact instance) on terminal convergence.
 * </ul>
 *
 * <p>Watcher correctness: a heartbeat {@link ToolInvocationUpdateOutcome#LOST_OWNERSHIP
 * LOST_OWNERSHIP} cancels the {@link ToolExecutionHandle} and forces terminal convergence; a
 * deadline on an ENVIRONMENT route converges to {@code UNKNOWN} because the remote side effect may
 * have run, while a PLATFORM deadline converges to {@code FAILED + TIMEOUT}. {@link
 * RemoteToolSendUncertainException} is never retried — the durable row converges to {@code UNKNOWN}
 * whether raised synchronously from the {@code Tool.execute} call or asynchronously through {@code
 * onError}.
 *
 * <p>A local {@code ACTIVE -> TERMINATING -> TERMINAL} gate gives exactly one callback ownership of
 * terminal convergence before it can drain partials, schedule a retry, or issue a terminal
 * transaction. Acquiring {@code TERMINATING} immediately cancels all scheduler futures. A normal
 * scheduled partial flush only runs while {@code ACTIVE}; the terminal owner alone can drain an
 * already-buffered partial batch while {@code TERMINATING}.
 */
@Slf4j
final class ExecutionCallback implements ToolExecutionListener {

  private final ClaimedToolInvocation claimed;
  private final ToolBinding binding;
  private final ToolCall call;
  private final ToolInvocationTransactions transactions;
  private final InvocationRetryPolicyResolver retryPolicyResolver;
  private final TerminalCompleter terminalCompleter;
  private final Consumer<RealtimeEvent> realtimeEventSink;
  private final ToolWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Consumer<ExecutionCallback> ownerRelease;

  private final List<ToolResult> pending = new ArrayList<>();
  private ToolExecutionHandle handle;
  private Lifecycle lifecycle = Lifecycle.ACTIVE;
  private boolean ownerReleased;
  private boolean cancelHandleOnAttach;
  private int pendingBytes;
  private Instant lastObservedActivityAt;
  private ScheduledFuture<?> heartbeat;
  private ScheduledFuture<?> timeout;
  private ScheduledFuture<?> partialFlush;

  ExecutionCallback(
      ClaimedToolInvocation claimed,
      ToolBinding binding,
      ToolCall call,
      ToolInvocationTransactions transactions,
      InvocationRetryPolicyResolver retryPolicyResolver,
      TerminalCompleter terminalCompleter,
      Consumer<RealtimeEvent> realtimeEventSink,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Consumer<ExecutionCallback> ownerRelease) {
    this.claimed = Objects.requireNonNull(claimed, "claimed");
    this.binding = Objects.requireNonNull(binding, "binding");
    this.call = Objects.requireNonNull(call, "call");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.terminalCompleter = Objects.requireNonNull(terminalCompleter, "terminalCompleter");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.ownerRelease = Objects.requireNonNull(ownerRelease, "ownerRelease");
    this.lastObservedActivityAt = claimed.invocation().lastActivityAt();
  }

  /** Arm the heartbeat / deadline / partial-flush watchdogs for this invocation. */
  synchronized void schedule() {
    if (lifecycle != Lifecycle.ACTIVE) {
      return;
    }
    heartbeat =
        scheduler.scheduleAtFixedRate(
            this::heartbeat,
            config.heartbeatInterval().toMillis(),
            config.heartbeatInterval().toMillis(),
            TimeUnit.MILLISECONDS);
    timeout =
        scheduler.schedule(
            this::timeout,
            Math.max(
                0, Duration.between(clock.instant(), claimed.invocation().deadlineAt()).toMillis()),
            TimeUnit.MILLISECONDS);
    partialFlush =
        scheduler.scheduleAtFixedRate(
            this::flushScheduled,
            config.partialFlushInterval().toMillis(),
            config.partialFlushInterval().toMillis(),
            TimeUnit.MILLISECONDS);
  }

  /** Attach the synchronous {@link ToolExecutionHandle} once the {@code Tool} has returned it. */
  synchronized void setHandle(ToolExecutionHandle value) {
    handle = Objects.requireNonNull(value, "tool execution handle");
    if (cancelHandleOnAttach) {
      handle.cancel();
    }
  }

  /** Inspect the route this callback is bound to. */
  ToolBinding binding() {
    return binding;
  }

  /** Emergency local fence for shutdown, ownership loss, and synchronous dispatch failures. */
  void forceTerminal() {
    synchronized (this) {
      lifecycle = Lifecycle.TERMINAL;
      cancelHandleOnAttach = true;
    }
    cancelSchedulers();
    cancelHandle();
    releaseOwner();
  }

  /**
   * Process-local stop: the worker is shutting down and no longer wants this callback's callbacks.
   * Idempotent.
   */
  void abandon() {
    forceTerminal();
  }

  /**
   * Fences a process-local handle after lease recovery / conflict. Late callbacks become no-ops;
   * durable state is owned by the recovering claim path.
   */
  void abandonStaleLocal() {
    forceTerminal();
  }

  @Override
  public void onPartial(ToolResult partial) {
    for (ToolContent content : partial.contents()) {
      if (content instanceof BinaryToolContent || content instanceof ArtifactToolContent) {
        error(new IllegalArgumentException("PARTIAL result must not contain artifact content"));
        return;
      }
    }
    boolean flushImmediately;
    synchronized (this) {
      if (lifecycle != Lifecycle.ACTIVE) {
        return;
      }
      pending.add(partial);
      pendingBytes += ToolResultJsonCodec.encode(partial).length();
      flushImmediately = pendingBytes >= config.partialBatchBytes();
    }
    if (flushImmediately) {
      flushScheduled();
    }
  }

  @Override
  public void onComplete(ToolResult result) {
    completeSuccessResult(result);
  }

  @Override
  public void onError(Throwable error) {
    if (error instanceof RemoteToolCancelledException) {
      completeCancelledResult();
      return;
    }
    if (error instanceof RemoteToolSendUncertainException uncertain) {
      // Async disconnect / uncertain delivery: never fail or retry; converge to UNKNOWN.
      completeUnknownResult(
          "REMOTE_UNCERTAIN",
          failureMessage(
              uncertain, "Remote tool outcome is uncertain; side effect result is unknown."));
      return;
    }
    error(error);
  }

  /** Converges a synchronous dispatch uncertainty through the same terminal gate as callbacks. */
  void completeUnknown(String kind, String message) {
    completeUnknownResult(kind, message);
  }

  private void error(Throwable error) {
    String message =
        error == null || error.getMessage() == null ? "Tool execution failed." : error.getMessage();
    completeFailureOrRetry("EXECUTION_FAILED", message);
  }

  private void timeout() {
    if (binding.type() == ToolType.ENVIRONMENT) {
      // Remote deadline: side effects may have run; converge conservatively to UNKNOWN.
      completeUnknownResult(
          "LEASE_EXPIRED", "Tool deadline elapsed; remote side effect result is unknown.");
      return;
    }
    completeFailureOrRetry("TIMEOUT", "Tool execution deadline exceeded.");
  }

  private void heartbeat() {
    RuntimeException failure = null;
    boolean ownershipLost = false;
    synchronized (this) {
      if (lifecycle != Lifecycle.ACTIVE) {
        return;
      }
      try {
        if (transactions.renew(claimed, config.leaseDuration(), clock.instant())
            != ToolInvocationUpdateOutcome.APPLIED) {
          lifecycle = Lifecycle.TERMINAL;
          ownershipLost = true;
        }
      } catch (RuntimeException error) {
        lifecycle = Lifecycle.TERMINAL;
        failure = error;
      }
    }
    if (ownershipLost || failure != null) {
      forceTerminal();
      if (failure != null) {
        log.warn("Tool heartbeat failed; fenced local execution", failure);
      }
    }
  }

  /**
   * Regular partial batch flush. This path is only legal in {@link Lifecycle#ACTIVE}; it holds the
   * lifecycle monitor through {@code recordActivity} so a concurrent terminal owner cannot begin
   * after the activity write has lost ownership.
   */
  private void flushScheduled() {
    List<ToolResult> batch;
    Instant activityAt;
    RuntimeException failure = null;
    boolean ownershipLost = false;
    synchronized (this) {
      if (lifecycle != Lifecycle.ACTIVE || pending.isEmpty()) {
        return;
      }
      batch = List.copyOf(pending);
      pending.clear();
      pendingBytes = 0;
      activityAt = clock.instant();
      try {
        if (transactions.recordActivity(claimed, activityAt, activityAt)
            != ToolInvocationUpdateOutcome.APPLIED) {
          lifecycle = Lifecycle.TERMINAL;
          ownershipLost = true;
        } else {
          lastObservedActivityAt = activityAt;
        }
      } catch (RuntimeException error) {
        lifecycle = Lifecycle.TERMINAL;
        failure = error;
      }
    }
    if (ownershipLost || failure != null) {
      forceTerminal();
      if (failure != null) {
        log.warn("Tool partial activity persistence failed; fenced local execution", failure);
      }
      return;
    }
    publishPartials(batch, activityAt);
  }

  private void publishPartials(List<ToolResult> batch, Instant activityAt) {
    for (ToolResult partial : batch) {
      try {
        realtimeEventSink.accept(
            new RealtimeEvent.ToolPartial(
                claimed.invocation().threadId(),
                claimed.invocation().id(),
                claimed.invocation().attempt(),
                partial,
                activityAt));
      } catch (RuntimeException error) {
        log.warn("Tool realtime projection failed", error);
      }
    }
  }

  private void completeSuccessResult(ToolResult result) {
    if (!tryBeginTerminal()) {
      return;
    }
    boolean terminalPersisted = false;
    try {
      if (!flushPendingForTerminal()) {
        return;
      }
      terminalPersisted =
          terminalCompleter.completeSuccess(
              claimed, binding, call, result, lastObservedActivityAt());
    } catch (RuntimeException error) {
      // Terminal persistence threw; fail closed by writing a deterministic terminal failure.
      try {
        terminalCompleter.completeFailure(
            claimed,
            new ToolInvocationError(
                "RESULT_PERSISTENCE_FAILED",
                failureMessage(error, "Tool result persistence failed.")),
            lastObservedActivityAt());
      } catch (RuntimeException suppressed) {
        error.addSuppressed(suppressed);
      }
    } finally {
      if (!terminalPersisted) {
        cancelHandle();
      }
      finishTerminal();
    }
  }

  private void completeCancelledResult() {
    if (!tryBeginTerminal()) {
      return;
    }
    try {
      if (flushPendingForTerminal()) {
        terminalCompleter.completeCancelled(claimed, lastObservedActivityAt());
      }
    } finally {
      cancelHandle();
      finishTerminal();
    }
  }

  private void completeUnknownResult(String kind, String message) {
    if (!tryBeginTerminal()) {
      return;
    }
    try {
      if (flushPendingForTerminal()) {
        terminalCompleter.completeUnknown(
            claimed, new ToolInvocationError(kind, message), lastObservedActivityAt());
      }
    } finally {
      cancelHandle();
      finishTerminal();
    }
  }

  private void completeFailureOrRetry(String kind, String message) {
    if (!tryBeginTerminal()) {
      return;
    }
    try {
      if (!flushPendingForTerminal()) {
        return;
      }
      InvocationRetryPolicy policy = retryPolicyResolver.resolve();
      int retryOrdinal = claimed.invocation().attempt();
      Instant now = clock.instant();
      Instant nextAttemptAt = now.plus(policy.delayBeforeRetry(retryOrdinal));
      boolean retryable =
          claimed.invocation().descriptor().sideEffect() == ToolSideEffect.IDEMPOTENT
              && policy.allowsRetry(retryOrdinal)
              && nextAttemptAt.isBefore(claimed.invocation().deadlineAt());
      if (retryable
          && transactions.scheduleRetry(claimed, nextAttemptAt, lastObservedActivityAt(), now)
              == ToolInvocationUpdateOutcome.APPLIED) {
        // A retry re-arms the durable activation; the row is no longer RUNNING here, so the local
        // handle must be torn down deterministically to free the environment slot.
        return;
      }
      terminalCompleter.completeFailure(
          claimed, new ToolInvocationError(kind, message), lastObservedActivityAt());
    } finally {
      cancelHandle();
      finishTerminal();
    }
  }

  /**
   * Atomically acquires the sole terminal-convergence ownership. Scheduler cancellation follows
   * immediately after publishing {@code TERMINATING}; a tick that wins the small scheduling race
   * sees a non-active lifecycle and becomes a no-op before issuing durable work.
   */
  private boolean tryBeginTerminal() {
    synchronized (this) {
      if (lifecycle != Lifecycle.ACTIVE) {
        return false;
      }
      lifecycle = Lifecycle.TERMINATING;
    }
    cancelSchedulers();
    return true;
  }

  /**
   * Drains partials for the terminal owner. Unlike {@link #flushScheduled()}, this is allowed in
   * {@code TERMINATING} so output received before terminal ownership is neither lost nor recorded
   * more than once.
   *
   * @return {@code false} when activity persistence lost ownership or failed, in which case no
   *     terminal transaction may be forged
   */
  private boolean flushPendingForTerminal() {
    List<ToolResult> batch;
    Instant activityAt;
    synchronized (this) {
      if (lifecycle != Lifecycle.TERMINATING) {
        return false;
      }
      if (pending.isEmpty()) {
        return true;
      }
      batch = List.copyOf(pending);
      pending.clear();
      pendingBytes = 0;
      activityAt = clock.instant();
    }
    try {
      if (transactions.recordActivity(claimed, activityAt, activityAt)
          != ToolInvocationUpdateOutcome.APPLIED) {
        forceTerminal();
        return false;
      }
    } catch (RuntimeException error) {
      forceTerminal();
      log.warn("Tool terminal partial activity persistence failed; fenced local execution", error);
      return false;
    }
    synchronized (this) {
      if (lifecycle != Lifecycle.TERMINATING) {
        return false;
      }
      lastObservedActivityAt = activityAt;
    }
    publishPartials(batch, activityAt);
    return true;
  }

  private synchronized Instant lastObservedActivityAt() {
    return lastObservedActivityAt;
  }

  /** Completes a terminal owner's cleanup even when its durable mutation threw. */
  private void finishTerminal() {
    synchronized (this) {
      lifecycle = Lifecycle.TERMINAL;
    }
    cancelSchedulers();
    releaseOwner();
  }

  private void releaseOwner() {
    synchronized (this) {
      if (ownerReleased) {
        return;
      }
      ownerReleased = true;
    }
    ownerRelease.accept(this);
  }

  /** Synchronously request the underlying {@link ToolExecutionHandle} to cancel. */
  synchronized void cancelHandle() {
    cancelHandleOnAttach = true;
    if (handle != null && !handle.isCancelled()) {
      handle.cancel();
    }
  }

  /** Cancel the watchdog scheduler tasks. Does not touch the {@link ToolExecutionHandle}. */
  synchronized void cancelSchedulers() {
    if (heartbeat != null) {
      heartbeat.cancel(false);
    }
    if (timeout != null) {
      timeout.cancel(false);
    }
    if (partialFlush != null) {
      partialFlush.cancel(false);
    }
  }

  private static String failureMessage(Throwable error, String fallback) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }

  private enum Lifecycle {
    ACTIVE,
    TERMINATING,
    TERMINAL
  }
}
