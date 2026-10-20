package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.ThreadTreeLocks;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 一次 claim 的进程内 Model execution：Gateway Listener 回调门控缓冲、所有权围栏与 lease heartbeat。
 *
 * <p>Listener 回调在持久化 RUNNING 落地前由回调门控缓冲；两阶段激活（{@link #activate}）：先安全 attach handle，再持久化
 * markRunning，最后调用外部 {@code handle.activate()}（通知 Gateway 打开回调门控），成功后按到达顺序重放缓冲信号。 激活后通过
 * per-execution 单 drain owner 顺序处理流式增量事件、推送/checkpoint 定时信号与终态信号。
 *
 * <p>推送与落库是两条独立节奏：delta 在 {@link ModelStreamConfig#pushWindow()} 内合并为小批次后直接发布（不等待数据库、不依赖提交）；累计
 * text/thinking 由独立的 checkpoint 定时器（或追帧请求 {@link #requestCheckpoint}）持久化，事务只锁 ModelInvocation 与
 * claimed Work（不取执行树锁，因为不唤醒 Thread），围栏重校验 RUNNING + attempt + lease，提交后发布 {@link
 * RealtimeEvent.ModelCheckpoint}。terminal / retry 信号仍在同一次状态事务中单次更新 ModelInvocation 并唤醒
 * Thread，只发布已提交的终态补齐 delta；lost ownership 或 Stop 竞态立即收敛关闭并停止发布。
 */
@Slf4j
final class ModelExecution implements ModelGateway.Listener {

  private final HarnessStore store;
  private final RealtimeEventSink realtimeEventSink;
  private final ClaimedWork claim;
  private final UUID invocationId;
  private final UUID threadId;
  private final int attempt;

  /**
   * prepare 时冻结的直接 owner-settled 事实：本 Thread 当前有效未完成票据的 delivery 为 SETTLE_OWNER_TURN。只用于摘要完整性校验 （截断
   * / 空 final 不成为摘要），其余执行路径与普通 Runtime 完全一致；执行约束不参与该判定。
   */
  private final boolean ownerSettledSummary;

  private final List<ToolBinding> bindings;
  private final ModelProcessorConfig config;

  /** 冻结到本次 Invocation 的 Provider 名称；解析 HTTP 失败重试策略时读取其覆盖。 */
  private final String providerName;

  private final Clock clock;
  private final WorkHeartbeat heartbeat;
  private final ScheduledExecutorService scheduler;
  private final Executor flushExecutor;
  private final Consumer<ModelExecution> ownerRelease;
  private final LongSupplier nanoTime;

  /** 首个非空输出 delta 的回调观察时刻（单调纳秒）；null 表示尚未观察到任何模型输出。 */
  private final AtomicReference<Long> firstOutputNanos = new AtomicReference<>();

  private final AtomicBoolean terminal = new AtomicBoolean();
  private final AtomicBoolean abandoned = new AtomicBoolean();
  private final Object monitor = new Object();
  private final ReentrantLock drainLock = new ReentrantLock();
  private final ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
  private final ArrayDeque<Pending> pending = new ArrayDeque<>();
  private long pendingPayloadBytes;
  private boolean gateOpen;
  private ModelGateway.Handle handle;

  /** monitor 保护；activation 期间 abandon 推迟 handle cancel。 */
  private boolean activationInProgress;

  /** 已接受、尚未推送的 delta（按 sequence 升序）；monitor 保护。 */
  private final List<Publish> pushBuffer = new ArrayList<>();

  private long pushBufferBytes;
  private ScheduledFuture<?> pushTimer;
  private long pushGeneration;
  private ScheduledFuture<?> checkpointTimer;
  private long checkpointGeneration;

  /** monitor 保护：追帧请求最多合并为一项待执行 checkpoint，避免公开入口无限占用 flushExecutor。 */
  private boolean checkpointRequestInFlight;

  private long lastAcceptedSequence;
  private long lastSafeSequence;

  /** 已持久化 checkpoint 覆盖到的 sequence；只在 drainLock 内读写。 */
  private long lastCheckpointSequence;

  /**
   * 完整构造器：{@code providerName} 是本次 Invocation 冻结的 Provider 身份；{@code nanoTime} 是采集流式生成计时的单调时间源，生产默认
   * {@link System#nanoTime}，测试可注入可控来源。
   */
  ModelExecution(
      HarnessStore store,
      RealtimeEventSink realtimeEventSink,
      ClaimedWork claim,
      UUID threadId,
      int attempt,
      boolean ownerSettledSummary,
      List<ToolBinding> bindings,
      ModelProcessorConfig config,
      String providerName,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      Executor flushExecutor,
      Consumer<ModelExecution> ownerRelease) {
    this(
        store,
        realtimeEventSink,
        claim,
        threadId,
        attempt,
        ownerSettledSummary,
        bindings,
        config,
        providerName,
        clock,
        scheduler,
        heartbeatWorker,
        flushExecutor,
        ownerRelease,
        System::nanoTime);
  }

  ModelExecution(
      HarnessStore store,
      RealtimeEventSink realtimeEventSink,
      ClaimedWork claim,
      UUID threadId,
      int attempt,
      boolean ownerSettledSummary,
      List<ToolBinding> bindings,
      ModelProcessorConfig config,
      String providerName,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      Executor flushExecutor,
      Consumer<ModelExecution> ownerRelease,
      LongSupplier nanoTime) {
    this.store = Objects.requireNonNull(store, "store");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.invocationId = claim.target().id();
    this.threadId = threadId;
    this.attempt = attempt;
    this.ownerSettledSummary = ownerSettledSummary;
    this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    this.config = Objects.requireNonNull(config, "config");
    this.providerName = requireProviderName(providerName);
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.flushExecutor = Objects.requireNonNull(flushExecutor, "flushExecutor");
    this.heartbeat =
        new WorkHeartbeat(
            this.store,
            this.scheduler,
            Objects.requireNonNull(heartbeatWorker, "heartbeatWorker"),
            config.leaseConfig(),
            this::abandon);
    this.ownerRelease = Objects.requireNonNull(ownerRelease, "ownerRelease");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  /** 冻结的 Provider 身份必须非空白：未知名称在策略层按“继承系统名单”处理，但 null/blank 不得进入 live 路径。 */
  private static String requireProviderName(String providerName) {
    Objects.requireNonNull(providerName, "providerName");
    if (providerName.isBlank()) {
      throw new IllegalArgumentException("providerName must not be blank");
    }
    return providerName;
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

  /** 启动 lease heartbeat；scheduler 拒绝时返回 false。 */
  boolean startHeartbeat() {
    return heartbeat.start(claim);
  }

  /**
   * Gateway admission 返回 Started 后调用。先安全 attach handle + 持久化 markRunning（失败即 abandon 并
   * LOST），随后才调用外部 {@code handle.activate()} 打开回调门控。激活仲裁：monitor 内声明 activation 开始；abandon 在开始前获胜则
   * handle 已由 abandon 取消且 activate 绝不调用；已开始后 abandon 推迟 cancel，由本方法在 activation 返回后补上，外部调用序 恒为
   * ACTIVATE -&gt; CANCEL（外部 activate / cancel 绝不持 monitor 避免与 Listener 回调死锁）。activate
   * 抛异常即激活失败：恰好一次 UNKNOWN terminal；lost / stale / Stop 获胜关闭回调门控并 cancel handle，不写任何持久化状态。
   */
  ProcessResult activate(ModelGateway.Handle startedHandle) {
    if (!attachHandle(startedHandle)) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    boolean committed;
    try {
      committed = markRunning();
    } catch (RuntimeException failure) {
      log.warn("cannot persist model running state for {}", invocationId, failure);
      committed = false;
    }
    if (!committed) {
      // handle 已 attach：abandon 会 cancel 它。
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    if (!beginActivation()) {
      // abandon 已在 activation 开始前获胜：handle 已由 abandon cancel，activate 绝不调用。
      return ProcessResult.LOST_OWNERSHIP;
    }
    // 两阶段激活：持久化 markRunning 落地之后、打开自身 Listener 回调门控之前，才允许 Gateway 打开回调门控或启动外部执行。
    RuntimeException activationError = null;
    try {
      startedHandle.activate();
    } catch (RuntimeException failure) {
      activationError = failure;
    }
    if (finishActivation()) {
      // activation 期间 abandon 获胜且推迟了 cancel：这里补上 deferred cancel（外部调用序 ACTIVATE -> CANCEL）。
      cancelHandle();
      return ProcessResult.LOST_OWNERSHIP;
    }
    if (activationError != null) {
      // 异常渲染（toString）绝不能绕过状态转换：先收敛持久化 UNKNOWN，再记录。
      ProcessResult result =
          activationFailure(
              new ModelInvocationError(
                  ProviderErrorKind.TRANSIENT,
                  "model gateway activation failed; provider outcome cannot be confirmed"));
      log.warn(
          "model gateway activation failed for {}: {}",
          invocationId,
          ProcessorExceptions.describe(activationError));
      return result;
    }
    Applied applied = Applied.PROGRESSED;
    drainLock.lock();
    try {
      List<Pending> buffered;
      synchronized (monitor) {
        if (abandoned.get()) {
          applied = Applied.LOST;
          buffered = List.of();
        } else {
          gateOpen = true;
          buffered = List.copyOf(pending);
          pending.clear();
          pendingPayloadBytes = 0;
        }
      }
      for (Pending signal : buffered) {
        applied = applySignal(signal);
        if (applied != Applied.PROGRESSED) {
          break;
        }
      }
    } finally {
      drainLock.unlock();
    }
    if (applied == Applied.LOST) {
      // 兜底：gate 打开前 abandon 竞态获胜时 handle 已由 abandon 取消，这里只补 deferred cancel。
      cancelHandle();
      return ProcessResult.LOST_OWNERSHIP;
    }
    if (applied != Applied.PROGRESSED) {
      // 缓冲的 terminal/retry 信号在激活期间已落地：本地 execution 已结束。
      abandon();
      return applied == Applied.RETRY ? ProcessResult.RESCHEDULED : ProcessResult.TERMINATED;
    }
    return ProcessResult.STARTED;
  }

  /** monitor 内声明 activation 开始；abandon 已获胜时返回 false（activate 绝不调用，handle 已由 abandon 取消）。 */
  private boolean beginActivation() {
    synchronized (monitor) {
      if (abandoned.get()) {
        return false;
      }
      activationInProgress = true;
      return true;
    }
  }

  /** monitor 内结束 activation 阶段并返回当前是否已 abandoned；abandoned 时 handle cancel 由调用方在 monitor 外补上。 */
  private boolean finishActivation() {
    synchronized (monitor) {
      activationInProgress = false;
      return abandoned.get();
    }
  }

  /**
   * Gateway 激活失败（{@code handle.activate} 抛异常）：执行结果无法确认，强制恰好一次 UNKNOWN terminal。恶意 / 异常 handle 可能在
   * activate() 内同步投递 terminal 回调（gate 未开，只进缓冲且 terminal 已被 claim）——一律丢弃缓冲信号并强制落地 UNKNOWN，绝不把执行留在
   * RUNNING 等 lease 恢复；随后 abandon。
   */
  private ProcessResult activationFailure(ModelInvocationError error) {
    List<Publish> publishes = new ArrayList<>();
    Applied applied = Applied.LOST;
    drainLock.lock();
    try {
      synchronized (monitor) {
        if (abandoned.get()) {
          return ProcessResult.LOST_OWNERSHIP;
        }
        cancelTimersLocked();
        pending.clear();
        pendingPayloadBytes = 0;
        clearPushBufferLocked();
        terminal.set(true);
        applied = finishUnknownLocked(error, publishes);
      }
      publishAll(publishes);
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist activation failure terminal for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      applied = Applied.LOST;
    } finally {
      drainLock.unlock();
    }
    abandon();
    return applied == Applied.LOST ? ProcessResult.LOST_OWNERSHIP : ProcessResult.TERMINATED;
  }

  /**
   * monitor 内只做决定与赋值：若 execution 已 abandoned（heartbeat / close / cancel 竞态）或 handle 已存在则返回 false
   * （绝不 markRunning）；外部 {@link ModelGateway.Handle#cancel} 必须在 monitor 之外调用（cancel 可能同步触发 Listener
   * 回调或阻塞等待确认，锁内调用会与需要 monitor 的回调路径死锁），因此锁内只置标记，锁外统一 best-effort cancel。 activation 已开始后的 abandon
   * 由 {@link #abandon} 推迟 cancel，与 {@link #activate} 的 deferred cancel 合计仍恰好一次。
   */
  private boolean attachHandle(ModelGateway.Handle startedHandle) {
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
  public void onEvent(ProviderStreamEvent event) {
    if (terminal.get() || abandoned.get()) {
      return;
    }
    if (isModelOutputDelta(event) && firstOutputNanos.get() == null) {
      // 在回调入口采样首非空输出的观察时刻：与 drain/持久化等待无关，随后才排队缓冲。
      firstOutputNanos.compareAndSet(null, nanoTime.getAsLong());
    }
    deliver(new Pending(PendingKind.EVENT, event, null, null, 0L));
  }

  @Override
  public void onSucceeded(ProviderCompletion completion) {
    if (abandoned.get() || !terminal.compareAndSet(false, true)) {
      return;
    }
    // 在回调入口采样成功观察时刻作为计时的右端点：随信号携带，之后的持久化/发布等待不计入。
    deliver(new Pending(PendingKind.SUCCEEDED, null, completion, null, nanoTime.getAsLong()));
  }

  @Override
  public void onFailed(ModelInvocationError error) {
    if (abandoned.get() || !terminal.compareAndSet(false, true)) {
      return;
    }
    deliver(new Pending(PendingKind.FAILED, null, null, error, 0L));
  }

  @Override
  public void onUnknown(ModelInvocationError error) {
    if (abandoned.get() || !terminal.compareAndSet(false, true)) {
      return;
    }
    deliver(new Pending(PendingKind.UNKNOWN, null, null, error, 0L));
  }

  /**
   * 关闭 listener（丢弃缓冲）、cancel handle（activation 已开始时推迟到 activate 返回后）、停止 heartbeat 并从 registry
   * 释放；幂等。
   */
  void abandon() {
    if (!abandoned.compareAndSet(false, true)) {
      return;
    }
    heartbeat.stop();
    boolean cancelNow;
    synchronized (monitor) {
      cancelTimersLocked();
      pending.clear();
      pendingPayloadBytes = 0;
      clearPushBufferLocked();
      cancelNow = !activationInProgress;
    }
    if (cancelNow) {
      cancelHandle();
    }
    ownerRelease.accept(this);
  }

  private void checkPendingCapacityLocked(ProviderStreamEvent event) {
    if (pending.size() >= config.streamConfig().maxBatchEvents()) {
      throw new BatchInfrastructureException(
          "activation pending capacity exceeded: " + pending.size());
    }
    long eventBytes = ModelStreamConfig.eventPayloadBytes(event);
    long newBytes;
    try {
      newBytes = Math.addExact(pendingPayloadBytes, eventBytes);
    } catch (ArithmeticException overflow) {
      throw new BatchInfrastructureException("activation pending payload bytes overflow", overflow);
    }
    if (!pending.isEmpty() && newBytes > config.streamConfig().maxBatchBytes()) {
      throw new BatchInfrastructureException(
          "activation pending payload bytes exceeded: " + newBytes);
    }
    pendingPayloadBytes = newBytes;
  }

  private void deliver(Pending signal) {
    boolean isTerminalSignal = signal.kind() != PendingKind.EVENT;
    drainLock.lock();
    try {
      if (abandoned.get()) {
        return;
      }
      synchronized (monitor) {
        if (!gateOpen) {
          if (!isTerminalSignal) {
            checkPendingCapacityLocked(signal.event());
            pending.add(signal);
            return;
          } else {
            pending.add(signal);
            return;
          }
        }
      }
      applySignal(signal);
    } catch (RuntimeException failure) {
      handleSignalFailure(failure, isTerminalSignal);
    } finally {
      drainLock.unlock();
    }
  }

  private Applied handleSignalFailure(RuntimeException failure, boolean isTerminalSignal) {
    if (failure instanceof BatchInfrastructureException batchError) {
      log.warn(
          "model stream batching failed for {}: {}",
          invocationId,
          ProcessorExceptions.describe(batchError));
      abandon();
      return Applied.LOST;
    }
    if (isTerminalSignal) {
      log.warn(
          "cannot persist model terminal for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      abandon();
      return Applied.LOST;
    }
    return deliverFailure(
        new ModelInvocationError(
            ProviderErrorKind.INVALID_RESPONSE, message(failure, "invalid provider callback")));
  }

  /**
   * 统一信号处理边界：在 drainLock 保护下执行单个信号。
   *
   * <p>异常严格分类隔离： 1. 批次基础设施/内部不变量失败（{@link BatchInfrastructureException}）：abandon execution，保留
   * durable 状态供 lease recovery 收敛，不得写 FAILED/retry，返回 LOST； 2.
   * 终态信号（SUCCEEDED/FAILED/UNKNOWN）持久化失败：记录日志并 abandon，返回 LOST； 3. 非法 Provider 事件（EVENT）：收敛为
   * INVALID_RESPONSE terminal 或 retry；若该收敛持久化亦失败则 abandon，返回对应结果。 保证任何异常绝不逃出 ModelExecution 边界。
   */
  private Applied applySignal(Pending signal) {
    if (abandoned.get()) {
      return Applied.LOST;
    }
    boolean isTerminalSignal = signal.kind() != PendingKind.EVENT;
    try {
      Applied applied = processSignal(signal);
      if (applied == Applied.LOST || applied != Applied.PROGRESSED) {
        abandon();
      }
      return applied;
    } catch (RuntimeException failure) {
      return handleSignalFailure(failure, isTerminalSignal);
    }
  }

  private Applied processSignal(Pending signal) {
    return switch (signal.kind()) {
      case EVENT -> processEvent(signal.event());
      case SUCCEEDED -> processSucceeded(signal.completion(), signal.successNanos());
      case FAILED -> processFailed(signal.error());
      case UNKNOWN -> processUnknown(signal.error());
    };
  }

  /**
   * 处理一个 stream delta：只做本地缓冲，不触达数据库。delta 进入推送缓冲，达到事件数/字节上界立即推送，否则由单次无防抖的推送定时器在 {@link
   * ModelStreamConfig#pushWindow()} 内推送；text/thinking delta 更新 lastSafeSequence 并保证 checkpoint
   * 定时器已武装（固定 deadline，不 debounce）。整个处理与发布在单 drain owner 内顺序执行。
   *
   * <p>推送不等待提交，所有权损失因此不在每个 token 上检测，而收敛到 checkpoint 提交围栏、terminal 围栏或 heartbeat 丢失 lease 后触发的
   * {@link #abandon}；abandon 后立即停止发布。
   */
  private Applied processEvent(ProviderStreamEvent event) {
    long eventBytes = ModelStreamConfig.eventPayloadBytes(event);
    ModelStreamConfig streamConfig = config.streamConfig();
    List<Publish> toPush = List.of();
    boolean armPush = false;
    long pushArmGeneration = 0;
    boolean armCheckpoint = false;
    long checkpointArmGeneration = 0;
    synchronized (monitor) {
      if (abandoned.get()) {
        return Applied.LOST;
      }
      long sequence = nextSequence();
      accumulator.append(event);
      if (isSafeDelta(event)) {
        lastSafeSequence = sequence;
        if (checkpointTimer == null) {
          armCheckpoint = true;
          checkpointArmGeneration = checkpointGeneration;
        }
      }
      pushBuffer.add(new Publish(event, sequence));
      pushBufferBytes += eventBytes;
      if (pushBuffer.size() >= streamConfig.maxBatchEvents()
          || pushBufferBytes >= streamConfig.maxBatchBytes()) {
        toPush = takePushBufferLocked();
      } else if (pushTimer == null) {
        armPush = true;
        pushArmGeneration = pushGeneration;
      }
    }
    publishAll(toPush);
    if (armPush) {
      schedulePushTimer(pushArmGeneration);
    }
    if (armCheckpoint) {
      scheduleCheckpointTimer(checkpointArmGeneration);
    }
    return abandoned.get() ? Applied.LOST : Applied.PROGRESSED;
  }

  private Applied processSucceeded(ProviderCompletion completion, long successNanos) {
    // Renderer 属于可插拔 Tool 代码；即使目录与实现都应为内存纯函数，也不能在 execution
    // monitor 内调用。terminal CAS 与 drainLock 已阻止后续 Provider 信号，渲染期间若丢失
    // lease，abandon 可立即取得 monitor，随后下方围栏会拒绝提交。
    ProviderResponse responseWithHistoryActions =
        ToolHistoryActions.freeze(
            completion.response(), bindings, config.toolHistoryActionResolver());
    ProviderCompletion durableCompletion =
        responseWithHistoryActions == completion.response()
            ? completion
            : new ProviderCompletion(responseWithHistoryActions, completion.replayState());
    List<Publish> publishes = new ArrayList<>();
    Applied applied;
    synchronized (monitor) {
      if (abandoned.get()) {
        return Applied.LOST;
      }
      applied = finishSuccessLocked(durableCompletion, successNanos, publishes);
    }
    if (applied == Applied.LOST) {
      return Applied.LOST;
    }
    publishAll(publishes);
    return applied;
  }

  private Applied processFailed(ModelInvocationError error) {
    List<Publish> publishes = new ArrayList<>();
    Applied applied;
    synchronized (monitor) {
      if (abandoned.get()) {
        return Applied.LOST;
      }
      applied = finishFailureLocked(error, publishes);
    }
    if (applied == Applied.LOST) {
      return Applied.LOST;
    }
    publishAll(publishes);
    return applied;
  }

  private Applied processUnknown(ModelInvocationError error) {
    List<Publish> publishes = new ArrayList<>();
    Applied applied;
    synchronized (monitor) {
      if (abandoned.get()) {
        return Applied.LOST;
      }
      applied = finishUnknownLocked(error, publishes);
    }
    if (applied == Applied.LOST) {
      return Applied.LOST;
    }
    publishAll(publishes);
    return applied;
  }

  private void schedulePushTimer(long targetGeneration) {
    ScheduledFuture<?> future;
    try {
      future =
          scheduler.schedule(
              () -> submitPushIfCurrent(targetGeneration),
              config.streamConfig().pushWindow().toMillis(),
              TimeUnit.MILLISECONDS);
    } catch (RuntimeException rejected) {
      if (claimPushRejectionLocked(targetGeneration)) {
        log.warn(
            "model push timer scheduling rejected for {}: {}",
            invocationId,
            ProcessorExceptions.describe(rejected));
        abandon();
      }
      return;
    }
    synchronized (monitor) {
      if (abandoned.get() || terminal.get() || targetGeneration != pushGeneration) {
        future.cancel(false);
        return;
      }
      pushTimer = future;
    }
  }

  private void scheduleCheckpointTimer(long targetGeneration) {
    ScheduledFuture<?> future;
    try {
      future =
          scheduler.schedule(
              () -> submitCheckpointIfCurrent(targetGeneration),
              config.streamConfig().checkpointInterval().toMillis(),
              TimeUnit.MILLISECONDS);
    } catch (RuntimeException rejected) {
      if (claimCheckpointRejectionLocked(targetGeneration)) {
        log.warn(
            "model checkpoint timer scheduling rejected for {}: {}",
            invocationId,
            ProcessorExceptions.describe(rejected));
        abandon();
      }
      return;
    }
    synchronized (monitor) {
      if (abandoned.get() || terminal.get() || targetGeneration != checkpointGeneration) {
        future.cancel(false);
        return;
      }
      checkpointTimer = future;
    }
  }

  /**
   * 推送定时器到期后先校验代际/终态再投递 executor；executor 拒绝时重新在 monitor 内校验代际并原子认领 abandon。
   *
   * <p>检查与 execute 之间 generation 可能被容量 flush 推进、terminal 可能已被 provider 回调认领；此时 rejection
   * 属于旧代际，绝不能误伤 当前 execution。
   */
  private void submitPushIfCurrent(long generation) {
    synchronized (monitor) {
      if (abandoned.get() || terminal.get() || generation != pushGeneration) {
        return;
      }
    }
    try {
      flushExecutor.execute(() -> deliverPush(generation));
    } catch (RuntimeException rejected) {
      if (claimPushRejectionLocked(generation)) {
        log.warn(
            "model push executor rejected for {}: {}",
            invocationId,
            ProcessorExceptions.describe(rejected));
        abandon();
      }
    }
  }

  /** checkpoint 定时器与推送定时器同理：只有当前代际的 rejection 才能认领 abandon。 */
  private void submitCheckpointIfCurrent(long generation) {
    synchronized (monitor) {
      if (abandoned.get() || terminal.get() || generation != checkpointGeneration) {
        return;
      }
    }
    try {
      flushExecutor.execute(() -> deliverCheckpoint(generation, false));
    } catch (RuntimeException rejected) {
      if (claimCheckpointRejectionLocked(generation)) {
        log.warn(
            "model checkpoint executor rejected for {}: {}",
            invocationId,
            ProcessorExceptions.describe(rejected));
        abandon();
      }
    }
  }

  /** 仅当前 push 代际仍未被 flush/terminal/cancel 推进时，原子认领一次 abandon。 */
  private boolean claimPushRejectionLocked(long generation) {
    if (abandoned.get() || terminal.get() || generation != pushGeneration) {
      return false;
    }
    return terminal.compareAndSet(false, true);
  }

  /** 仅当前 checkpoint 代际仍未被后续 checkpoint/terminal/cancel 推进时，原子认领一次 abandon。 */
  private boolean claimCheckpointRejectionLocked(long generation) {
    if (abandoned.get() || terminal.get() || generation != checkpointGeneration) {
      return false;
    }
    return terminal.compareAndSet(false, true);
  }

  /** 取出推送缓冲并取消推送定时器；monitor 内调用。 */
  private List<Publish> takePushBufferLocked() {
    cancelPushTimerLocked();
    List<Publish> taken = List.copyOf(pushBuffer);
    pushBuffer.clear();
    pushBufferBytes = 0;
    return taken;
  }

  private void clearPushBufferLocked() {
    pushBuffer.clear();
    pushBufferBytes = 0;
  }

  private void cancelPushTimerLocked() {
    if (pushTimer != null) {
      pushTimer.cancel(false);
      pushTimer = null;
    }
    pushGeneration++;
  }

  private void cancelCheckpointTimerLocked() {
    if (checkpointTimer != null) {
      checkpointTimer.cancel(false);
      checkpointTimer = null;
    }
    checkpointGeneration++;
  }

  private void cancelTimersLocked() {
    cancelPushTimerLocked();
    cancelCheckpointTimerLocked();
  }

  /** 推送定时器到期：发布当前代际的缓冲；过期代际与终态后为 no-op。 */
  void deliverPush(long generation) {
    drainLock.lock();
    try {
      List<Publish> toPush;
      synchronized (monitor) {
        if (abandoned.get()
            || terminal.get()
            || generation != pushGeneration
            || pushBuffer.isEmpty()) {
          return;
        }
        toPush = takePushBufferLocked();
      }
      publishAll(toPush);
    } finally {
      drainLock.unlock();
    }
  }

  /**
   * 追帧请求：仅当本 execution 仍是 active owner 且 attempt 匹配时，合并安排一次立即 checkpoint。
   *
   * <p>best-effort：每个 execution 最多一项 in-flight 追帧任务；重复请求在已有任务未完成时返回 {@code true}（已有请求会覆盖当前累计状态）。
   * executor 拒绝只返回 {@code false}，绝不 cancel/abandon execution，也不影响周期 checkpoint 与
   * terminal。终态、取消/abandon 或 attempt 不匹配返回 {@code false}。
   */
  boolean requestCheckpoint(int requestedAttempt) {
    if (requestedAttempt != attempt) {
      return false;
    }
    synchronized (monitor) {
      if (abandoned.get() || terminal.get()) {
        return false;
      }
      if (checkpointRequestInFlight) {
        return true;
      }
      checkpointRequestInFlight = true;
    }
    try {
      flushExecutor.execute(this::runCatchUpCheckpoint);
      return true;
    } catch (RuntimeException rejected) {
      synchronized (monitor) {
        checkpointRequestInFlight = false;
      }
      log.warn(
          "catch-up checkpoint rejected for {}: {}",
          invocationId,
          ProcessorExceptions.describe(rejected));
      return false;
    }
  }

  /** 追帧任务在 finally 释放本 execution 自己的 in-flight 标记；新 attempt 是独立 execution，互不影响。 */
  private void runCatchUpCheckpoint() {
    try {
      deliverCheckpoint(0, true);
    } finally {
      synchronized (monitor) {
        checkpointRequestInFlight = false;
      }
    }
  }

  /**
   * 持久化 checkpoint（定时器到期或追帧强制）。事务只锁 ModelInvocation 与 claimed Work（Model -&gt; Work），不取执行树锁：它只更新本
   * Invocation 一行，不唤醒 Thread；围栏仍重校验 RUNNING + attempt + lease。只有比上次落库前进时才写库；强制请求在无前进时重发已落库的
   * checkpoint 事件，使等待方立即得到答复。提交失败或所有权丢失即 abandon，且不发布。
   */
  void deliverCheckpoint(long generation, boolean forced) {
    drainLock.lock();
    try {
      StreamCheckpoint checkpoint = null;
      synchronized (monitor) {
        if (abandoned.get() || terminal.get() || (!forced && generation != checkpointGeneration)) {
          return;
        }
        cancelCheckpointTimerLocked();
        if (lastSafeSequence > lastCheckpointSequence) {
          checkpoint =
              new StreamCheckpoint(
                  attempt, lastSafeSequence, accumulator.text(), accumulator.thinking());
        }
      }
      if (checkpoint == null) {
        if (forced && lastCheckpointSequence > 0) {
          publishCheckpointEvent(lastCheckpointSequence);
        }
        return;
      }
      if (!persistCheckpoint(checkpoint)) {
        abandon();
        return;
      }
      lastCheckpointSequence = checkpoint.sequence();
      if (!abandoned.get()) {
        publishCheckpointEvent(checkpoint.sequence());
      }
    } finally {
      drainLock.unlock();
    }
  }

  private boolean persistCheckpoint(StreamCheckpoint checkpoint) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                if (model == null || tx.lockClaimedWork(claim).isEmpty()) {
                  return false;
                }
                if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                  return false;
                }
                tx.updateModelInvocation(model.checkpoint(checkpoint, now));
                return true;
              }));
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model checkpoint for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return false;
    }
  }

  private void publishCheckpointEvent(long sequence) {
    dispatchRealtime(
        List.of(
            new RealtimeEvent.ModelCheckpoint(
                threadId, invocationId, attempt, sequence, clock.instant())));
  }

  private Applied finishSuccessLocked(
      ProviderCompletion completion, long successNanos, List<Publish> publishes) {
    cancelTimersLocked();
    ProviderResponse response = completion.response();
    // 仅在成功终态冻结 Harness 观测的生成计时；失败/重试/UNKNOWN/abandon 绝不虚构时长。
    Long decodeDurationMillis = decodeDurationMillis(successNanos);
    if (response != null && response.stopReason() == GenerationStopReason.FILTERED) {
      ProviderResponse validatedResponse;
      try {
        validatedResponse = ModelResponseValidator.validate(response);
      } catch (RuntimeException failure) {
        log.warn(
            "invalid filtered provider response for invocation {}: {}",
            invocationId,
            ProcessorExceptions.describe(failure));
        return finishFailureLocked(
            new ModelInvocationError(
                ProviderErrorKind.INVALID_RESPONSE, message(failure, "invalid provider response")),
            publishes);
      }
      if (ownerSettledSummary) {
        // 过滤结果明确不可恢复：既不能当作摘要，也不无依据地重放同一请求（INVALID_REQUEST 不属于可重试 kind）。
        log.info("compaction summary was filtered by the provider for invocation {}", invocationId);
        return finishFailureLocked(
            new ModelInvocationError(
                ProviderErrorKind.INVALID_REQUEST,
                "compaction model response was filtered and cannot be used as a summary"),
            publishes);
      }
      ProviderResponse durableResponse =
          validatedResponse.withDecodeDurationMillis(decodeDurationMillis);
      boolean committed = safeTerminal(() -> commitSuccess(durableResponse, null, null));
      if (!committed) {
        return Applied.LOST;
      }
      clearPushBufferLocked();
      return Applied.TERMINAL;
    }
    ModelStreamAccumulator.PreparedCompletion prepared;
    ProviderResponse validatedResponse;
    try {
      prepared = accumulator.prepareComplete(response);
      validatedResponse = ModelResponseValidator.validate(prepared.response());
    } catch (RuntimeException failure) {
      log.warn(
          "invalid provider response for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return finishFailureLocked(
          new ModelInvocationError(
              ProviderErrorKind.INVALID_RESPONSE, message(failure, "invalid provider response")),
          publishes);
    }
    ModelStreamAccumulator.Completion accCompletion = prepared.apply();
    List<Publish> gapItems = new ArrayList<>();
    for (ProviderStreamEvent gap : accCompletion.gaps()) {
      long seq = nextSequence();
      boolean isSafe = isSafeDelta(gap);
      if (isSafe) {
        lastSafeSequence = seq;
      }
      gapItems.add(new Publish(gap, seq));
    }

    String text = accumulator.text();
    String thinking = accumulator.thinking();
    if (ownerSettledSummary && invalidCompactionFinal(validatedResponse, text)) {
      // 摘要完整性：截断的结果（LENGTH，含截断的 tool intent）与空 final 都不能成为摘要，也绝不执行截断的 tool call。走既有调用重试
      // （同一预算、无第二套预算），上限耗尽即 FAILED；父 Join 冻结前绝不产生成功回执，也不把部分输出当摘要交给父 Agent。
      String reason =
          validatedResponse.stopReason() == GenerationStopReason.LENGTH
              ? "compaction model response was truncated before the summary completed"
              : "compaction model returned an empty summary";
      log.info(
          "invalid compaction summary for invocation {} (attempt {}): {}",
          invocationId,
          attempt,
          reason);
      return finishFailureLocked(
          new ModelInvocationError(ProviderErrorKind.INVALID_RESPONSE, reason), publishes);
    }
    StreamCheckpoint finalCheckpoint = null;
    if (!text.isEmpty() || !thinking.isEmpty()) {
      finalCheckpoint = new StreamCheckpoint(attempt, lastSafeSequence, text, thinking);
    }
    final StreamCheckpoint checkpointToCommit = finalCheckpoint;

    ProviderReplayState replayState = null;
    if ((validatedResponse.stopReason() == GenerationStopReason.COMPLETE
            || validatedResponse.stopReason() == GenerationStopReason.LENGTH
            // CONTINUE 是撤下工具意图的续写终止态：下一请求必须原样回放上游 native 字段。
            || validatedResponse.stopReason() == GenerationStopReason.CONTINUE)
        && validatedResponse.toolCallDiagnostics().isEmpty()) {
      replayState = completion.replayState();
    }
    final ProviderReplayState replayStateToCommit = replayState;

    ProviderResponse durableResponse =
        validatedResponse.withDecodeDurationMillis(decodeDurationMillis);
    boolean committed =
        safeTerminal(() -> commitSuccess(durableResponse, checkpointToCommit, replayStateToCommit));
    if (!committed) {
      return Applied.LOST;
    }

    // 终态已提交：补发尚未推送的缓冲与终态补齐的 gap delta（均按 sequence 升序）。
    publishes.addAll(pushBuffer);
    publishes.addAll(gapItems);
    clearPushBufferLocked();
    return Applied.TERMINAL;
  }

  private Applied finishFailureLocked(ModelInvocationError error, List<Publish> publishes) {
    cancelTimersLocked();
    InvocationRetryDecision decision =
        InvocationRetryDecision.decide(
            config.retryPolicyProvider().retryPolicy(), isRetryable(error), attempt);
    if (decision.retry()) {
      Duration delay = decision.delay();
      boolean committed = safeTerminal(() -> commitRetry(delay, error));
      if (committed) {
        log.info(
            "scheduled model retry for invocation {} (attempt {}) in {}",
            invocationId,
            attempt + 1,
            delay);
        clearPushBufferLocked();
        return Applied.RETRY;
      }
      return Applied.LOST;
    }
    TerminalKind kind =
        error.kind() == ProviderErrorKind.CANCELLED ? TerminalKind.CANCELLED : TerminalKind.FAILED;
    boolean committed = safeTerminal(() -> commitTerminal(kind, error));
    if (committed) {
      clearPushBufferLocked();
      return Applied.TERMINAL;
    }
    return Applied.LOST;
  }

  /**
   * 模型失败是否重试：先做统一候选判定（OVERFLOW/CANCELLED 排除，非 HTTP AUTHENTICATION/BILLING 排除），带 HTTP 状态时由现读的 {@link
   * fun.fengwk.kkstudio.harness.runtime.retry.ModelHttpErrorPolicy} 唯一决定，其余非 HTTP
   * TRANSIENT/INVALID_RESPONSE 走 共享 {@link InvocationRetryPolicy} 预算。
   */
  private boolean isRetryable(ModelInvocationError error) {
    if (!error.retryCandidate()) {
      return false;
    }
    if (error.httpStatus() != null) {
      return config.httpErrorPolicyProvider().policy(providerName).allowsRetry(error.httpStatus());
    }
    return true;
  }

  /**
   * 压缩子 final 完整性：LENGTH 一律是被输出上限截断的结果（即使 provider 同时给出 tool call，截断的 tool intent 也不是可执行意图），
   * COMPLETE 只有在无 tool calls 且文本为空白时才是空摘要（纯空白与空串同样是不可用摘要，绝不能冻结成父 receipt 后被空摘要校验拒绝）。 两者都不是可用摘要。
   *
   * <p>COMPLETE / CONTINUE 的合法 tool 回合不在此列：它们按普通 Runtime 语义继续推进（模型得到真实 tool result 后再产出 final）。
   */
  private static boolean invalidCompactionFinal(ProviderResponse response, String text) {
    if (response.stopReason() == GenerationStopReason.LENGTH) {
      return true;
    }
    return response.stopReason() == GenerationStopReason.COMPLETE
        && response.toolCalls().isEmpty()
        && text.isBlank();
  }

  private Applied finishUnknownLocked(ModelInvocationError error, List<Publish> publishes) {
    cancelTimersLocked();
    boolean committed = safeTerminal(() -> commitTerminal(TerminalKind.UNKNOWN, error));
    if (committed) {
      clearPushBufferLocked();
      return Applied.TERMINAL;
    }
    return Applied.LOST;
  }

  private boolean markRunning() {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadTreeLocks.lockForThread(tx, threadId);
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
              if (model == null || tx.lockClaimedWork(claim).isEmpty()) {
                return false;
              }
              if (model.status() != ModelInvocationStatus.DISPATCHING
                  || model.attempt() != attempt - 1) {
                return false;
              }
              tx.updateModelInvocation(model.markRunning(now));
              tx.updateThread(thread.touchVersion(now));
              return true;
            }));
  }

  /** RUNNING -&gt; READY：记录瞬态失败并排程重试：{@code retryAt = failedAt + delay}，Work 按同一 delay 重排。 */
  private boolean commitRetry(Duration delay, ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              Instant now = tx.now();
              ThreadTreeLocks.lockForThread(tx, threadId);
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
              if (model == null || tx.lockClaimedWork(claim).isEmpty()) {
                return false;
              }
              if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                return false;
              }
              Instant failedAt = now;
              Instant retryAt = now.plus(delay);
              ModelAttemptFailure failure =
                  new ModelAttemptFailure(
                      attempt,
                      lastSafeSequence,
                      accumulator.text(),
                      accumulator.thinking(),
                      error,
                      failedAt,
                      retryAt);
              tx.updateModelInvocation(model.retryReady(failure, failedAt));
              tx.updateThread(thread.touchVersion(failedAt));
              tx.rescheduleWork(claim, delay);
              return true;
            }));
  }

  private boolean commitSuccess(
      ProviderResponse response,
      StreamCheckpoint finalCheckpoint,
      ProviderReplayState replayState) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadTreeLocks.lockForThread(tx, threadId);
                ThreadState thread = tx.lockThread(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                if (model == null) {
                  return false;
                }
                if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                  return false;
                }
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.lockClaimedWork(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.updateModelInvocation(
                    model.succeed(response, finalCheckpoint, replayState, now));
                tx.updateThread(thread.touchVersion(now));
                tx.completeWork(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  private boolean commitTerminal(TerminalKind kind, ModelInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadTreeLocks.lockForThread(tx, threadId);
                ThreadState thread = tx.lockThread(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                if (model == null) {
                  return false;
                }
                if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                  return false;
                }
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.lockClaimedWork(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                String text = accumulator.text();
                String thinking = accumulator.thinking();
                StreamCheckpoint finalCheckpoint = null;
                if (!text.isEmpty() || !thinking.isEmpty()) {
                  finalCheckpoint = new StreamCheckpoint(attempt, lastSafeSequence, text, thinking);
                }
                ModelInvocation next =
                    switch (kind) {
                      case FAILED -> model.fail(error, finalCheckpoint, now);
                      case CANCELLED -> model.cancel(error, finalCheckpoint, now);
                      case UNKNOWN -> model.unknown(error, finalCheckpoint, now);
                    };
                tx.updateModelInvocation(next);
                tx.updateThread(thread.touchVersion(now));
                tx.completeWork(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  private Applied deliverFailure(ModelInvocationError error) {
    drainLock.lock();
    try {
      List<Publish> publishes = new ArrayList<>();
      Applied applied;
      synchronized (monitor) {
        if (abandoned.get()) {
          return Applied.LOST;
        }
        applied = finishFailureLocked(error, publishes);
      }
      if (applied == Applied.LOST) {
        abandon();
        return Applied.LOST;
      }
      publishAll(publishes);
      abandon();
      return applied;
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model failure for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      abandon();
      return Applied.LOST;
    } finally {
      drainLock.unlock();
    }
  }

  private boolean safeTerminal(BooleanSupplier action) {
    try {
      return action.getAsBoolean();
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model terminal for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return false;
    }
  }

  /**
   * 按 sequence 顺序发布一批 delta。
   *
   * <p>发布以 {@link ModelStreamConfig} 的批次上界分块（事件数 + 增量载荷字节），每个分块恰好调用一次 {@link
   * RealtimeEventSink#appendAll}：终态补齐的大量 gap delta 或单个超大事件都不会构造无界列表或单次无界调用。分块失败按调用隔离，只降低实时体验，
   * 绝不改变已提交的 durable 状态或终态。
   */
  private void publishAll(List<Publish> publishes) {
    ModelStreamConfig publishConfig = config.streamConfig();
    List<RealtimeEvent> chunk = new ArrayList<>();
    long chunkPayloadBytes = 0;
    for (Publish publish : publishes) {
      if (abandoned.get()) {
        // cancel / lease loss 已获胜：已在 sink 内的块允许完成，后续块绝不再发布。
        return;
      }
      long payloadBytes = ModelStreamConfig.eventPayloadBytes(publish.event());
      if (!chunk.isEmpty()
          && (chunk.size() >= publishConfig.maxBatchEvents()
              || chunkPayloadBytes + payloadBytes > publishConfig.maxBatchBytes())) {
        dispatchRealtime(chunk);
        chunk = new ArrayList<>();
        chunkPayloadBytes = 0;
      }
      chunk.add(toRealtime(publish));
      chunkPayloadBytes += payloadBytes;
    }
    dispatchRealtime(chunk);
  }

  /** 单次有界批量发布；sink 异常在此隔离，绝不影响 durable 状态与调用方返回的终态。 */
  private void dispatchRealtime(List<RealtimeEvent> events) {
    if (events.isEmpty() || abandoned.get()) {
      return;
    }
    try {
      realtimeEventSink.appendAll(events);
    } catch (RuntimeException failure) {
      log.warn("realtime model delta projection failed for invocation {}", invocationId, failure);
    }
  }

  private RealtimeEvent toRealtime(Publish publish) {
    return new RealtimeEvent.ModelDelta(
        threadId, invocationId, attempt, publish.sequence(), publish.event(), clock.instant());
  }

  private void cancelHandle() {
    ModelGateway.Handle current;
    synchronized (monitor) {
      current = handle;
      handle = null;
    }
    if (current != null) {
      cancelBestEffort(current);
    }
  }

  /** Gateway Handle 取消是契约级幂等；异常只记录，不影响本地清理。 */
  private void cancelBestEffort(ModelGateway.Handle target) {
    try {
      target.cancel();
    } catch (RuntimeException failure) {
      log.warn("cannot cancel local model execution handle for {}", invocationId, failure);
    }
  }

  private long nextSequence() {
    if (lastAcceptedSequence == Long.MAX_VALUE) {
      throw new IllegalStateException("model delta sequence overflow");
    }
    return ++lastAcceptedSequence;
  }

  private static String message(Throwable failure, String fallback) {
    if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
      return fallback;
    }
    return failure.getMessage();
  }

  /** 仅非空 text / thinking delta 能够更新 lastSafeSequence 并生成 durable checkpoint。 */
  private static boolean isSafeDelta(ProviderStreamEvent event) {
    if (event instanceof ProviderStreamEvent.TextDelta delta) {
      return !delta.text().isEmpty();
    }
    if (event instanceof ProviderStreamEvent.ThinkingDelta delta) {
      return !delta.text().isEmpty();
    }
    return false;
  }

  /**
   * 是否为可计入生成计时的模型输出 delta：非空 text / thinking，或携带 name / 非空 argumentsJson 的 tool call delta（其中单纯
   * id-only 的 fragment 不算模型输出）。{@link ProviderStreamEvent.ToolCallDelta} 已保证 name 非 blank，因此非 null
   * 即代表模型输出了工具名。
   */
  private static boolean isModelOutputDelta(ProviderStreamEvent event) {
    if (event instanceof ProviderStreamEvent.TextDelta delta) {
      return !delta.text().isEmpty();
    }
    if (event instanceof ProviderStreamEvent.ThinkingDelta delta) {
      return !delta.text().isEmpty();
    }
    if (event instanceof ProviderStreamEvent.ToolCallDelta delta) {
      return delta.name() != null
          || (delta.argumentsJson() != null && !delta.argumentsJson().isEmpty());
    }
    return false;
  }

  /**
   * 本 attempt 的流式生成计时：首个非空输出 delta 的观察时刻到成功回调观察时刻；任一端点缺失（无输出、abandon / failed / UNKNOWN 未成功）时返回
   * null，绝不虚构时长。两个端点都在回调入口采样，之后的 drain / 持久化等待不计入；若回调竞态或异常时间源使 窗口为负，收敛为 0。
   */
  private Long decodeDurationMillis(long successNanos) {
    Long startNanos = firstOutputNanos.get();
    if (startNanos == null) {
      return null;
    }
    return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, successNanos - startNanos));
  }

  /** 内部批次持久化或内部不变量检查失败：代表基础设施异常，绝不伪装为 Provider 错误。 */
  private static final class BatchInfrastructureException extends RuntimeException {
    private BatchInfrastructureException(String message, Throwable cause) {
      super(message, cause);
    }

    private BatchInfrastructureException(String message) {
      super(message);
    }
  }

  private enum PendingKind {
    /** 暂存待处理的流式增量事件。 */
    EVENT,

    /** 暂存待处理的成功完成响应。 */
    SUCCEEDED,

    /** 暂存待处理的调用失败错误。 */
    FAILED,

    /** 暂存待处理的结果不确定错误。 */
    UNKNOWN
  }

  /** 一次回调信号落地后的本地应用结果。 */
  private enum Applied {
    /** 事件已成功推进 checkpoint 或流式序列号。 */
    PROGRESSED,

    /** 触发瞬态重试，对应 Work 已完成延迟重排。 */
    RETRY,

    /** 终态已持久化落盘，本地执行结束。 */
    TERMINAL,

    /** Claim 所有权已丢失或调用已被废弃，当前信号未被应用。 */
    LOST
  }

  private enum TerminalKind {
    /** 收敛为明确的失败终态。 */
    FAILED,

    /** 收敛为取消终态。 */
    CANCELLED,

    /** 收敛为结果不确定的终态。 */
    UNKNOWN
  }

  /** 一次回调信号；{@code successNanos} 仅对 SUCCEEDED 有意义（成功回调的单调纳秒观察时刻）。 */
  private record Pending(
      PendingKind kind,
      ProviderStreamEvent event,
      ProviderCompletion completion,
      ModelInvocationError error,
      long successNanos) {}

  private record Publish(ProviderStreamEvent event, long sequence) {}

  /** 内部回滚信号：callback 事务失去 Work ownership 时使当前事务完整回滚。 */
  private static final class ClaimLostSignal extends RuntimeException {
    private ClaimLostSignal() {
      super("claimed work lost at final fence", null, false, false);
    }
  }
}
