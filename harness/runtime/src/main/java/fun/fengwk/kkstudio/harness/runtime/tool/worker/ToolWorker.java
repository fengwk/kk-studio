package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Location-agnostic durable Tool worker driven by the single harness_execution_activation queue.
 *
 * <p>The worker is the small orchestration entry point of the Tool execution lifecycle. The three
 * cohesive responsibilities are delegated to package-private collaborators:
 *
 * <ul>
 *   <li>{@link PermissionResolver} owns the durable permission transition and produces the final
 *       {@link ExecutablePlan} that must match the row.
 *   <li>{@link TerminalCompleter} owns {@code SUCCEEDED} result preparation (after interceptor +
 *       {@link ResourceStore} externalization) plus the four terminal transitions ({@code SUCCEEDED
 *       / FAILED / CANCELLED / UNKNOWN}).
 *   <li>{@link ExecutionCallback} owns in-flight {@link
 *       fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener} callbacks, the heartbeat
 *       / deadline / partial-flush watchdogs, partial-result batching, and the per-callback release
 *       of the owner's process-local slot.
 * </ul>
 *
 * <p>The worker itself keeps four process-local concerns:
 *
 * <ol>
 *   <li>The synchronous durable claim in {@link #dispatch(long)}: the caller (the future PostgreSQL
 *       ExecutionActivation dispatcher thread) runs {@code transactions.claim(...)}.
 *   <li>The post-claim hand-off to the injected {@link Executor}: the caller never blocks on
 *       external Tool I/O after a successful claim.
 *   <li>The per-invocation pending-dispatch ticket fence: the conditional-remove gate around {@code
 *       dispatchClaimed} ensures {@link #stop()} and superseding claims prevent any
 *       no-longer-current Runnable from performing external Tool I/O.
 *   <li>The process-local active-execution map: {@link #hasActiveExecution()} / {@link
 *       #hasActiveExecution(EnvironmentId)} expose the in-flight gate so the environment slot is
 *       observable until every callback has terminated.
 * </ol>
 *
 * <p>Threading: durable claim on the caller; external Tool execution on {@code executor}; watchdogs
 * on {@code scheduler}; realtime projections are best-effort. The owner's map is the sole
 * process-local gate for {@code hasActiveExecution}; the durable FIFO gate remains the PostgreSQL
 * {@code harness_execution_activation} queue.
 */
@Slf4j
public final class ToolWorker {
  private final ToolInvocationTransactions transactions;
  private final PermissionResolver permissionResolver;
  private final TerminalCompleter terminalCompleter;
  private final InvocationRetryPolicyResolver retryPolicyResolver;
  private final RealtimeEventSink realtimeEventSink;
  private final ToolWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor executor;
  private final Supplier<String> workerTokenSupplier;
  private final ConcurrentHashMap<Long, ExecutionCallback> executions = new ConcurrentHashMap<>();
  // Per-invocation id: the most recently registered ticket for a deferred dispatch that has not
  // yet been admitted into dispatchClaimed. The Runnable uses conditional remove(id, ticket) as
  // the admission gate; the entry is released on admission so the map cannot leak. stop() clears
  // the map and a later claim for the same invocation id overwrites the entry, both of which
  // cause the in-flight conditional remove to fail and the Runnable to no-op. Process-local only.
  private final ConcurrentHashMap<Long, Long> pendingTickets = new ConcurrentHashMap<>();
  private final AtomicLong ticketSeq = new AtomicLong();

  public ToolWorker(
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      RemoteToolTransport remoteTransport,
      ToolInterceptorChain interceptorChain,
      ResourceStore resourceStore,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor executor,
      Supplier<String> workerTokenSupplier,
      ToolSettingsProvider toolSettingsProvider,
      Path workdir,
      Path environmentRoot) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.workerTokenSupplier = Objects.requireNonNull(workerTokenSupplier, "workerTokenSupplier");
    Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    Objects.requireNonNull(workdir, "workdir");
    Objects.requireNonNull(environmentRoot, "environmentRoot");
    this.permissionResolver =
        new PermissionResolver(
            transactions,
            interceptorChain,
            toolSettingsProvider,
            registry,
            remoteTransport,
            clock,
            workdir,
            environmentRoot);
    this.terminalCompleter =
        new TerminalCompleter(transactions, interceptorChain, resourceStore, config, clock);
  }

  /**
   * Process a single durable ToolInvocation dispatch. The caller (the ExecutionActivation
   * dispatcher thread) runs the durable {@code transactions.claim(...)} synchronously; post-claim
   * external Tool execution is handed off to the injected {@link Executor} so the caller never
   * blocks on Tool I/O after a successful claim.
   *
   * @return {@code true} when this process successfully claimed an invocation and either submitted
   *     the local execution or durably converged a submission rejection; {@code false} only when
   *     the claim is empty (no due work to dispatch).
   */
  public boolean dispatch(long invocationId) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    String workerToken = nextWorkerToken();
    Optional<ClaimedToolInvocation> claimed =
        transactions.claim(invocationId, workerToken, config.leaseDuration(), clock.instant());
    if (claimed.isEmpty()) {
      return false;
    }
    ClaimedToolInvocation ownership = claimed.orElseThrow();
    if (ownership.invocation().workerLease() == null
        || !workerToken.equals(ownership.invocation().workerLease().token())) {
      throw new IllegalStateException("claim returned an unexpected worker token");
    }
    long ticket = ticketSeq.incrementAndGet();
    pendingTickets.put(invocationId, ticket);
    try {
      executor.execute(() -> runClaimedIfTicketCurrent(invocationId, ticket, ownership));
    } catch (RejectedExecutionException rejected) {
      // Drop our ticket before invoking the durable release logic so the rejected attempt never
      // appears as a live pending task and cannot race a later superseding claim.
      pendingTickets.remove(invocationId, ticket);
      handleSubmissionRejection(ownership, rejected);
    }
    return true;
  }

  private void runClaimedIfTicketCurrent(
      long invocationId, long ticket, ClaimedToolInvocation ownership) {
    // Atomic admission gate: conditional remove succeeds iff our ticket is still the current
    // entry. Either failure mode means this Runnable must not enter post-claim execution —
    // stop() cleared the fence, or a later claim for the same invocation id superseded this
    // ticket — and the durable recovery path will re-route the work without any external Tool
    // I/O from this process. Successful remove also releases the transient fence so the map
    // cannot leak per invocation.
    if (!pendingTickets.remove(invocationId, ticket)) {
      return;
    }
    dispatchClaimed(ownership);
  }

  /**
   * Converges a successful claim whose post-claim execution was rejected by the executor (typically
   * a closed / shutting-down executor) to a terminal failure. No external Tool execution occurred,
   * but the claim must not be requeued indefinitely.
   */
  private void handleSubmissionRejection(
      ClaimedToolInvocation ownership, RejectedExecutionException rejected) {
    log.error(
        "Tool dispatch executor rejected submission for invocation {}; releasing unstarted claim",
        ownership.invocation().id(),
        rejected);
    try {
      terminalCompleter.completeFailure(
          ownership,
          new ToolInvocationError(
              "EXECUTION_REJECTED",
              failureMessage(rejected, "Tool execution was rejected before it started.")),
          ownership.invocation().lastActivityAt());
    } catch (RuntimeException terminalError) {
      log.error(
          "Failed to terminally fail tool invocation {} after executor rejection; relying on lease recovery",
          ownership.invocation().id(),
          terminalError);
    }
  }

  /** Process-local gate: any in-flight handle. */
  public boolean hasActiveExecution() {
    return !executions.isEmpty();
  }

  /**
   * Process-local gate: an in-flight ENVIRONMENT handle for the given canonical route identity.
   * PLATFORM handles never match an Environment route.
   */
  public boolean hasActiveExecution(EnvironmentId environmentId) {
    if (environmentId == null) {
      return false;
    }
    return executions.values().stream()
        .anyMatch(
            execution ->
                execution.binding().type() == ToolType.ENVIRONMENT
                    && environmentId.value().equals(execution.binding().environmentName()));
  }

  /**
   * Stops process-local handles without changing durable invocation state. Invalidate pending
   * dispatch tickets before abandoning active executions so any Runnable already submitted to the
   * executor but not yet running becomes a no-op instead of invoking external Tool I/O.
   */
  public void stop() {
    pendingTickets.clear();
    executions.values().forEach(ExecutionCallback::abandon);
  }

  private void dispatchClaimed(ClaimedToolInvocation claimed) {
    ToolInvocation invocation = claimed.invocation();
    if (claimed.recoveredLease()) {
      ExecutionCallback stale = executions.get(invocation.id());
      if (stale != null) {
        // Fence the stale process-local handle: its callbacks must become no-ops while the
        // recovering claim path owns durable state.
        stale.abandonStaleLocal();
      }
      terminalCompleter.completeUnknown(
          claimed,
          new ToolInvocationError(
              "LEASE_EXPIRED", "Tool ownership was lost; execution result is unknown."),
          claimed.invocation().lastActivityAt());
      return;
    }
    if (!clock.instant().isBefore(invocation.deadlineAt())) {
      terminalCompleter.completeFailure(
          claimed,
          new ToolInvocationError("TIMEOUT", "Tool execution deadline exceeded."),
          claimed.invocation().lastActivityAt());
      return;
    }
    PermissionResolution resolution = permissionResolver.resolve(claimed);
    if (!(resolution instanceof PermissionResolution.Resolved resolved)) {
      // ASK / DENY / TERMINAL_FAILED: every durable mutation is already persisted by the
      // resolver; the process-local slot stays empty so the durable FIFO gate can advance.
      return;
    }
    Optional<Tool> tool =
        permissionResolver.resolveTool(
            resolved.plan().binding(), claimed.invocation().environmentId());
    if (tool.isEmpty()) {
      terminalCompleter.completeFailure(
          claimed,
          new ToolInvocationError(
              "EXECUTION_FAILED", permissionResolver.toolMissingFailure(resolved.plan().binding())),
          claimed.invocation().lastActivityAt());
      return;
    }
    ToolBinding executeBinding = resolved.plan().binding();
    ToolCall executeCall = resolved.plan().call();
    ExecutionCallback execution = newExecutionCallback(claimed, executeBinding, executeCall);
    ExecutionCallback previous = executions.putIfAbsent(invocation.id(), execution);
    if (previous != null) {
      // A process-local handle already exists for this invocation id (typically a stale handle
      // after lease loss / double dispatch). Abandon and fence the stale handle so late callbacks
      // cannot mutate durable state. Converge the newly claimed ownership to UNKNOWN without
      // re-executing.
      previous.abandonStaleLocal();
      terminalCompleter.completeUnknown(
          claimed,
          new ToolInvocationError(
              "LOCAL_CONFLICT",
              "Stale process-local tool handle was abandoned; reclaimed result is unknown."),
          claimed.invocation().lastActivityAt());
      return;
    }
    execution.schedule();
    try {
      ToolExecutionHandle handle =
          tool.get()
              .execute(
                  new ToolExecutionRequest(
                      tool.get().descriptor(),
                      executeCall,
                      Duration.between(clock.instant(), invocation.deadlineAt()),
                      new ToolExecutionContext(invocation.id(), invocation.threadId())),
                  execution);
      execution.setHandle(handle);
    } catch (RemoteToolUnavailableException unavailable) {
      // The Environment went offline after planning. This is terminal, not an unbounded retry.
      try {
        terminalCompleter.completeFailure(
            claimed,
            new ToolInvocationError(
                "ENVIRONMENT_UNAVAILABLE",
                failureMessage(unavailable, "Environment is unavailable.")),
            claimed.invocation().lastActivityAt());
      } finally {
        execution.forceTerminal();
      }
    } catch (RemoteToolSendUncertainException uncertain) {
      // Synchronous send uncertainty: side effects may have begun; converge immediately to UNKNOWN.
      try {
        execution.completeUnknown(
            "REMOTE_UNCERTAIN",
            failureMessage(uncertain, "Remote tool send outcome is uncertain; result is unknown."));
      } finally {
        execution.forceTerminal();
      }
    } catch (RuntimeException error) {
      // onError may throw before reaching a terminal path. forceTerminal is idempotent so an
      // unconditional call after onError guarantees the owner slot and all scheduler futures are
      // released no matter how onError aborts; the conditional remove inside forceTerminal avoids
      // a double remove.
      try {
        execution.onError(error);
      } finally {
        execution.forceTerminal();
      }
    }
  }

  private String nextWorkerToken() {
    String token = workerTokenSupplier.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("workerTokenSupplier returned a blank token");
    }
    return token;
  }

  private ExecutionCallback newExecutionCallback(
      ClaimedToolInvocation claimed, ToolBinding executeBinding, ToolCall executeCall) {
    long invocationId = claimed.invocation().id();
    return new ExecutionCallback(
        claimed,
        executeBinding,
        executeCall,
        transactions,
        retryPolicyResolver,
        terminalCompleter,
        realtimeEventSink::append,
        config,
        clock,
        scheduler,
        callback -> executions.remove(invocationId, callback));
  }

  private static String failureMessage(Throwable error, String fallback) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }
}
