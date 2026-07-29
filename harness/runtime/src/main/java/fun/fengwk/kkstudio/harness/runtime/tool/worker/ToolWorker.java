package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Location-agnostic durable Tool worker.
 *
 * <p>Owns claim/lease/heartbeat/timeout/partial/retry/terminal/UNKNOWN/artifact/after-hook/wake
 * semantics for both PLATFORM and ENVIRONMENT. Local tools resolve from {@link ToolRegistry};
 * ENVIRONMENT tools resolve to transport-backed {@link RemoteTool}.
 */
@Slf4j
public final class ToolWorker {
  private static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(5);

  private final ToolInvocationTransactions transactions;
  private final ToolRegistry registry;
  private final RemoteToolTransport remoteTransport;
  private final ToolInterceptorChain interceptorChain;
  private final ArtifactStore artifactStore;
  private final InvocationRetryPolicyResolver retryPolicyResolver;
  private final RealtimeEventSink realtimeEventSink;
  private final ActivationNotifier activationNotifier;
  private final ToolWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Supplier<String> workerTokenSupplier;
  private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Long> environmentActiveInvocations =
      new ConcurrentHashMap<>();

  public ToolWorker(
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      RemoteToolTransport remoteTransport,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ActivationNotifier activationNotifier,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Supplier<String> workerTokenSupplier) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.remoteTransport = Objects.requireNonNull(remoteTransport, "remoteTransport");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.activationNotifier = Objects.requireNonNull(activationNotifier, "activationNotifier");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.workerTokenSupplier = Objects.requireNonNull(workerTokenSupplier, "workerTokenSupplier");
  }

  /** Processes one signal for a specified durable invocation of any location. */
  public boolean dispatch(long invocationId) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    return transactions
        .findClaimable(invocationId, clock.instant())
        .map(this::claimAndDispatch)
        .orElse(false);
  }

  /**
   * Claims and dispatches at most one due invocation for the given location/environment.
   *
   * <p>ENVIRONMENT requires a non-blank environmentName and enforces one active execution per
   * environment.
   */
  public boolean dispatchNext(ToolExecutionLocation location, String environmentName) {
    Objects.requireNonNull(location, "location");
    if (location == ToolExecutionLocation.ENVIRONMENT) {
      if (environmentName == null || environmentName.isBlank()) {
        throw new IllegalArgumentException("ENVIRONMENT dispatch requires environmentName");
      }
      if (environmentActiveInvocations.containsKey(environmentName)) {
        return false;
      }
    } else if (location == ToolExecutionLocation.PLATFORM && hasActivePlatformExecution()) {
      return false;
    }
    return transactions
        .findNextClaimable(location, environmentName, clock.instant())
        .map(this::claimAndDispatch)
        .orElse(false);
  }

  /** Process-local gate: any in-flight handle. */
  public boolean hasActiveExecution() {
    return !executions.isEmpty();
  }

  public boolean hasActiveExecution(String environmentName) {
    return environmentName != null && environmentActiveInvocations.containsKey(environmentName);
  }

  /** Stops process-local handles without changing durable invocation state. */
  public void stop() {
    executions.values().forEach(Execution::abandon);
  }

  private boolean hasActivePlatformExecution() {
    return executions.values().stream()
        .anyMatch(execution -> execution.binding.location() == ToolExecutionLocation.PLATFORM);
  }

  private boolean claimAndDispatch(ToolInvocation candidate) {
    boolean environmentSlotHeld = false;
    if (candidate.location() == ToolExecutionLocation.ENVIRONMENT) {
      String environmentName = candidate.environmentName();
      Long existing = environmentActiveInvocations.putIfAbsent(environmentName, candidate.id());
      if (existing != null) {
        return false;
      }
      environmentSlotHeld = true;
    }
    try {
      String token = nextWorkerToken();
      Duration executionTimeout =
          candidate.descriptor().timeout().isZero()
              ? DEFAULT_EXECUTION_TIMEOUT
              : candidate.descriptor().timeout();
      Optional<ClaimedToolInvocation> claimed =
          transactions.claim(
              candidate.id(), token, executionTimeout, config.leaseDuration(), clock.instant());
      if (claimed.isEmpty()) {
        return false;
      }
      ClaimedToolInvocation ownership = claimed.orElseThrow();
      if (!ownership.invocation().workerLease().token().equals(token)) {
        throw new IllegalStateException("claim returned an unexpected worker token");
      }
      dispatchClaimed(ownership, candidate.status());
      return true;
    } finally {
      // Exceptional paths that never registered an Execution must release the environment slot.
      if (environmentSlotHeld
          && !executions.containsKey(candidate.id())
          && Objects.equals(
              environmentActiveInvocations.get(candidate.environmentName()), candidate.id())) {
        clearEnvironmentSlot(candidate);
      }
    }
  }

  private void dispatchClaimed(ClaimedToolInvocation claimed, InvocationStatus previousStatus) {
    ToolInvocation invocation = claimed.invocation();
    if (claimed.recoveredLease()) {
      abandonStaleLocalExecution(invocation.id());
      completeUnknown(
          claimed,
          new ToolInvocationError(
              "LEASE_EXPIRED", "Tool ownership was lost; execution result is unknown."));
      clearEnvironmentSlot(invocation);
      return;
    }
    if (!clock.instant().isBefore(invocation.deadlineAt())) {
      fail(claimed, "Tool execution deadline exceeded.");
      clearEnvironmentSlot(invocation);
      return;
    }
    ToolBinding binding = bindingFor(invocation);
    ResolvedTool resolved = resolveTool(binding, invocation);
    if (resolved.failureMessage() != null) {
      fail(claimed, resolved.failureMessage());
      clearEnvironmentSlot(invocation);
      return;
    }
    Tool tool = resolved.tool();
    ToolCall call =
        new ToolCall(invocation.toolCallId(), invocation.toolName(), invocation.argumentsJson());
    Execution execution = new Execution(claimed, binding, call, previousStatus);
    Execution previous = executions.putIfAbsent(invocation.id(), execution);
    if (previous != null) {
      // A process-local handle already exists for this invocation id (typically a stale handle
      // after
      // lease loss / double dispatch). Abandon and fence the stale handle so late callbacks cannot
      // mutate durable state. Converge the newly claimed ownership to UNKNOWN without re-executing.
      // abandonStaleLocal clears the environment slot only for this invocation id; a different
      // surviving execution id keeps its own slot.
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
        releaseUnstarted(claimed, previousStatus, clock.instant());
      } finally {
        clearEnvironmentSlot(invocation);
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
        clearEnvironmentSlot(invocation);
        executions.remove(invocation.id(), execution);
        execution.cancelSchedulersOnly();
      }
    } catch (RuntimeException error) {
      try {
        execution.error(error);
      } finally {
        // error() stops and clears on success; if it aborted early, still free the slot.
        if (!executions.containsKey(invocation.id())) {
          clearEnvironmentSlot(invocation);
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
    if (outcome != ToolInvocationUpdateOutcome.APPLIED) {
      return false;
    }
    notifyThread(claimed);
    return true;
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
    if (transactions.completeFailure(claimed, error, lastObservedActivityAt, now)
        != ToolInvocationUpdateOutcome.APPLIED) {
      return false;
    }
    notifyThread(claimed);
    return true;
  }

  private boolean completeCancelled(ClaimedToolInvocation claimed, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    if (transactions.completeCancelled(claimed, lastObservedActivityAt, now)
        != ToolInvocationUpdateOutcome.APPLIED) {
      return false;
    }
    notifyThread(claimed);
    return true;
  }

  private boolean completeUnknown(ClaimedToolInvocation claimed, ToolInvocationError error) {
    return completeUnknown(claimed, error, claimed.invocation().lastActivityAt());
  }

  private boolean completeUnknown(
      ClaimedToolInvocation claimed, ToolInvocationError error, Instant lastObservedActivityAt) {
    Instant now = clock.instant();
    if (transactions.completeUnknown(claimed, error, lastObservedActivityAt, now)
        != ToolInvocationUpdateOutcome.APPLIED) {
      return false;
    }
    notifyThread(claimed);
    return true;
  }

  private void releaseUnstarted(
      ClaimedToolInvocation claimed, InvocationStatus previousStatus, Instant now) {
    Instant nextAttemptAt = null;
    InvocationStatus releaseTo =
        previousStatus == InvocationStatus.RETRY_WAIT
            ? InvocationStatus.RETRY_WAIT
            : InvocationStatus.QUEUED;
    if (releaseTo == InvocationStatus.RETRY_WAIT) {
      nextAttemptAt = now.plus(config.unavailableRetryDelay());
      if (!nextAttemptAt.isBefore(claimed.invocation().deadlineAt())) {
        completeFailure(
            claimed,
            new ToolInvocationError(
                "EXECUTION_FAILED", "Environment was unavailable before the Tool deadline."));
        return;
      }
    }
    if (transactions.releaseUnstarted(claimed, releaseTo, nextAttemptAt, now)
        == ToolInvocationUpdateOutcome.APPLIED) {
      if (releaseTo == InvocationStatus.RETRY_WAIT) {
        scheduleRetrySignal(
            claimed.invocation().id(), Objects.requireNonNull(nextAttemptAt, "nextAttemptAt"));
      }
    }
  }

  private void notifyThread(ClaimedToolInvocation claimed) {
    notifyTarget(new ExecutionTarget(ExecutionTargetKind.THREAD, claimed.invocation().threadId()));
  }

  private void notifyTarget(ExecutionTarget target) {
    try {
      activationNotifier.notifyAfterCommit(target);
    } catch (RuntimeException error) {
      log.warn("Tool activation notification failed", error);
    }
  }

  private void scheduleRetrySignal(long invocationId, Instant retryAt) {
    long delayMillis = Math.max(1L, Duration.between(clock.instant(), retryAt).toMillis() + 1L);
    try {
      scheduler.schedule(
          () ->
              notifyTarget(new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, invocationId)),
          delayMillis,
          TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException error) {
      log.warn("cannot schedule tool retry signal for {}", invocationId, error);
    }
  }

  private void clearEnvironmentSlot(ToolInvocation invocation) {
    if (invocation.location() == ToolExecutionLocation.ENVIRONMENT
        && invocation.environmentName() != null) {
      environmentActiveInvocations.remove(invocation.environmentName(), invocation.id());
    }
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
    private final InvocationStatus previousStatus;
    private final List<ToolResult> pending = new ArrayList<>();
    private ToolExecutionHandle handle;
    private boolean terminal;
    private boolean cancelHandleOnAttach;
    private int pendingBytes;
    private Instant lastObservedActivityAt;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> timeout;
    private ScheduledFuture<?> partialFlush;

    private Execution(
        ClaimedToolInvocation claimed,
        ToolBinding binding,
        ToolCall call,
        InvocationStatus previousStatus) {
      this.claimed = claimed;
      this.binding = binding;
      this.call = call;
      this.previousStatus = previousStatus;
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
          scheduleRetrySignal(claimed.invocation().id(), nextAttemptAt);
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
      clearEnvironmentSlot(claimed.invocation());
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
      clearEnvironmentSlot(claimed.invocation());
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
