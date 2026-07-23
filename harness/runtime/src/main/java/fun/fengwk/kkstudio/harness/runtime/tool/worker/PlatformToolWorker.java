package fun.fengwk.kkstudio.harness.runtime.tool.worker;

import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ToolCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.tool.AfterToolCallContext;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorChain;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
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

/**
 * Asynchronously dispatches at most one durable Platform invocation per poll. No worker thread
 * waits for callbacks: callback ownership is validated by the transaction port before every write.
 */
public final class PlatformToolWorker {
  private final ToolInvocationWorkerStore store;
  private final ToolInvocationTransactions transactions;
  private final ToolRegistry registry;
  private final ToolInterceptorChain interceptorChain;
  private final ArtifactStore artifactStore;
  private final ToolWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final HarnessLifecycleObservers lifecycleObservers;
  private final ConcurrentHashMap<Long, Execution> executions = new ConcurrentHashMap<>();

  public PlatformToolWorker(
      ToolInvocationWorkerStore store,
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this(
        store,
        transactions,
        registry,
        interceptorChain,
        artifactStore,
        config,
        clock,
        scheduler,
        new HarnessLifecycleObservers(List.of()));
  }

  public PlatformToolWorker(
      ToolInvocationWorkerStore store,
      ToolInvocationTransactions transactions,
      ToolRegistry registry,
      ToolInterceptorChain interceptorChain,
      ArtifactStore artifactStore,
      ToolWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      HarnessLifecycleObservers lifecycleObservers) {
    this.store = Objects.requireNonNull(store, "store");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.lifecycleObservers = Objects.requireNonNull(lifecycleObservers, "lifecycleObservers");
  }

  /**
   * Dispatches one due invocation without awaiting it. Prefer {@link #dispatchDueForThread} for
   * event-triggered ThreadProcessor paths; this global claim remains for recovery/tests.
   */
  public Optional<ClaimedToolInvocation> executeNext(String workerId) {
    requireNonBlank(workerId, "workerId");
    Instant now = clock.instant();
    Optional<ClaimedToolInvocation> claimed = store.claimDue(workerId, now, config.leaseDuration());
    claimed.ifPresent(this::dispatch);
    return claimed;
  }

  /**
   * Event-triggered path: claim and dispatch all currently due Platform invocations for one thread
   * (non-blocking callbacks).
   */
  public int dispatchDueForThread(String workerId, long threadId) {
    requireNonBlank(workerId, "workerId");
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    int dispatched = 0;
    Instant now = clock.instant();
    while (true) {
      Optional<ClaimedToolInvocation> claimed =
          store.claimDueForThread(workerId, threadId, now, config.leaseDuration());
      if (claimed.isEmpty()) {
        break;
      }
      dispatch(claimed.orElseThrow());
      dispatched++;
      now = clock.instant();
    }
    return dispatched;
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

  private void dispatch(ClaimedToolInvocation claimed) {
    ToolInvocation invocation = claimed.invocation();
    if (!supportedLocation(invocation)) {
      fail(claimed, "Tool worker cannot execute location " + invocation.location() + ".");
      return;
    }
    // A reclaimed non-idempotent call may already have produced an external side effect. UNKNOWN
    // must win over cancellation, deadline, and current registry drift checks.
    if (claimed.recoveredLease() && invocation.sideEffect() == ToolSideEffect.NON_IDEMPOTENT) {
      terminate(
          claimed,
          ToolInvocationStatus.UNKNOWN,
          ToolResult.error(
              invocation.toolCallId(), "Tool ownership was lost; side effect result is unknown."),
          "non-idempotent invocation lease expired");
      return;
    }
    if (invocation.cancelRequestedAt() != null
        || invocation.status() == ToolInvocationStatus.CANCEL_REQUESTED) {
      terminate(
          claimed,
          ToolInvocationStatus.CANCELLED,
          ToolResult.error(invocation.toolCallId(), "Tool execution cancelled."),
          "Tool execution cancelled.");
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
    if (!transactions.start(claimed, clock.instant())) {
      return;
    }
    ToolBinding binding = ToolBinding.of(tool.descriptor());
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
    return tool.descriptor().name().equals(invocation.toolName())
        && tool.descriptor().version().equals(invocation.toolVersion())
        && tool.descriptor().executionLocation() == invocation.location()
        && tool.descriptor().sideEffect() == invocation.sideEffect();
  }

  private boolean supportedLocation(ToolInvocation invocation) {
    return invocation.location() == ToolExecutionLocation.PLATFORM;
  }

  private void fail(ClaimedToolInvocation claimed, String message) {
    terminate(
        claimed,
        ToolInvocationStatus.FAILED,
        ToolResult.error(claimed.invocation().toolCallId(), message),
        message);
  }

  private boolean terminate(
      ClaimedToolInvocation claimed,
      ToolInvocationStatus status,
      ToolResult result,
      String errorMessage) {
    Instant now = clock.instant();
    if (!transactions.terminate(claimed, status, externalize(result), errorMessage, now)) {
      return false;
    }
    // terminal CAS 已落库，先发 lifecycle observation，再尝试 coordinate；coordinate 抛错也不影响已持久观察。
    lifecycleObservers.publish(
        new ToolCompleted(
            claimed.invocation().id(), claimed.invocation().threadId(), status, errorMessage, now));
    return true;
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
      // Artifact persistence intentionally precedes the terminal ownership CAS. A lost CAS can
      // leave an unreachable artifact, but no Invocation or Session entry can reference it.
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

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
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
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> timeout;
    private ScheduledFuture<?> partialFlush;

    private Execution(ClaimedToolInvocation claimed, ToolBinding binding, ToolCall call) {
      this.claimed = claimed;
      this.binding = binding;
      this.call = call;
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
        pending.add(externalize(partial));
        pendingBytes += ToolResultJsonCodec.encode(partial).length();
        if (pendingBytes >= config.partialBatchBytes()) {
          flush();
        }
      }
    }

    @Override
    public void onComplete(ToolResult result) {
      complete(ToolInvocationStatus.SUCCEEDED, result, null);
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
      complete(
          ToolInvocationStatus.FAILED,
          ToolResult.error(claimed.invocation().toolCallId(), message),
          message);
    }

    private void timeout() {
      complete(
          ToolInvocationStatus.FAILED,
          ToolResult.error(claimed.invocation().toolCallId(), "Tool execution deadline exceeded."),
          "Tool execution deadline exceeded.");
    }

    private void heartbeat() {
      ToolInvocation current = store.find(claimed.invocation().id()).orElse(null);
      if (current == null || current.status().isTerminal()) {
        cancelHandle();
        stop();
        return;
      }
      if (current.cancelRequestedAt() != null
          || current.status() == ToolInvocationStatus.CANCEL_REQUESTED) {
        complete(
            ToolInvocationStatus.CANCELLED,
            ToolResult.error(claimed.invocation().toolCallId(), "Tool execution cancelled."),
            "Tool execution cancelled.");
        return;
      }
      if (!store.heartbeat(claimed, clock.instant(), config.leaseDuration())) {
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
      transactions.appendPartial(claimed, batch, clock.instant());
    }

    private void complete(ToolInvocationStatus status, ToolResult result, String errorMessage) {
      synchronized (this) {
        if (terminal) {
          return;
        }
        terminal = true;
      }
      boolean terminalPersisted = false;
      ToolInvocationStatus terminalStatus = status;
      ToolResult terminalResult = result;
      String terminalErrorMessage = errorMessage;
      try {
        flush();
        try {
          terminalResult =
              interceptorChain.after(
                  new AfterToolCallContext(
                      claimed.invocation().id(), binding, call, terminalResult));
        } catch (RuntimeException error) {
          terminalStatus = ToolInvocationStatus.FAILED;
          terminalErrorMessage = afterInterceptorFailure(error);
          terminalResult =
              ToolResult.error(claimed.invocation().toolCallId(), terminalErrorMessage);
        }
        terminalPersisted =
            terminate(claimed, terminalStatus, terminalResult, terminalErrorMessage);
      } finally {
        if (terminalStatus != ToolInvocationStatus.SUCCEEDED || !terminalPersisted) {
          cancelHandle();
        }
        stop();
      }
    }

    private String afterInterceptorFailure(RuntimeException error) {
      String message = error.getMessage();
      return message == null || message.isBlank() ? "afterToolCall interceptor failed." : message;
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
}
