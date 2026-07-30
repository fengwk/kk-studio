package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallContext;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteTool;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Location-agnostic durable Tool worker driven by the single harness_execution_target queue.
 *
 * <p>Process-local gate: each in-flight handle is keyed by invocation id and reports active
 * execution for both PLATFORM and ENVIRONMENT via {@link #hasActiveExecution()} / {@link
 * #hasActiveExecution(String)}. Durable dispatch is owned by the ExecutionTarget dispatcher; this
 * worker is invoked when a {@link
 * fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind#TOOL_INVOCATION} target has
 * already been locked due by the worker transaction.
 *
 * <p>Threading: {@link #dispatch(long)} retains the single public entry and runs the durable {@code
 * transactions.claim(...)} on the caller (the future PostgreSQL execution-target dispatcher
 * thread). All post-claim external Tool execution ({@code dispatchClaimed}, including {@link
 * fun.fengwk.kkstudio.harness.tool.execution.Tool#execute} / remote send) is handed off to a
 * dedicated injected {@link Executor}; watchdogs (heartbeat / deadline / partial flush) remain on
 * the {@link ScheduledExecutorService scheduler} so that the caller never blocks on external Tool
 * I/O after a successful claim.
 *
 * <p>Pending-dispatch fence: each submitted post-claim Runnable carries a per-invocation ticket
 * registered just before executor submission. The Runnable atomically removes its own ticket as the
 * gate to entering {@code dispatchClaimed} — if the conditional removal succeeds, the ticket was
 * current and execution is admitted; if it fails, the ticket was either cleared by {@link #stop()}
 * or superseded by a later claim for the same invocation id and the Runnable returns without
 * invoking external Tool I/O. Tickets persist only until execution admission; admitted tasks
 * release their fence immediately so the transient map cannot leak per invocation. The fence is
 * purely process-local — it is not durable state, a queue, a route slot, or a retry mechanism.
 */
@Slf4j
public final class ToolWorker {
  private final ToolInvocationTransactions transactions;
  private final ToolRegistry registry;
  private final RemoteToolTransport remoteTransport;
  private final ToolInterceptorChain interceptorChain;
  private final ArtifactStore artifactStore;
  private final InvocationRetryPolicyResolver retryPolicyResolver;
  private final RealtimeEventSink realtimeEventSink;
  private final ToolWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor executor;
  private final Supplier<String> workerTokenSupplier;
  private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();
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
      ArtifactStore artifactStore,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor executor,
      Supplier<String> workerTokenSupplier) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.remoteTransport = Objects.requireNonNull(remoteTransport, "remoteTransport");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.workerTokenSupplier = Objects.requireNonNull(workerTokenSupplier, "workerTokenSupplier");
  }

  /**
   * Process a single durable ToolInvocation dispatch. The caller (the execution-target dispatcher
   * thread) runs the durable {@code transactions.claim(...)} synchronously; post-claim external
   * Tool execution is handed off to the injected {@link Executor} so the caller never blocks on
   * Tool I/O after a successful claim.
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
    if (!workerToken.equals(ownership.invocation().workerLease().token())) {
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
   * Conservatively durably converges a successful claim whose post-claim execution was rejected by
   * the executor (typically a closed / shutting-down executor). No external Tool execution
   * occurred, so the only safe durable action is to release the unstarted lease back to the queue;
   * existing target/lease recovery (heartbeat, deadline, re-dispatch) then re-routes the work
   * without ever leaving it stranded as RUNNING.
   */
  private void handleSubmissionRejection(
      ClaimedToolInvocation ownership, RejectedExecutionException rejected) {
    log.error(
        "Tool dispatch executor rejected submission for invocation {}; releasing unstarted claim",
        ownership.invocation().id(),
        rejected);
    InvocationStatus previousStatus = ownership.previousStatus();
    if (previousStatus != InvocationStatus.QUEUED
        && previousStatus != InvocationStatus.RETRY_WAIT) {
      // Invariant breach: a successful claim should always carry QUEUED/RETRY_WAIT as the
      // pre-flip status. Do not attempt a second terminal mutation with unknown ownership; the
      // existing heartbeat/deadline/re-dispatch recovery will fence and re-route the row.
      log.error(
          "Cannot release unstarted tool invocation {} after executor rejection: unexpected previous status {}",
          ownership.invocation().id(),
          previousStatus);
      return;
    }
    try {
      releaseUnstarted(ownership, clock.instant());
    } catch (RuntimeException releaseError) {
      // Do not attempt a second terminal mutation with potentially unknown ownership; the existing
      // heartbeat/deadline/re-dispatch recovery will fence and re-route the row once the lease
      // expires or the durable target becomes due again.
      log.error(
          "Failed to release unstarted tool invocation {} after executor rejection; relying on lease/target recovery",
          ownership.invocation().id(),
          releaseError);
    }
  }

  /** Process-local gate: any in-flight handle. */
  public boolean hasActiveExecution() {
    return !executions.isEmpty();
  }

  /**
   * Process-local gate: an in-flight handle for the given ENVIRONMENT name. PLATFORM handles are
   * matched by location instead.
   */
  public boolean hasActiveExecution(String environmentName) {
    if (environmentName == null || environmentName.isBlank()) {
      return false;
    }
    return executions.values().stream()
        .anyMatch(
            execution ->
                execution.binding.location() == ToolExecutionLocation.ENVIRONMENT
                    && environmentName.equals(execution.binding.environmentName()));
  }

  /**
   * Stops process-local handles without changing durable invocation state. Invalidate pending
   * dispatch tickets before abandoning active executions so any Runnable already submitted to the
   * executor but not yet running becomes a no-op instead of invoking external Tool I/O.
   */
  public void stop() {
    pendingTickets.clear();
    executions.values().forEach(Execution::abandon);
  }

  private void dispatchClaimed(ClaimedToolInvocation claimed) {
    ToolInvocation invocation = claimed.invocation();
    if (claimed.recoveredLease()) {
      abandonStaleLocalExecution(invocation.id());
      completeUnknown(
          claimed,
          new ToolInvocationError(
              "LEASE_EXPIRED", "Tool ownership was lost; execution result is unknown."));
      return;
    }
    if (!clock.instant().isBefore(invocation.deadlineAt())) {
      fail(claimed, "Tool execution deadline exceeded.");
      return;
    }
    ToolBinding binding = bindingFor(invocation);
    ResolvedTool resolved = resolveTool(binding, invocation);
    if (resolved.failureMessage() != null) {
      fail(claimed, resolved.failureMessage());
      return;
    }
    Tool tool = resolved.tool();
    ToolCall call =
        new ToolCall(invocation.toolCallId(), invocation.toolName(), invocation.argumentsJson());
    Execution execution = new Execution(claimed, binding, call);
    Execution previous = executions.putIfAbsent(invocation.id(), execution);
    if (previous != null) {
      // A process-local handle already exists for this invocation id (typically a stale handle
      // after lease loss / double dispatch). Abandon and fence the stale handle so late callbacks
      // cannot mutate durable state. Converge the newly claimed ownership to UNKNOWN without
      // re-executing.
      previous.abandonStaleLocal();
      completeUnknown(
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
          tool.execute(
              new ToolExecutionRequest(
                  tool.descriptor(),
                  call,
                  Duration.between(clock.instant(), invocation.deadlineAt()),
                  new ToolExecutionContext(invocation.id(), invocation.threadId())),
              execution);
      execution.setHandle(handle);
    } catch (RemoteToolUnavailableException unavailable) {
      execution.markTerminalLocal();
      try {
        releaseUnstarted(claimed, clock.instant());
      } finally {
        executions.remove(invocation.id(), execution);
        execution.cancelSchedulersOnly();
      }
    } catch (RemoteToolSendUncertainException uncertain) {
      // Synchronous send uncertainty: side effects may have begun; converge immediately to UNKNOWN.
      execution.markTerminalLocal();
      try {
        completeUnknown(
            claimed,
            new ToolInvocationError(
                "REMOTE_UNCERTAIN",
                failureMessage(
                    uncertain, "Remote tool send outcome is uncertain; result is unknown.")),
            claimed.invocation().lastActivityAt());
      } finally {
        executions.remove(invocation.id(), execution);
        execution.cancelSchedulersOnly();
      }
    } catch (RuntimeException error) {
      try {
        execution.error(error);
      } finally {
        // error() stops and clears on success; if it aborted early, still free the slot.
        if (!executions.containsKey(invocation.id())) {
          executions.remove(invocation.id(), execution);
          execution.cancelSchedulersOnly();
        }
      }
    }
  }

  private ToolBinding bindingFor(ToolInvocation invocation) {
    if (invocation.location() == ToolExecutionLocation.ENVIRONMENT) {
      return ToolBinding.of(invocation.descriptor(), invocation.environmentName());
    }
    return ToolBinding.of(invocation.descriptor());
  }

  private ResolvedTool resolveTool(ToolBinding binding, ToolInvocation invocation) {
    if (binding.location() == ToolExecutionLocation.PLATFORM) {
      Optional<Tool> resolved = registry.find(invocation.toolName(), invocation.toolVersion());
      if (resolved.isEmpty() || !descriptorMatches(invocation, resolved.get())) {
        return ResolvedTool.failure(
            "Frozen tool "
                + invocation.toolName()
                + "@"
                + invocation.toolVersion()
                + " is unavailable.");
      }
      return ResolvedTool.success(resolved.get());
    }
    Tool remote =
        new RemoteTool(invocation.descriptor(), binding.environmentName(), remoteTransport);
    return ResolvedTool.success(remote);
  }

  private boolean descriptorMatches(ToolInvocation invocation, Tool tool) {
    return tool.descriptor().equals(invocation.descriptor());
  }

  private void fail(ClaimedToolInvocation claimed, String message) {
    completeFailure(claimed, new ToolInvocationError("EXECUTION_FAILED", message));
  }

  private boolean completeSuccess(
      ClaimedToolInvocation claimed,
      ToolBinding binding,
      ToolCall call,
      ToolResult result,
      Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    ToolInvocationUpdateOutcome outcome;
    try {
      outcome =
          transactions.completeSuccess(
              claimed,
              () -> prepareTerminalResult(claimed, binding, call, result),
              lastObservedActivityAt,
              now);
    } catch (TerminalResultPreparationException error) {
      return completeFailure(
          claimed, new ToolInvocationError(error.kind, error.getMessage()), lastObservedActivityAt);
    } catch (RuntimeException error) {
      return completeFailure(
          claimed,
          new ToolInvocationError(
              "RESULT_PERSISTENCE_FAILED",
              failureMessage(error, "Tool result persistence failed.")),
          lastObservedActivityAt);
    }
    return outcome == ToolInvocationUpdateOutcome.APPLIED;
  }

  private ToolResult prepareTerminalResult(
      ClaimedToolInvocation claimed, ToolBinding binding, ToolCall call, ToolResult result) {
    ToolResult intercepted;
    try {
      intercepted =
          interceptorChain.after(
              new AfterToolCallContext(claimed.invocation().id(), binding, call, result));
    } catch (RuntimeException error) {
      throw new TerminalResultPreparationException(
          "AFTER_INTERCEPTOR_FAILED", afterInterceptorFailure(error), error);
    }
    try {
      return externalize(intercepted);
    } catch (RuntimeException error) {
      throw new TerminalResultPreparationException(
          "RESULT_PERSISTENCE_FAILED",
          failureMessage(error, "Tool result persistence failed."),
          error);
    }
  }

  private boolean completeFailure(ClaimedToolInvocation claimed, ToolInvocationError error) {
    return completeFailure(claimed, error, claimed.invocation().lastActivityAt());
  }

  private boolean completeFailure(
      ClaimedToolInvocation claimed, ToolInvocationError error, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    return transactions.completeFailure(claimed, error, lastObservedActivityAt, now)
        == ToolInvocationUpdateOutcome.APPLIED;
  }

  private boolean completeCancelled(ClaimedToolInvocation claimed, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    return transactions.completeCancelled(claimed, lastObservedActivityAt, now)
        == ToolInvocationUpdateOutcome.APPLIED;
  }

  private boolean completeUnknown(ClaimedToolInvocation claimed, ToolInvocationError error) {
    return completeUnknown(claimed, error, claimed.invocation().lastActivityAt());
  }

  private boolean completeUnknown(
      ClaimedToolInvocation claimed, ToolInvocationError error, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    return transactions.completeUnknown(claimed, error, lastObservedActivityAt, now)
        == ToolInvocationUpdateOutcome.APPLIED;
  }

  private void releaseUnstarted(ClaimedToolInvocation claimed, Instant now) {
    InvocationStatus previousStatus = claimed.previousStatus();
    Instant nextAttemptAt = null;
    if (previousStatus == InvocationStatus.RETRY_WAIT) {
      nextAttemptAt = now.plus(config.unavailableRetryDelay());
      if (!nextAttemptAt.isBefore(claimed.invocation().deadlineAt())) {
        completeFailure(
            claimed,
            new ToolInvocationError(
                "EXECUTION_FAILED", "Environment was unavailable before the Tool deadline."));
        return;
      }
    } else if (previousStatus != InvocationStatus.QUEUED) {
      throw new IllegalStateException(
          "unstarted release requires a QUEUED or RETRY_WAIT claim, but was " + previousStatus);
    }
    transactions.releaseUnstarted(claimed, nextAttemptAt, now);
  }

  private void abandonStaleLocalExecution(long invocationId) {
    Execution stale = executions.get(invocationId);
    if (stale != null) {
      stale.abandonStaleLocal();
    }
  }

  private String nextWorkerToken() {
    String token = workerTokenSupplier.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("workerTokenSupplier returned a blank token");
    }
    return token;
  }

  private ToolResult externalize(ToolResult result) {
    List<ToolContent> contents = new ArrayList<>();
    for (ToolContent content : result.contents()) {
      if (content instanceof BinaryToolContent binary) {
        byte[] bytes = binary.content();
        ArtifactRef artifact = artifactStore.save(binary.mediaType(), "identity", bytes);
        contents.add(new ArtifactToolContent(artifact));
        continue;
      }
      if (content instanceof ArtifactToolContent) {
        contents.add(content);
        continue;
      }
      byte[] bytes = bytes(content);
      if (bytes.length <= config.inlineResultBytes()) {
        contents.add(content);
        continue;
      }
      String mediaType = content instanceof JsonToolContent ? "application/json" : "text/plain";
      // The terminal transaction invokes this method only after validating and locking ownership.
      ArtifactRef artifact = artifactStore.save(mediaType, "utf-8", bytes);
      contents.add(new TextToolContent(preview(bytes)));
      contents.add(new ArtifactToolContent(artifact));
    }
    if (contents.isEmpty()) {
      contents.add(new TextToolContent(""));
    }
    return new ToolResult(
        result.toolCallId(), contents, result.error(), result.detailsJson(), false);
  }

  private byte[] bytes(ToolContent content) {
    if (content instanceof TextToolContent text) {
      return text.text().getBytes(StandardCharsets.UTF_8);
    }
    if (content instanceof JsonToolContent json) {
      return json.json().getBytes(StandardCharsets.UTF_8);
    }
    return new byte[0];
  }

  private String preview(byte[] bytes) {
    if (bytes.length <= config.previewBytes()) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    String value = new String(bytes, StandardCharsets.UTF_8);
    StringBuilder prefix = new StringBuilder();
    int previewBytes = 0;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      int codePointBytes =
          new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
      if (previewBytes + codePointBytes > config.previewBytes()) {
        break;
      }
      prefix.appendCodePoint(codePoint);
      previewBytes += codePointBytes;
      offset += Character.charCount(codePoint);
    }
    return prefix + "\n[full output stored as artifact]";
  }

  private final class Execution implements ToolExecutionListener {
    private final ClaimedToolInvocation claimed;
    private final ToolBinding binding;
    private final ToolCall call;
    private final List<ToolResult> pending = new ArrayList<>();
    private ToolExecutionHandle handle;
    private boolean terminal;
    private boolean cancelHandleOnAttach;
    private int pendingBytes;
    private Instant lastObservedActivityAt;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> timeout;
    private ScheduledFuture<?> partialFlush;

    private Execution(ClaimedToolInvocation claimed, ToolBinding binding, ToolCall call) {
      this.claimed = claimed;
      this.binding = binding;
      this.call = call;
      this.lastObservedActivityAt = claimed.invocation().lastActivityAt();
    }

    private synchronized void schedule() {
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
                  0,
                  Duration.between(clock.instant(), claimed.invocation().deadlineAt()).toMillis()),
              TimeUnit.MILLISECONDS);
      partialFlush =
          scheduler.scheduleAtFixedRate(
              this::flush,
              config.partialFlushInterval().toMillis(),
              config.partialFlushInterval().toMillis(),
              TimeUnit.MILLISECONDS);
    }

    private synchronized void setHandle(ToolExecutionHandle value) {
      handle = Objects.requireNonNull(value, "tool execution handle");
      if (cancelHandleOnAttach) {
        handle.cancel();
      }
    }

    private synchronized void markTerminalLocal() {
      terminal = true;
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
          error == null || error.getMessage() == null
              ? "Tool execution failed."
              : error.getMessage();
      completeFailureOrRetry("EXECUTION_FAILED", message);
    }

    private void timeout() {
      if (binding.location() == ToolExecutionLocation.ENVIRONMENT) {
        // Remote deadline: side effects may have run; converge conservatively to UNKNOWN.
        completeUnknownResult(
            "LEASE_EXPIRED", "Tool deadline elapsed; remote side effect result is unknown.");
        return;
      }
      completeFailureOrRetry("TIMEOUT", "Tool execution deadline exceeded.");
    }

    private void heartbeat() {
      if (transactions.renew(claimed, config.leaseDuration(), clock.instant())
          != ToolInvocationUpdateOutcome.APPLIED) {
        cancelHandle();
        stop();
      }
    }

    private void flush() {
      List<ToolResult> batch;
      synchronized (this) {
        if (pending.isEmpty()) {
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
        stop();
        return;
      }
      lastObservedActivityAt = activityAt;
      for (ToolResult partial : batch) {
        try {
          realtimeEventSink.append(
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
      synchronized (this) {
        if (terminal) {
          return;
        }
        terminal = true;
      }
      boolean terminalPersisted = false;
      try {
        flush();
        terminalPersisted = completeSuccess(claimed, binding, call, result, lastObservedActivityAt);
      } finally {
        if (!terminalPersisted) {
          cancelHandle();
        }
        stop();
      }
    }

    private void completeCancelledResult(String message) {
      synchronized (this) {
        if (terminal) {
          return;
        }
        terminal = true;
      }
      try {
        flush();
        completeCancelled(claimed, lastObservedActivityAt);
      } finally {
        cancelHandle();
        stop();
      }
    }

    private void completeUnknownResult(String kind, String message) {
      synchronized (this) {
        if (terminal) {
          return;
        }
        terminal = true;
      }
      try {
        flush();
        completeUnknown(claimed, new ToolInvocationError(kind, message), lastObservedActivityAt);
      } finally {
        cancelHandle();
        stop();
      }
    }

    private void completeFailureOrRetry(String kind, String message) {
      synchronized (this) {
        if (terminal) {
          return;
        }
        terminal = true;
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
          return;
        }
        completeFailure(claimed, new ToolInvocationError(kind, message), lastObservedActivityAt);
      } finally {
        cancelHandle();
        stop();
      }
    }

    private void abandon() {
      synchronized (this) {
        if (terminal) {
          return;
        }
        terminal = true;
      }
      cancelHandle();
      stop();
    }

    /**
     * Fences a process-local handle after lease recovery/conflict. Late callbacks become no-ops;
     * durable state is owned by the recovering claim path.
     */
    private void abandonStaleLocal() {
      synchronized (this) {
        if (terminal) {
          executions.remove(claimed.invocation().id(), this);
          return;
        }
        terminal = true;
      }
      cancelHandle();
      cancelSchedulersOnly();
      executions.remove(claimed.invocation().id(), this);
    }

    private synchronized void cancelHandle() {
      cancelHandleOnAttach = true;
      if (handle != null && !handle.isCancelled()) {
        handle.cancel();
      }
    }

    private synchronized void cancelSchedulersOnly() {
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

    private synchronized void stop() {
      cancelSchedulersOnly();
      executions.remove(claimed.invocation().id(), this);
    }
  }

  private static String failureMessage(Throwable error, String fallback) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }

  private static String afterInterceptorFailure(RuntimeException error) {
    return failureMessage(error, "afterToolCall interceptor failed.");
  }

  private static final class TerminalResultPreparationException extends RuntimeException {
    private final String kind;

    private TerminalResultPreparationException(String kind, String message, Throwable cause) {
      super(message, cause);
      this.kind = kind;
    }
  }

  private record ResolvedTool(Tool tool, String failureMessage) {
    static ResolvedTool success(Tool tool) {
      return new ResolvedTool(Objects.requireNonNull(tool, "tool"), null);
    }

    static ResolvedTool failure(String message) {
      return new ResolvedTool(null, Objects.requireNonNull(message, "message"));
    }
  }
}
