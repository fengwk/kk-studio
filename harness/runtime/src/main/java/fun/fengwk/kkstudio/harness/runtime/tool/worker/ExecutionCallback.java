package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
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
 *       flush) on {@link #schedule() schedule} and cancels them on terminal convergence via {@link
 *       #forceTerminal()}.
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
 * <p>Every terminal path routes through {@link #forceTerminal()} so that exactly one of them sets
 * the terminal flag, cancels all three scheduler futures, and conditionally removes this callback
 * from the owner's map. The terminal flag is also re-checked at the top of every scheduler task so
 * a scheduler tick that races the terminal release becomes a no-op.
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
  private boolean terminal;
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
            this::flush,
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

  /**
   * Mark the callback as terminal before any external {@link Tool} handle is acquired. Used when
   * the synchronous send raises {@link
   * fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException} or {@link
   * RemoteToolSendUncertainException}: the calling worker must not run any further callbacks on the
   * returned handle.
   */
  synchronized void markTerminalLocal() {
    terminal = true;
  }

  /** Whether the callback has already converged to a terminal state. */
  synchronized boolean isTerminal() {
    return terminal;
  }

  /** Inspect the route this callback is bound to. */
  ToolBinding binding() {
    return binding;
  }

  /**
   * The single terminal-convergence primitive used by every terminal path. Marks the callback
   * terminal (idempotent), cancels all three scheduler futures, and conditionally removes this
   * callback from the owner's process-local map. Safe to call from any thread; the first caller
   * does the work and any concurrent caller observes {@code terminal == true} on entry and returns
   * immediately.
   */
  void forceTerminal() {
    synchronized (this) {
      if (terminal) {
        return;
      }
      terminal = true;
    }
    cancelSchedulers();
    ownerRelease.accept(this);
  }

  /**
   * Process-local stop: the worker is shutting down and no longer wants this callback's callbacks.
   * Idempotent. Preserves the original semantics of cancelling the underlying handle before tearing
   * down schedulers and the owner slot.
   */
  void abandon() {
    cancelHandle();
    forceTerminal();
  }

  /**
   * Fences a process-local handle after lease recovery / conflict. Late callbacks become no-ops;
   * durable state is owned by the recovering claim path.
   */
  void abandonStaleLocal() {
    synchronized (this) {
      if (terminal) {
        ownerRelease.accept(this);
        return;
      }
      terminal = true;
    }
    cancelHandle();
    cancelSchedulers();
    ownerRelease.accept(this);
  }

  @Override
  public void onPartial(ToolResult partial) {
    for (ToolContent content : partial.contents()) {
      if (content instanceof BinaryToolContent || content instanceof ArtifactToolContent) {
        error(new IllegalArgumentException("PARTIAL result must not contain artifact content"));
        return;
      }
    }
    synchronized (this) {
      if (terminal) {
        return;
      }
      pending.add(partial);
      pendingBytes += ToolResultJsonCodec.encode(partial).length();
      if (pendingBytes >= config.partialBatchBytes()) {
        flush();
      }
    }
  }

  @Override
  public void onComplete(ToolResult result) {
    completeSuccessResult(result);
  }

  @Override
  public void onError(Throwable error) {
    if (error instanceof RemoteToolCancelledException cancelled) {
      completeCancelledResult(cancelled.getMessage());
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

  private void error(Throwable error) {
    String message =
        error == null || error.getMessage() == null ? "Tool execution failed." : error.getMessage();
    completeFailureOrRetry("EXECUTION_FAILED", message);
  }

  private void timeout() {
    if (isTerminal()) {
      return;
    }
    if (binding.location() == ToolExecutionLocation.ENVIRONMENT) {
      // Remote deadline: side effects may have run; converge conservatively to UNKNOWN.
      completeUnknownResult(
          "LEASE_EXPIRED", "Tool deadline elapsed; remote side effect result is unknown.");
      return;
    }
    completeFailureOrRetry("TIMEOUT", "Tool execution deadline exceeded.");
  }

  private void heartbeat() {
    if (isTerminal()) {
      return;
    }
    if (transactions.renew(claimed, config.leaseDuration(), clock.instant())
        != ToolInvocationUpdateOutcome.APPLIED) {
      cancelHandle();
      forceTerminal();
    }
  }

  private void flush() {
    List<ToolResult> batch;
    synchronized (this) {
      if (terminal || pending.isEmpty()) {
        return;
      }
      batch = List.copyOf(pending);
      pending.clear();
      pendingBytes = 0;
    }
    Instant activityAt = clock.instant();
    if (transactions.recordActivity(claimed, activityAt, activityAt)
        != ToolInvocationUpdateOutcome.APPLIED) {
      cancelHandle();
      forceTerminal();
      return;
    }
    lastObservedActivityAt = activityAt;
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
    if (isTerminal()) {
      return;
    }
    boolean terminalPersisted;
    try {
      flush();
      terminalPersisted =
          terminalCompleter.completeSuccess(claimed, binding, call, result, lastObservedActivityAt);
    } catch (RuntimeException error) {
      // Terminal persistence threw; fail closed by writing a deterministic terminal failure.
      terminalPersisted = false;
      try {
        terminalCompleter.completeFailure(
            claimed,
            new ToolInvocationError(
                "RESULT_PERSISTENCE_FAILED",
                failureMessage(error, "Tool result persistence failed.")),
            lastObservedActivityAt);
      } catch (RuntimeException suppressed) {
        error.addSuppressed(suppressed);
      }
    }
    if (!terminalPersisted) {
      cancelHandle();
    }
    forceTerminal();
  }

  private void completeCancelledResult(String message) {
    if (isTerminal()) {
      return;
    }
    try {
      flush();
      terminalCompleter.completeCancelled(claimed, lastObservedActivityAt);
    } finally {
      cancelHandle();
      forceTerminal();
    }
  }

  private void completeUnknownResult(String kind, String message) {
    if (isTerminal()) {
      return;
    }
    try {
      flush();
      terminalCompleter.completeUnknown(
          claimed, new ToolInvocationError(kind, message), lastObservedActivityAt);
    } finally {
      cancelHandle();
      forceTerminal();
    }
  }

  private void completeFailureOrRetry(String kind, String message) {
    if (isTerminal()) {
      return;
    }
    try {
      flush();
      InvocationRetryPolicy policy = retryPolicyResolver.resolve();
      int retryOrdinal = claimed.invocation().attempt();
      Instant now = clock.instant();
      Instant nextAttemptAt = now.plus(policy.delayBeforeRetry(retryOrdinal));
      boolean retryable =
          claimed.invocation().descriptor().sideEffect() == ToolSideEffect.IDEMPOTENT
              && policy.allowsRetry(retryOrdinal)
              && nextAttemptAt.isBefore(claimed.invocation().deadlineAt());
      if (retryable
          && transactions.scheduleRetry(claimed, nextAttemptAt, lastObservedActivityAt, now)
              == ToolInvocationUpdateOutcome.APPLIED) {
        // A retry re-arms the durable target; the row is no longer RUNNING here, so the local
        // handle must be torn down deterministically to free the environment slot.
        forceTerminal();
        return;
      }
      terminalCompleter.completeFailure(
          claimed, new ToolInvocationError(kind, message), lastObservedActivityAt);
    } finally {
      cancelHandle();
      forceTerminal();
    }
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
}
