package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision;
import fun.fengwk.kkstudio.harness.spi.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.spi.ToolOutcomeMaterializationException;
import fun.fengwk.kkstudio.harness.spi.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.spi.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.spi.ToolSuccess;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionHandle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRegistration;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimAdmissionGuard;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.spi.work.WorkHeartbeat;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
public final class ToolExecution implements ClaimAdmissionGuard.GuardedExecution {

  private final HarnessStore store;
  private final ExecutionExitConfirmer exitConfirmer;
  private final LocalExecutionLifecycle lifecycle;
  private final RealtimeEventSink realtimeEventSink;
  private final ClaimedWork claim;
  private final UUID invocationId;
  private final UUID executionId = UUID.randomUUID();
  private final UUID threadId;
  private final int attempt;

  /** PREPARED 之后、登记事务提交后由生命周期端口返回的物理句柄。 */
  private volatile LocalExecutionHandle localHandle;

  /** 本代次已成功登记的远端事实；发送前写入，确认时直接复用原始 registeredAt，不在确认事务中重新拼接。 */
  private final ConcurrentMap<UUID, ExecutionExitFact> registeredRemotes =
      new ConcurrentHashMap<>();

  private final ToolInvocationRequest request;
  private final ToolProcessorConfig config;
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;
  private final Clock clock;
  private final WorkHeartbeat heartbeat;
  private final Consumer<ToolExecution> ownerRelease;

  private final AtomicBoolean abandoned = new AtomicBoolean();
  private final AtomicBoolean heartbeatStarted = new AtomicBoolean();
  private final Object monitor = new Object();

  /** monitor 保护：执行线程正处于 {@code Run.execute} 内；只有此时 abandon 才会 interrupt。 */
  private boolean inBody;

  /** 是否真正进入过 Tool body；未进入 body 的执行只允许确认 NOT_STARTED。 */
  private volatile boolean bodyStarted;

  private Thread bodyThread;
  private volatile boolean progressOpen;

  public ToolExecution(
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
      Consumer<ToolExecution> ownerRelease,
      LocalExecutionLifecycle lifecycle,
      ExecutionExitConfirmer exitConfirmer,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.exitConfirmer = Objects.requireNonNull(exitConfirmer, "exitConfirmer");
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
    this.toolResultHistoryMaterializer =
        Objects.requireNonNull(toolResultHistoryMaterializer, "toolResultHistoryMaterializer");
  }

  UUID invocationId() {
    return invocationId;
  }

  /** 当前 Tool 代次的远端观察者：发送前登记，正向 ACK 或确定未发送才确认。 */
  public ToolGateway.RemoteExecutionObserver remoteExecutions() {
    return new RemoteExecution();
  }

  private final class RemoteExecution implements ToolGateway.RemoteExecutionObserver {
    @Override
    public boolean tryStart(UUID remoteExecutionId, EnvironmentId environmentId) {
      Objects.requireNonNull(remoteExecutionId, "remoteExecutionId");
      Objects.requireNonNull(environmentId, "environmentId");
      if (abandoned.get()) {
        return false;
      }
      ExecutionExitFact remote =
          store.transaction(
              tx -> {
                if (tx.threads().lock(threadId).isEmpty()
                    || tx.work().lockClaimed(claim).isEmpty()) {
                  return null;
                }
                if (abandoned.get()) {
                  return null;
                }
                return ExecutionExitLedger.register(
                    tx,
                    remoteExecutionId,
                    threadId,
                    invocationId,
                    attempt,
                    claim.leaseToken(),
                    ExecutionExitKind.REMOTE,
                    executionId(),
                    environmentId);
              });
      if (remote == null) {
        return false;
      }
      // 必须先写入本地注册表再返回允许发送；确认只使用这条已持久化身份及其原始登记时间。
      registeredRemotes.put(remoteExecutionId, remote);
      return true;
    }

    @Override
    public void confirmExit(UUID remoteExecutionId) {
      confirmRemote(remoteExecutionId, ExecutionExitState.REMOTE_ACKNOWLEDGED);
    }

    @Override
    public void confirmNotStarted(UUID remoteExecutionId) {
      confirmRemote(remoteExecutionId, ExecutionExitState.NOT_STARTED);
    }

    private void confirmRemote(UUID remoteExecutionId, ExecutionExitState state) {
      Objects.requireNonNull(remoteExecutionId, "remoteExecutionId");
      LocalExecutionHandle handle = localHandle;
      if (handle == null || !handle.registered()) {
        throw new IllegalStateException(
            "remote execution " + remoteExecutionId + " has no registered local generation");
      }
      ExecutionExitFact remote = registeredRemotes.get(remoteExecutionId);
      if (remote == null) {
        throw new IllegalStateException(
            "remote execution " + remoteExecutionId + " was not registered before start");
      }
      // 远端交付失败必须显式抛出；合法深删由 confirmer 按 missing-safe 结束。
      exitConfirmer.confirm(remote, state);
    }
  }

  /** 本 execution 持有的 claim（供 processor 的 duplicate admission fencing 比较 token）。 */
  @Override
  public ClaimedWork claim() {
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
   * Gateway admission 返回 Admitted 后调用：先持久化 PREPARED 并在事务内登记 {@code READY -> RUNNING}，事务提交成功才启动执行线程。
   * run 的关闭责任自此转移给执行线程；返回 {@link ProcessResult#STARTED} 表示线程已启动。
   */
  public ProcessResult start(ToolGateway.Run run) {
    LocalExecutionHandle handle;
    try {
      handle = beginLocal(this::registerRunning);
    } catch (RuntimeException failure) {
      log.warn("cannot prepare tool running state for {}", invocationId, failure);
      closeStrict(run);
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    return launch(run, handle);
  }

  /**
   * Gateway reattach 成功拿到 Run 后调用：在事务内以本 claim 锁 Thread -> Tool -> Work 登记本节点的 TOOL 事实， 绝不自增
   * attempt，不调用 markRunning（状态已是 RUNNING）。随后启动执行线程。
   */
  public ProcessResult startReattach(ToolGateway.Run run, ExecutionExitFact remoteFact) {
    LocalExecutionHandle handle;
    try {
      handle = beginLocal(this::registerReattach);
      if (handle.registered()) {
        registeredRemotes.put(remoteFact.executionId(), remoteFact);
      }
    } catch (RuntimeException failure) {
      log.warn("cannot prepare tool reattach state for {}", invocationId, failure);
      closeStrict(run);
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    return launch(run, handle);
  }

  /** 在 PREPARED 已落盘后执行登记事务，并保存端口返回的物理句柄；PREPARED 写失败时不返回 handle。 */
  private LocalExecutionHandle beginLocal(LocalExecutionRegistration registration) {
    LocalExecutionHandle handle = lifecycle.begin(localIdentity(), registration);
    localHandle = handle;
    return handle;
  }

  private LocalExecutionIdentity localIdentity() {
    return new LocalExecutionIdentity(
        executionId, threadId, invocationId, attempt, claim.leaseToken(), ExecutionExitKind.TOOL);
  }

  private ProcessResult launch(ToolGateway.Run run, LocalExecutionHandle handle) {
    if (handle == null || !handle.registered() || abandoned.get()) {
      boolean closed = closeStrict(run);
      if (handle != null) {
        if (closed) {
          handle.provenExit(ExecutionExitState.NOT_STARTED);
        } else {
          handle.exitNotProven();
        }
      }
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    try {
      Thread.ofVirtual().name("tool-" + invocationId + "-" + attempt).start(() -> execute(run));
    } catch (RuntimeException | Error failure) {
      if (closeStrict(run)) {
        handle.provenExit(ExecutionExitState.NOT_STARTED);
      } else {
        handle.exitNotProven();
      }
      abandon();
      throw failure;
    }
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
      boolean closed = closeStrict(run);
      if (closed) {
        if (bodyStarted()) {
          confirmStudioExit();
        } else {
          confirmNotStarted();
        }
      } else {
        exitNotProven();
      }
      abandon();
    }
  }

  /** 本地资源释放成功才允许随后确认退出；失败保持未确认并保留异常证据。 */
  private boolean closeStrict(ToolGateway.Run run) {
    try {
      run.close();
      return true;
    } catch (RuntimeException failure) {
      log.warn(
          "cannot release tool run resources for {}; execution {} remains unconfirmed;"
              + " phase=TOOL_RUN_CLOSE; exception={}",
          invocationId,
          executionId(),
          failure.getClass().getSimpleName());
      return false;
    }
  }

  /** 已持有 PREPARED 或已登记但执行线程确定没有进入 body；资源释放成功后结束该代次。 */
  private void confirmNotStarted() {
    if (localHandle != null) {
      confirm(ExecutionExitState.NOT_STARTED);
    }
  }

  /** body 与本地资源释放之后才确认 Studio 退出。持久化失败保持未确认，不能把清理失败当成退出。 */
  private void confirmStudioExit() {
    confirm(ExecutionExitState.STUDIO_EXITED);
  }

  /** 无法证明退出：保留 PREPARED 与未确认事实，并要求日志目录锁保留到 JVM 退出。 */
  private void exitNotProven() {
    LocalExecutionHandle handle = localHandle;
    if (handle != null) {
      handle.exitNotProven();
    }
  }

  private void confirm(ExecutionExitState state) {
    LocalExecutionHandle handle = localHandle;
    if (handle == null) {
      log.warn("tool execution {} was not registered and remains unconfirmed", executionId);
      return;
    }
    try {
      handle.provenExit(state);
    } catch (RuntimeException failure) {
      log.warn(
          "cannot confirm tool execution exit for {}; phase=TOOL_EXIT_CONFIRM; exception={}",
          executionId,
          failure.getClass().getSimpleName());
    }
  }

  private UUID executionId() {
    return executionId;
  }

  /** 是否真正进入过 Tool body；未进入 body 的 cancel 不是 Studio 退出。 */
  boolean bodyStarted() {
    return bodyStarted;
  }

  /** 声明执行体开始；已取消或取消早于 attach 时返回 false（工具绝不被调用）。 */
  private boolean enterBody() {
    synchronized (monitor) {
      LocalExecutionHandle handle = localHandle;
      if (abandoned.get() || handle == null || !handle.attachBody(Thread.currentThread())) {
        return false;
      }
      inBody = true;
      bodyStarted = true;
      bodyThread = Thread.currentThread();
      progressOpen = true;
      return true;
    }
  }

  /** 声明执行体退出，并清除可能已投递的 interrupt；此后 abandon 不会再打断本线程。 */
  private void exitBody() {
    synchronized (monitor) {
      inBody = false;
      progressOpen = false;
      bodyThread = null;
      LocalExecutionHandle handle = localHandle;
      if (handle != null) {
        handle.detachBody();
      }
      Thread.interrupted();
    }
  }

  /** 停止 heartbeat、请求取消物理 body（含迟到 attach）并从 registry 释放；幂等，且不反写任何持久化状态。 */
  @Override
  public void abandon() {
    if (!abandoned.compareAndSet(false, true)) {
      return;
    }
    heartbeat.stop();
    LocalExecutionHandle handle = localHandle;
    if (handle != null) {
      handle.requestCancel();
    }
    ownerRelease.accept(this);
  }

  /** 当前 JVM 本地执行体是否仍在等待远端结果：处于 body 内部、未被 abandon 且已登记远端事实。 */
  public boolean isAwaitingRemote() {
    synchronized (monitor) {
      return inBody && !abandoned.get() && !registeredRemotes.isEmpty();
    }
  }

  /**
   * 排空移交：停止本地执行体（不写 durable 状态），在剩余预算内等待执行线程退出，再 release TOOL Work claim，使其他节点立即可以认领。 执行线程退出时由
   * execute() 的 finally 自行以 STUDIO_EXITED 确认 TOOL 事实；超时则保持 UNCONFIRMED。
   *
   * @return 若不满足移交条件返回 false，成功移交返回 true
   */
  public boolean handover(Duration remaining) {
    Objects.requireNonNull(remaining, "remaining");
    if (remaining.isNegative()) {
      throw new IllegalArgumentException("handover remaining must not be negative");
    }
    long deadlineNanos = System.nanoTime() + remaining.toNanos();
    Thread targetThread;
    synchronized (monitor) {
      if (!inBody || abandoned.get() || registeredRemotes.isEmpty()) {
        return false;
      }
      targetThread = bodyThread;
    }
    abandon();
    if (targetThread != null && targetThread != Thread.currentThread()) {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos > 0) {
        try {
          targetThread.join(Duration.ofNanos(remainingNanos));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
      if (targetThread.isAlive()) {
        log.warn(
            "tool execution {} body thread did not terminate before drain deadline; tool exit fact remains unconfirmed",
            executionId);
      }
    }
    store.transaction(tx -> tx.work().release(claim));
    return true;
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
    safeTerminal(() -> commitSuccess(result));
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
            config.retryPolicyProvider().get(),
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

  /** READY -&gt; RUNNING（attempt+1）+ version+1 并登记退出事实；事务提交成功但未登记时返回 {@code null}。 */
  private ExecutionExitFact registerRunning() {
    return store.transaction(
        tx -> {
          Instant now = tx.now();
          ThreadState thread = tx.threads().lock(threadId).orElse(null);
          if (thread == null) {
            return null;
          }
          ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
          if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
            return null;
          }
          if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt - 1) {
            return null;
          }
          tx.tools().update(List.of(tool.markRunning(now)));
          tx.threads().update(thread.touchVersion(now));
          return ExecutionExitLedger.register(
              tx,
              executionId(),
              threadId,
              invocationId,
              attempt,
              claim.leaseToken(),
              ExecutionExitKind.TOOL,
              null);
        });
  }

  /**
   * 挂接执行体在事务内锁 Thread -> Tool -> Work 登记本节点的 TOOL 事实： 同一 attempt、新 executionId、本 claim token。不调用
   * markRunning，不修改 version。提交成功后由 {@link #startReattach} 将原 REMOTE 事实放入
   * registeredRemotes，以便统一确认与排空移交判定。
   */
  private ExecutionExitFact registerReattach() {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElse(null);
          if (thread == null) {
            return null;
          }
          ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
          if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
            return null;
          }
          if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
            return null;
          }
          return ExecutionExitLedger.register(
              tx,
              executionId(),
              threadId,
              invocationId,
              attempt,
              claim.leaseToken(),
              ExecutionExitKind.TOOL,
              null);
        });
  }

  /** RUNNING -&gt; READY + version+1 + reschedule；不 request THREAD。 */
  private boolean commitRetry(Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
              if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              tx.tools().update(List.of(tool.retryReady(now)));
              tx.threads().update(thread.touchVersion(now));
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  /**
   * RUNNING -&gt; SUCCEEDED：在同一事务内先把结果物化为 Session 稳定引用，再保存该唯一稳定结果与 SUCCEEDED。物化永久失败时回滚整个
   * 成功事务，并以独立的无资源 UNKNOWN 收敛，绝不重试外部副作用或再次消费同一 upload。
   */
  private boolean commitSuccess(ToolResult result) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                ToolResult stable =
                    toolResultHistoryMaterializer.materialize(
                        thread.sessionId(), request.call().toolName(), result);
                requireStableResult(stable, request.call().id());
                if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
                    stable, ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES)) {
                  throw new ToolOutcomeMaterializationException(
                      "stable tool outcome exceeds "
                          + ToolResultSizeLimits.MAX_TERMINAL_RESULT_UTF8_BYTES
                          + " bytes");
                }
                tx.tools().update(List.of(tool.succeed(stable, now)));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    } catch (ToolOutcomeMaterializationException failure) {
      log.warn(
          "cannot materialize stable tool outcome for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      String message =
          failure.getMessage() == null || failure.getMessage().isBlank()
              ? "tool result resource persistence failed"
              : failure.getMessage();
      return commitTerminal(
          TerminalKind.UNKNOWN,
          new ToolInvocationError("RESOURCE_MATERIALIZATION_FAILED", message));
    }
  }

  /** 稳定结果再校验：同一调用、仅 Text/JSON/Session Resource，且不含 inline binary 或瞬时 upload。 */
  private static void requireStableResult(ToolResult result, String toolCallId) {
    if (result == null) {
      throw new ToolOutcomeMaterializationException("stable tool outcome must not be null");
    }
    if (!result.toolCallId().equals(toolCallId)) {
      throw new ToolOutcomeMaterializationException(
          "stable tool outcome toolCallId does not match the request call");
    }
    for (ResultContent content : result.contents()) {
      if (content instanceof BinaryResultContent) {
        throw new ToolOutcomeMaterializationException(
            "stable tool outcome must not carry inline binary content");
      }
      if (content instanceof ResourceResultContent resource
          && resource.resource().sessionBlobId() == null) {
        throw new ToolOutcomeMaterializationException(
            "stable tool outcome resources must be session resources");
      }
    }
  }

  private boolean commitTerminal(TerminalKind kind, ToolInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                ToolInvocation next =
                    switch (kind) {
                      case FAILED -> tool.fail(error, now);
                      case CANCELLED -> tool.cancel(error, now);
                      case UNKNOWN -> tool.unknown(error, now);
                    };
                tx.tools().update(List.of(next));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
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
}
