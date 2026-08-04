package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次 claim 的进程内 Tool execution：Gateway listener 门控缓冲、durable fence 与 lease heartbeat。
 *
 * <p>listener 回调在 durable RUNNING 落地前只进缓冲区；激活（{@link #activate}）成功后按到达顺序重放。partial 先做 toolCallId /
 * content 校验（拒绝 Binary / Artifact content，partial 不可持久资源），再在校验 RUNNING + attempt 与 claim ownership
 * 的短事务中确认（无 durable mutation），commit 后才 best-effort 发布 {@link RealtimeEvent.ToolPartial}；sink
 * 失败不影响执行。terminal 回调一次生效，success 强制把 terminate 归一 false（拒绝 BinaryToolContent——Gateway 必须先外部化为稳定
 * ArtifactToolContent ref）；retryable 失败只在 sideEffect 为 READ_ONLY / IDEMPOTENT 且 retryPolicy
 * 允许时重试，NON_IDEMPOTENT 绝不自动重试。duplicate / late / stale 一律 no-op；lost ownership 立即关 gate、cancel
 * handle 并停止 heartbeat，且不反写任何 durable 状态。
 */
@Slf4j
final class ToolExecution implements ToolGateway.Listener {

  private final HarnessStore store;
  private final RealtimeEventSink realtimeEventSink;
  private final ClaimedWork claim;
  private final long invocationId;
  private final long threadId;
  private final int attempt;
  private final ToolInvocationRequest request;
  private final ToolProcessorConfig config;
  private final Clock clock;
  private final WorkHeartbeat heartbeat;
  private final Consumer<ToolExecution> ownerRelease;

  private final AtomicBoolean terminal = new AtomicBoolean();
  private final AtomicBoolean abandoned = new AtomicBoolean();
  private final AtomicBoolean heartbeatStarted = new AtomicBoolean();
  private final Object monitor = new Object();
  private final ArrayDeque<Pending> pending = new ArrayDeque<>();
  private boolean gateOpen;
  private ToolGateway.Handle handle;

  ToolExecution(
      HarnessStore store,
      RealtimeEventSink realtimeEventSink,
      ClaimedWork claim,
      long threadId,
      int attempt,
      ToolInvocationRequest request,
      ToolProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Consumer<ToolExecution> ownerRelease) {
    this.store = Objects.requireNonNull(store, "store");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.invocationId = claim.target().id();
    this.threadId = threadId;
    this.attempt = attempt;
    this.request = Objects.requireNonNull(request, "request");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeat =
        new WorkHeartbeat(
            Objects.requireNonNull(store, "store"),
            Objects.requireNonNull(scheduler, "scheduler"),
            config.leaseConfig(),
            clock,
            this::abandon);
    this.ownerRelease = Objects.requireNonNull(ownerRelease, "ownerRelease");
  }

  long invocationId() {
    return invocationId;
  }

  /** 本 execution 持有的 claim（供 processor 的 duplicate admission fencing 比较 token）。 */
  ClaimedWork claim() {
    return claim;
  }

  /** execution 是否已被 abandon（close / cancel / lost ownership 竞态检查用）。 */
  boolean abandoned() {
    return abandoned.get();
  }

  /** 启动 lease heartbeat；scheduler 拒绝时返回 false。已启动（preflight 阶段先行启动，dispatch 阶段复用）视为成功。 */
  boolean startHeartbeat() {
    if (heartbeatStarted.get()) {
      return true;
    }
    if (heartbeat.start(claim)) {
      heartbeatStarted.set(true);
      return true;
    }
    return heartbeatStarted.get();
  }

  /**
   * Gateway admission 返回 Started 后调用：先安全 attach handle（若 execution 已被 abandon / heartbeat 已 lost，
   * 立即 best-effort cancel 且不 markRunning），再短事务重新验证 lease + DISPATCHING + proposed attempt 并
   * markRunning + Thread revision+1；成功后打开 listener 门并重放缓冲回调。失败（lost / stale / Stop 获胜）关闭 listener 并
   * cancel handle，不写任何 durable 状态。
   */
  ProcessResult activate(ToolGateway.Handle startedHandle) {
    if (!attachHandle(startedHandle)) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    boolean committed;
    try {
      committed = markRunning();
    } catch (RuntimeException failure) {
      log.warn("cannot persist tool running state for {}", invocationId, failure);
      committed = false;
    }
    if (!committed) {
      // handle 已 attach：abandon 会 cancel 它。
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    List<Publish> publishes = new ArrayList<>();
    Applied applied;
    synchronized (monitor) {
      gateOpen = true;
      applied = abandoned.get() ? Applied.LOST : drain(publishes);
    }
    publishAll(publishes);
    if (applied == Applied.LOST) {
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    if (applied != Applied.PROGRESSED) {
      // 缓冲的 terminal/retry 信号在激活期间已落地：本地 execution 已结束。
      abandon();
      return applied == Applied.RETRY ? ProcessResult.RESCHEDULED : ProcessResult.TERMINATED;
    }
    return ProcessResult.STARTED;
  }

  /**
   * monitor 内只做决定与赋值：若 execution 已 abandoned（heartbeat / close / cancel 竞态）或 handle 已存在则返回 false
   * （绝不 markRunning）；外部 {@link ToolGateway.Handle#cancel} 的调用**必须在 monitor 之外**（cancel 可能同步触发
   * listener 回调 / 阻塞等待确认，锁内调用会与需要 monitor 的回调路径死锁），因此锁内只置标记，锁外统一 best-effort cancel。
   */
  private boolean attachHandle(ToolGateway.Handle startedHandle) {
    Objects.requireNonNull(startedHandle, "startedHandle");
    boolean cancel;
    synchronized (monitor) {
      if (abandoned.get() || handle != null) {
        cancel = true;
      } else {
        handle = startedHandle;
        cancel = false;
      }
    }
    if (cancel) {
      cancelBestEffort(startedHandle);
    }
    return !cancel;
  }

  @Override
  public void onPartial(ToolResult partial) {
    deliver(new Pending(PendingKind.PARTIAL, partial, null, null, null));
  }

  @Override
  public void onSucceeded(ToolResult result) {
    deliver(new Pending(PendingKind.SUCCEEDED, null, result, null, null));
  }

  @Override
  public void onFailed(ToolGateway.Failure failure) {
    deliver(new Pending(PendingKind.FAILED, null, null, failure, null));
  }

  @Override
  public void onCancelled(ToolInvocationError error) {
    deliver(new Pending(PendingKind.CANCELLED, null, null, null, error));
  }

  @Override
  public void onUnknown(ToolInvocationError error) {
    deliver(new Pending(PendingKind.UNKNOWN, null, null, null, error));
  }

  /** 关闭 listener（丢弃缓冲）、cancel handle、停止 heartbeat 并从 registry 释放；幂等。 */
  void abandon() {
    if (!abandoned.compareAndSet(false, true)) {
      return;
    }
    heartbeat.stop();
    synchronized (monitor) {
      pending.clear();
    }
    cancelHandle();
    ownerRelease.accept(this);
  }

  private void deliver(Pending signal) {
    if (terminal.get() || abandoned.get()) {
      return;
    }
    boolean terminalSignal = signal.kind != PendingKind.PARTIAL;
    if (terminalSignal && !terminal.compareAndSet(false, true)) {
      return;
    }
    List<Publish> publishes = new ArrayList<>();
    Applied applied = Applied.LOST;
    RuntimeException failure = null;
    synchronized (monitor) {
      if (abandoned.get()) {
        return;
      }
      if (!gateOpen) {
        pending.add(signal);
        return;
      }
      try {
        applied = processLocked(signal, publishes);
      } catch (RuntimeException error) {
        failure = error;
      }
    }
    if (failure != null) {
      // 非法 partial/result 已在 processLocked 内确定性映射为 FAILED；逃逸到这里的是 Store /
      // 本地管线异常，不能伪装成 Gateway 协议错误。关闭本地执行，保留 durable 状态供 lease 恢复为 UNKNOWN。
      log.warn("cannot process tool callback for {}: {}", invocationId, failure.toString());
      abandon();
      return;
    }
    if (applied == Applied.LOST) {
      abandon();
      return;
    }
    publishAll(publishes);
    if (applied != Applied.PROGRESSED) {
      // terminal / retry 落地后本地 execution 已结束（包括 partial 校验失败转 terminal 的路径）。
      abandon();
    }
  }

  /** 重放 gate 打开前缓冲的信号；terminal/retry 信号按到达顺序必须是缓冲区最后一个。 */
  private Applied drain(List<Publish> publishes) {
    List<Pending> buffered = List.copyOf(pending);
    pending.clear();
    for (Pending signal : buffered) {
      Applied applied = processLocked(signal, publishes);
      if (applied != Applied.PROGRESSED) {
        return applied;
      }
    }
    return Applied.PROGRESSED;
  }

  private Applied processLocked(Pending signal, List<Publish> publishes) {
    return switch (signal.kind) {
      case PARTIAL -> processPartialLocked(signal.partial, publishes);
      case SUCCEEDED -> finishSuccessLocked(signal.result, publishes);
      case FAILED -> finishFailureLocked(signal.failure, publishes);
      case CANCELLED -> finishCancelledLocked(signal.error, publishes);
      case UNKNOWN -> finishUnknownLocked(signal.error, publishes);
    };
  }

  /**
   * 处理一个 partial：先验证 toolCallId 匹配 request 且不含 Binary / Artifact content（partial 不可持久资源，违反即
   * 协议破坏，确定性 FAILED）；再短事务锁 Tool -&gt; claimed Work 校验 RUNNING + attempt（无 durable mutation）， commit
   * 后 best-effort 发布 {@link RealtimeEvent.ToolPartial}；duplicate / late / stale no-op。
   */
  private Applied processPartialLocked(ToolResult partial, List<Publish> publishes) {
    String validation = validatePartial(partial);
    if (validation != null) {
      return finishFailureLocked(
          new ToolGateway.Failure(new ToolInvocationError("INVALID_PARTIAL", validation), false),
          publishes);
    }
    Instant now = clock.instant();
    boolean committed =
        Boolean.TRUE.equals(
            store.transaction(
                tx -> {
                  ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
                  if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                    return false;
                  }
                  return tool.status() == ToolInvocationStatus.RUNNING && tool.attempt() == attempt;
                }));
    if (!committed) {
      return Applied.LOST;
    }
    publishes.add(new Publish(partial, now));
    return Applied.PROGRESSED;
  }

  /**
   * Terminal success：toolCallId 必须匹配 request；拒绝 BinaryToolContent（Gateway 必须先外部化为稳定
   * ArtifactToolContent ref）；terminate 强制归一 false 后写 SUCCEEDED。
   */
  private Applied finishSuccessLocked(ToolResult result, List<Publish> publishes) {
    String validation = validateTerminalResult(result);
    if (validation != null) {
      return finishFailureLocked(
          new ToolGateway.Failure(new ToolInvocationError("INVALID_RESULT", validation), false),
          publishes);
    }
    ToolResult normalized =
        new ToolResult(
            result.toolCallId(), result.contents(), result.error(), result.detailsJson(), false);
    boolean committed = safeTerminal(() -> commitSuccess(normalized));
    if (!committed) {
      return Applied.LOST;
    }
    return Applied.TERMINAL;
  }

  /**
   * 已确认失败：仅当 retryable=true、binding 的 sideEffect 为 READ_ONLY / IDEMPOTENT 且 retryPolicy 允许时 RUNNING
   * -&gt; READY + revision+1 + policy delay reschedule（不 request THREAD）；NON_IDEMPOTENT 绝不自动
   * retry。否则 FAILED + THREAD wake + complete。
   */
  private Applied finishFailureLocked(ToolGateway.Failure failure, List<Publish> publishes) {
    ToolInvocationError error = failure.error();
    ToolSideEffect sideEffect = request.binding().descriptor().sideEffect();
    if (failure.retryable()
        && sideEffect != ToolSideEffect.NON_IDEMPOTENT
        && config.retryPolicy().allowsRetry(attempt)) {
      Duration delay = config.retryPolicy().delayBeforeRetry(attempt);
      boolean committed = safeTerminal(() -> commitRetry(delay));
      if (committed) {
        log.info(
            "scheduled tool retry for invocation {} (attempt {}) in {}",
            invocationId,
            attempt + 1,
            delay);
        return Applied.RETRY;
      }
      return Applied.LOST;
    }
    return safeTerminal(() -> commitTerminal(TerminalKind.FAILED, error))
        ? Applied.TERMINAL
        : Applied.LOST;
  }

  /** onCancelled -&gt; CANCELLED terminal，不 retry。 */
  private Applied finishCancelledLocked(ToolInvocationError error, List<Publish> publishes) {
    return safeTerminal(() -> commitTerminal(TerminalKind.CANCELLED, error))
        ? Applied.TERMINAL
        : Applied.LOST;
  }

  /** onUnknown -&gt; UNKNOWN terminal（RUNNING 保留 attempt），不 retry。 */
  private Applied finishUnknownLocked(ToolInvocationError error, List<Publish> publishes) {
    return safeTerminal(() -> commitTerminal(TerminalKind.UNKNOWN, error))
        ? Applied.TERMINAL
        : Applied.LOST;
  }

  /** partial 校验：非空、toolCallId 匹配 request、不得携带 Binary / Artifact content。 */
  private String validatePartial(ToolResult partial) {
    if (partial == null) {
      return "partial must not be null";
    }
    if (!partial.toolCallId().equals(request.call().id())) {
      return "partial toolCallId does not match the request call";
    }
    for (ToolContent content : partial.contents()) {
      if (content instanceof BinaryToolContent || content instanceof ArtifactToolContent) {
        return "partial must not carry binary or artifact content";
      }
    }
    return null;
  }

  /** Terminal result 校验：非空、toolCallId 匹配 request、不得携带 BinaryToolContent。 */
  private String validateTerminalResult(ToolResult result) {
    if (result == null) {
      return "result must not be null";
    }
    if (!result.toolCallId().equals(request.call().id())) {
      return "result toolCallId does not match the request call";
    }
    for (ToolContent content : result.contents()) {
      if (content instanceof BinaryToolContent) {
        return "result must not carry inline binary content; externalize it to an artifact first";
      }
    }
    return null;
  }

  private boolean markRunning() {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.DISPATCHING
                  || tool.attempt() != attempt - 1) {
                return false;
              }
              tx.updateToolInvocations(List.of(tool.markRunning(now)));
              tx.updateThread(thread.touchRevision(now));
              return true;
            }));
  }

  /** RUNNING -&gt; READY + revision+1 + reschedule；不 request THREAD。 */
  private boolean commitRetry(Duration delay) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              tx.updateToolInvocations(List.of(tool.retryReady(now)));
              tx.updateThread(thread.touchRevision(now));
              tx.rescheduleWork(claim, now, now.plus(delay));
              return true;
            }));
  }

  private boolean commitSuccess(ToolResult result) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
              if (tool == null) {
                return false;
              }
              tx.lockWork(new WorkTarget(WorkTargetType.THREAD, threadId));
              if (tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              tx.updateToolInvocations(List.of(tool.succeed(result, now)));
              tx.updateThread(thread.touchRevision(now));
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), now);
              tx.completeWork(claim, now);
              return true;
            }));
  }

  private boolean commitTerminal(TerminalKind kind, ToolInvocationError error) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
              if (tool == null) {
                return false;
              }
              tx.lockWork(new WorkTarget(WorkTargetType.THREAD, threadId));
              if (tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              ToolInvocation next =
                  switch (kind) {
                    case FAILED -> tool.fail(error, now);
                    case CANCELLED -> tool.cancel(error, now);
                    case UNKNOWN -> tool.unknown(error, now);
                  };
              tx.updateToolInvocations(List.of(next));
              tx.updateThread(thread.touchRevision(now));
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), now);
              tx.completeWork(claim, now);
              return true;
            }));
  }

  private boolean safeTerminal(BooleanSupplier action) {
    try {
      return action.getAsBoolean();
    } catch (RuntimeException failure) {
      log.warn("cannot persist tool terminal for {}: {}", invocationId, failure.toString());
      return false;
    }
  }

  private void publishAll(List<Publish> publishes) {
    for (Publish publish : publishes) {
      appendRealtime(publish.partial, publish.createdAt);
    }
  }

  private void appendRealtime(ToolResult partial, Instant createdAt) {
    try {
      realtimeEventSink.append(
          new RealtimeEvent.ToolPartial(threadId, invocationId, attempt, partial, createdAt));
    } catch (RuntimeException failure) {
      log.warn("realtime tool partial projection failed for invocation {}", invocationId, failure);
    }
  }

  private void cancelHandle() {
    ToolGateway.Handle current;
    synchronized (monitor) {
      current = handle;
      handle = null;
    }
    if (current != null) {
      cancelBestEffort(current);
    }
  }

  /** Gateway Handle 取消是契约级幂等；异常只记录，不影响本地清理。 */
  private void cancelBestEffort(ToolGateway.Handle target) {
    try {
      target.cancel();
    } catch (RuntimeException failure) {
      log.warn("cannot cancel local tool execution handle for {}", invocationId, failure);
    }
  }

  private enum PendingKind {
    PARTIAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN
  }

  private record Pending(
      PendingKind kind,
      ToolResult partial,
      ToolResult result,
      ToolGateway.Failure failure,
      ToolInvocationError error) {}

  private record Publish(ToolResult partial, Instant createdAt) {}

  private enum Applied {
    PROGRESSED,
    RETRY,
    TERMINAL,
    LOST
  }

  private enum TerminalKind {
    FAILED,
    CANCELLED,
    UNKNOWN
  }
}
