package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.kernel.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallContext;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Asynchronously dispatches at most one durable Platform invocation per poll. No worker thread
 * waits for callbacks: callback ownership is validated by the transaction port before every write.
 */
public final class PlatformToolWorker {
  private static final System.Logger LOGGER = System.getLogger(PlatformToolWorker.class.getName());
  private static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(5);

  private final ToolInvocationTransactions transactions;
  private final ToolRegistry registry;
  private final ToolInterceptorChain interceptorChain;
  private final ArtifactStore artifactStore;
  private final InvocationRetryPolicyResolver retryPolicyResolver;
  private final RealtimeEventSink realtimeEventSink;
  private final ActivationNotifier activationNotifier;
  private final ToolWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final HarnessLifecycleObservers lifecycleObservers;
  private final Supplier<String> workerTokenSupplier;
  private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();

  public PlatformToolWorker(
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ActivationNotifier activationNotifier,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Supplier<String> workerTokenSupplier) {
    this(
        transactions,
        registry,
        interceptorChain,
        artifactStore,
        retryPolicyResolver,
        realtimeEventSink,
        activationNotifier,
        config,
        clock,
        scheduler,
        new HarnessLifecycleObservers(List.of()),
        workerTokenSupplier);
  }

  public PlatformToolWorker(
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ActivationNotifier activationNotifier,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      HarnessLifecycleObservers lifecycleObservers,
      Supplier<String> workerTokenSupplier) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.activationNotifier = Objects.requireNonNull(activationNotifier, "activationNotifier");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.lifecycleObservers = Objects.requireNonNull(lifecycleObservers, "lifecycleObservers");
    this.workerTokenSupplier = Objects.requireNonNull(workerTokenSupplier, "workerTokenSupplier");
  }

  /** Processes one signal for a specified durable PLATFORM invocation. */
  public boolean dispatch(long invocationId) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    return transactions
        .findClaimable(invocationId, clock.instant())
        .filter(invocation -> invocation.location() == ToolExecutionLocation.PLATFORM)
        .map(this::claimAndDispatch)
        .orElse(false);
  }

  /** Claims and dispatches at most one due PLATFORM invocation for recovery. */
  public boolean dispatchNext() {
    if (hasActiveExecution()) {
      return false;
    }
    return transactions
        .findNextClaimable(ToolExecutionLocation.PLATFORM, null, clock.instant())
        .map(this::claimAndDispatch)
        .orElse(false);
  }

  /**
   * Process lifecycle gate: durable polling does not claim another tool while one handle is active.
   */
  public boolean hasActiveExecution() {
    return !executions.isEmpty();
  }

  /**
   * Stops process-local handles without changing durable invocation state; lease recovery stays in
   * DB.
   */
  public void stop() {
    executions.values().forEach(Execution::abandon);
  }

  private boolean claimAndDispatch(ToolInvocation candidate) {
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
    dispatchClaimed(ownership);
    return true;
  }

  private void dispatchClaimed(ClaimedToolInvocation claimed) {
    ToolInvocation invocation = claimed.invocation();
    if (!supportedLocation(invocation)) {
      fail(claimed, "Tool worker cannot execute location " + invocation.location() + ".");
      return;
    }
    if (claimed.recoveredLease()) {
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
    Optional<Tool> resolved = registry.find(invocation.toolName(), invocation.toolVersion());
    if (resolved.isEmpty() || !descriptorMatches(invocation, resolved.get())) {
      fail(
          claimed,
          "Frozen tool "
              + invocation.toolName()
              + "@"
              + invocation.toolVersion()
              + " is unavailable.");
      return;
    }
    Tool tool = resolved.get();
    ToolBinding binding = ToolBinding.of(invocation.descriptor());
    ToolCall call =
        new ToolCall(invocation.toolCallId(), invocation.toolName(), invocation.argumentsJson());
    Execution execution = new Execution(claimed, binding, call);
    if (executions.putIfAbsent(invocation.id(), execution) != null) {
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
    } catch (RuntimeException error) {
      execution.error(error);
    }
  }

  private boolean descriptorMatches(ToolInvocation invocation, Tool tool) {
    return tool.descriptor().equals(invocation.descriptor());
  }

  private boolean supportedLocation(ToolInvocation invocation) {
    return invocation.location() == ToolExecutionLocation.PLATFORM;
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
    publishTerminal(claimed, InvocationStatus.SUCCEEDED, null, now);
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
    publishTerminal(claimed, InvocationStatus.FAILED, error.message(), now);
    return true;
  }

  private boolean completeUnknown(ClaimedToolInvocation claimed, ToolInvocationError error) {
    Instant now = clock.instant();
    if (transactions.completeUnknown(claimed, error, claimed.invocation().lastActivityAt(), now)
        != ToolInvocationUpdateOutcome.APPLIED) {
      return false;
    }
    publishTerminal(claimed, InvocationStatus.UNKNOWN, error.message(), now);
    return true;
  }

  private void publishTerminal(
      ClaimedToolInvocation claimed, InvocationStatus status, String errorMessage, Instant now) {
    lifecycleObservers.publish(
        new ToolCompleted(
            claimed.invocation().id(), claimed.invocation().threadId(), status, errorMessage, now));
    notifyTarget(new ExecutionTarget(ExecutionTargetKind.THREAD, claimed.invocation().threadId()));
  }

  private void notifyTarget(ExecutionTarget target) {
    try {
      activationNotifier.notifyAfterCommit(target);
    } catch (RuntimeException error) {
      LOGGER.log(System.Logger.Level.WARNING, "Tool activation notification failed", error);
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
      byte[] bytes = bytes(content);
      if (bytes.length <= config.inlineResultBytes() || content instanceof ArtifactToolContent) {
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

    @Override
    public void onPartial(ToolResult partial) {
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
          LOGGER.log(System.Logger.Level.WARNING, "Tool realtime projection failed", error);
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
          notifyTarget(
              new ExecutionTarget(ExecutionTargetKind.TOOL_INVOCATION, claimed.invocation().id()));
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

    private synchronized void cancelHandle() {
      cancelHandleOnAttach = true;
      if (handle != null && !handle.isCancelled()) {
        handle.cancel();
      }
    }

    private synchronized void stop() {
      if (heartbeat != null) {
        heartbeat.cancel(false);
      }
      if (timeout != null) {
        timeout.cancel(false);
      }
      if (partialFlush != null) {
        partialFlush.cancel(false);
      }
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
}
