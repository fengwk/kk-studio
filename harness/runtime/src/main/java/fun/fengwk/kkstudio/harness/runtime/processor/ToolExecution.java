package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次 claim 的进程内 Tool execution：独占一条虚拟线程阻塞执行 Gateway {@link ToolGateway.Run}，并持有所有权围栏与 lease
 * heartbeat。
 *
 * <p>生命周期：{@link #start} 在持久化 {@code READY -> RUNNING}（任何副作用之前）后启动执行线程；线程内依次阻塞执行、提交终态、释放资源 （{@link
 * ToolGateway.Run#close()}），因此资源只在终态决议之后、执行体返回之后释放——取消只是 interrupt，绝不代表工作线程已经退出。
 *
 * <p>{@link #abandon}（close / Stop / 租约丢失）只做本地收敛：停止 heartbeat、从 registry 移除，并在执行体运行期间 interrupt
 * 执行线程； 它绝不反写任何持久化状态，abandon 之后到达的结果一律丢弃（durable 状态由 Stop 提交或由 lease 恢复收敛为 UNKNOWN）。interrupt 只在
 * 执行体内部投递，进入终态事务前已清除，避免打断 JDBC。
 *
 * <p>终态提交：success 先做 toolCallId / 不含 BinaryResultContent 校验与 1 MiB bounded 编码尺寸校验（超限确定性
 * INVALID_RESULT， 绝不把超大行写入 PostgreSQL）；retryable 失败只在 sideEffect 为 READ_ONLY / IDEMPOTENT 且
 * retryPolicy 允许时重试，NON_IDEMPOTENT 绝不自动重试。partial 由 Gateway 校验后直接 best-effort 发布 {@link
 * RealtimeEvent.ToolPartial}，sink 失败不影响执行。 markRunning / retry / terminal 只写本 Thread 的
 * Thread/Tool/Work：事务按本 Thread 行 -&gt; ToolInvocation -&gt; claimed Work 加锁并以 Thread 行锁与
 * delete/Stop 串行化，唤醒 THREAD Work 只是 hint，不需要执行树锁。
 */
@Slf4j
final class ToolExecution {

  private final HarnessStore store;
  private final RealtimeEventSink realtimeEventSink;
  private final ClaimedWork claim;
  private final UUID invocationId;
  private final UUID threadId;
  private final int attempt;
  private final ToolInvocationRequest request;
  private final ToolProcessorConfig config;
  private final Clock clock;
  private final WorkHeartbeat heartbeat;
  private final Consumer<ToolExecution> ownerRelease;

  private final AtomicBoolean abandoned = new AtomicBoolean();
  private final AtomicBoolean heartbeatStarted = new AtomicBoolean();
  private final Object monitor = new Object();

  /** monitor 保护：执行线程正处于 {@code Run.execute} 内；只有此时 abandon 才会 interrupt。 */
  private boolean inBody;

  private Thread bodyThread;
  private volatile boolean progressOpen;

  ToolExecution(
      HarnessStore store,
      RealtimeEventSink realtimeEventSink,
      ClaimedWork claim,
      UUID threadId,
      int attempt,
      ToolInvocationRequest request,
      ToolProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      Consumer<ToolExecution> ownerRelease) {
    this.store = Objects.requireNonNull(store, "store");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.invocationId = claim.target().id();
    this.threadId = threadId;
    this.attempt = attempt;
    this.request = Objects.requireNonNull(request, "request");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.heartbeat =
        new WorkHeartbeat(
            this.store,
            Objects.requireNonNull(scheduler, "scheduler"),
            Objects.requireNonNull(heartbeatWorker, "heartbeatWorker"),
            config.leaseConfig(),
            this::abandon);
    this.ownerRelease = Objects.requireNonNull(ownerRelease, "ownerRelease");
  }

  UUID invocationId() {
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

  /** 启动 lease heartbeat；scheduler 拒绝时返回 false。已启动（preflight 阶段先行启动，执行阶段复用）视为成功。 */
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
   * Gateway admission 返回 Admitted 后调用：先持久化 {@code READY -> RUNNING}（失败即关闭 run 并 LOST），再启动执行线程。run
   * 的关闭 责任自此转移给执行线程；返回 {@link ProcessResult#STARTED} 表示线程已启动。
   */
  ProcessResult start(ToolGateway.Run run) {
    boolean committed;
    try {
      committed = markRunning();
    } catch (RuntimeException failure) {
      log.warn("cannot persist tool running state for {}", invocationId, failure);
      committed = false;
    }
    if (!committed) {
      closeQuietly(run);
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    Thread.ofVirtual().name("tool-" + invocationId + "-" + attempt).start(() -> execute(run));
    return ProcessResult.STARTED;
  }

  /** 执行线程主体：阻塞执行、提交终态、释放资源。abandon 在执行体开始之前获胜时绝不调用工具。 */
  private void execute(ToolGateway.Run run) {
    try {
      ToolGateway.Completion completion = null;
      if (enterBody()) {
        try {
          completion = run.execute(this::publishProgress);
        } catch (RuntimeException failure) {
          // 契约：Gateway 把工具异常分类为 Completion；逃逸的异常说明外部效果不可知，收敛为 UNKNOWN，绝不重放。
          log.warn(
              "tool gateway run failed for {}: {}",
              invocationId,
              ProcessorExceptions.describe(failure));
          completion =
              new ToolGateway.Unknown(
                  new ToolInvocationError(
                      "GATEWAY_UNKNOWN", "tool gateway run failed; outcome cannot be confirmed"));
        } finally {
          exitBody();
        }
      }
      if (completion != null && !abandoned.get()) {
        apply(completion);
      }
    } finally {
      closeQuietly(run);
      abandon();
    }
  }

  /** 声明执行体开始；abandon 已获胜时返回 false（工具绝不被调用）。 */
  private boolean enterBody() {
    synchronized (monitor) {
      if (abandoned.get()) {
        return false;
      }
      inBody = true;
      bodyThread = Thread.currentThread();
      progressOpen = true;
      return true;
    }
  }

  /** 声明执行体退出，并清除可能已投递的 interrupt：此后 abandon 不会再打断本线程。 */
  private void exitBody() {
    synchronized (monitor) {
      inBody = false;
      progressOpen = false;
      bodyThread = null;
      Thread.interrupted();
    }
  }

  /** Run 的关闭是幂等且不抛的契约；这里只保证意外异常不会阻断 heartbeat 与 registry 清理。 */
  private void closeQuietly(ToolGateway.Run run) {
    try {
      run.close();
    } catch (RuntimeException failure) {
      log.warn("cannot release tool run resources for {}", invocationId, failure);
    }
  }

  /** 停止 heartbeat、从 registry 释放，并在执行体运行期间 interrupt 执行线程；幂等，且不反写任何持久化状态。 */
  void abandon() {
    if (!abandoned.compareAndSet(false, true)) {
      return;
    }
    heartbeat.stop();
    synchronized (monitor) {
      if (inBody) {
        bodyThread.interrupt();
      }
    }
    ownerRelease.accept(this);
  }

  /** Gateway 已校验的非 terminal partial：直接 best-effort 发布，执行体退出或 abandon 之后丢弃。 */
  private void publishProgress(ToolResult partial) {
    if (!progressOpen || abandoned.get()) {
      return;
    }
    try {
      // 每次生产一个 partial event 生成独立 UUID：同 ms、同 payload 的两条 progress 仍是两个事件，客户端按 eventId 去重。
      realtimeEventSink.append(
          new RealtimeEvent.ToolPartial(
              threadId, invocationId, attempt, UUID.randomUUID(), partial, clock.instant()));
    } catch (RuntimeException failure) {
      log.warn("realtime tool partial projection failed for invocation {}", invocationId, failure);
    }
  }

  private void apply(ToolGateway.Completion completion) {
    switch (completion) {
      case ToolGateway.Succeeded succeeded -> finishSuccess(succeeded.success());
      case ToolGateway.Failed failed -> finishFailure(failed.error(), failed.retryable());
      case ToolGateway.Cancelled cancelled -> safeTerminal(
          () -> commitTerminal(TerminalKind.CANCELLED, cancelled.error()));
      case ToolGateway.Unknown unknown -> safeTerminal(
          () -> commitTerminal(TerminalKind.UNKNOWN, unknown.error()));
    }
  }

  /**
   * Terminal success：先做基础校验（非空 / toolCallId 匹配 / 拒绝 BinaryResultContent——Gateway 必须先外部化为稳定
   * ResourceResultContent ref），再用 bounded 编码器测量即将持久化结果的 canonical JSON UTF-8 字节（不物化完整 JSON），超过 1
   * MiB 确定性 INVALID_RESULT（N×data URI / 超大 details/text 不得撑爆 PostgreSQL）。
   */
  private void finishSuccess(ToolSuccess success) {
    ToolResult result = success.result();
    String validation = validateTerminalResult(result);
    if (validation != null) {
      finishFailure(new ToolInvocationError("INVALID_RESULT", validation), false);
      return;
    }
    if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
        result, ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES)) {
      finishFailure(
          new ToolInvocationError(
              "INVALID_RESULT",
              "result must not exceed "
                  + ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES
                  + " bytes of canonical tool result JSON"),
          false);
      return;
    }
    safeTerminal(() -> commitSuccess(result, success.effects()));
  }

  /**
   * 已确认失败：仅当 retryable=true、binding 的 sideEffect 为 READ_ONLY / IDEMPOTENT 且 retryPolicy 允许时 RUNNING
   * -&gt; READY + version+1 + policy delay reschedule（不 request THREAD）；NON_IDEMPOTENT 绝不自动
   * retry。否则 FAILED + THREAD wake + complete。
   */
  private void finishFailure(ToolInvocationError error, boolean retryable) {
    ToolSideEffect sideEffect = request.binding().descriptor().sideEffect();
    InvocationRetryDecision decision =
        InvocationRetryDecision.decide(
            config.retryPolicyProvider().retryPolicy(),
            retryable && sideEffect != ToolSideEffect.NON_IDEMPOTENT,
            attempt);
    if (decision.retry()) {
      Duration delay = decision.delay();
      if (safeTerminal(() -> commitRetry(delay))) {
        log.info(
            "scheduled tool retry for invocation {} (attempt {}) in {}",
            invocationId,
            attempt + 1,
            delay);
      }
      return;
    }
    safeTerminal(() -> commitTerminal(TerminalKind.FAILED, error));
  }

  /** Terminal result 基础校验：非空、toolCallId 匹配 request、不得携带 BinaryResultContent。编码尺寸校验在基础校验之后进行。 */
  private String validateTerminalResult(ToolResult result) {
    if (result == null) {
      return "result must not be null";
    }
    if (!result.toolCallId().equals(request.call().id())) {
      return "result toolCallId does not match the request call";
    }
    for (ResultContent content : result.contents()) {
      if (content instanceof BinaryResultContent) {
        return "result must not carry inline binary content; externalize it to a resource first";
      }
    }
    return null;
  }

  /** READY -&gt; RUNNING（attempt+1）+ version+1：副作用之前的持久执行意图，要求 claim 仍 owned 且 attempt 匹配。 */
  private boolean markRunning() {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt - 1) {
                return false;
              }
              tx.updateToolInvocations(List.of(tool.markRunning(now)));
              tx.updateThread(thread.touchVersion(now));
              return true;
            }));
  }

  /** RUNNING -&gt; READY + version+1 + reschedule；不 request THREAD。 */
  private boolean commitRetry(Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              tx.updateToolInvocations(List.of(tool.retryReady(now)));
              tx.updateThread(thread.touchVersion(now));
              tx.rescheduleWork(claim, delay);
              return true;
            }));
  }

  private boolean commitSuccess(ToolResult result, ToolEffectBatch effects) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.lockThread(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                  return false;
                }
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.lockClaimedWork(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.updateToolInvocations(List.of(tool.succeed(result, effects, now)));
                tx.updateThread(thread.touchVersion(now));
                tx.completeWork(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  private boolean commitTerminal(TerminalKind kind, ToolInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.lockThread(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                  return false;
                }
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.lockClaimedWork(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                ToolInvocation next =
                    switch (kind) {
                      case FAILED -> tool.fail(error, now);
                      case CANCELLED -> tool.cancel(error, now);
                      case UNKNOWN -> tool.unknown(error, now);
                    };
                tx.updateToolInvocations(List.of(next));
                tx.updateThread(thread.touchVersion(now));
                tx.completeWork(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  /** 终态落库失败只记录：durable 状态保持 RUNNING，由 lease 恢复收敛为 UNKNOWN。 */
  private boolean safeTerminal(BooleanSupplier action) {
    try {
      return action.getAsBoolean();
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist tool terminal for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return false;
    }
  }

  private enum TerminalKind {
    /** 收敛为明确的失败终态。 */
    FAILED,

    /** 收敛为取消终态。 */
    CANCELLED,

    /** 收敛为结果不确定的终态。 */
    UNKNOWN
  }

  /** 内部回滚信号：终态事务失去 Work ownership 时使当前事务完整回滚。 */
  private static final class ClaimLostSignal extends RuntimeException {
    private ClaimLostSignal() {
      super("claimed work lost at final fence", null, false, false);
    }
  }
}
