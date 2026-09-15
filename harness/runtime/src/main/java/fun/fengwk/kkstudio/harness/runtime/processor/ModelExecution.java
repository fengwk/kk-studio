package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
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
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次 claim 的进程内 Model execution：Gateway Listener 回调门控缓冲、所有权围栏与 lease heartbeat。
 *
 * <p>Listener 回调在持久化 RUNNING 落地前由回调门控缓冲；两阶段激活（{@link #activate}）：先安全 attach handle，再持久化
 * markRunning，最后调用外部 {@code handle.activate()}（通知 Gateway 打开回调门控），成功后按到达顺序重放缓冲信号。 激活后通过
 * per-execution 单 drain owner 顺序处理流式增量事件、内部批次 FLUSH 与终态信号； delta
 * 只做本地缓冲，按有界时间窗口与容量阈值聚合为批次，围栏只在提交边界（flush / terminal / retry）内以同一次短事务重校验 RUNNING + attempt +
 * claimed lease，随后单次落地最新 checkpoint， commit 后在事务与 monitor 外按原 sequence 以有界分块批量发布； 纯工具批次仅通过 fence
 * 并推进 sequence 水位，不更新 checkpoint； terminal / retry 信号直接吸收未刷批次，在同一次状态事务中单次更新 ModelInvocation； lost
 * ownership 或 Stop 竞态立即收敛关闭，绝不补写或发布未提交批次。
 */
@Slf4j
final class ModelExecution implements ModelGateway.Listener {

  private final HarnessStore store;
  private final RealtimeEventSink realtimeEventSink;
  private final ClaimedWork claim;
  private final UUID invocationId;
  private final UUID threadId;
  private final int attempt;
  private final boolean compaction;
  private final ModelProcessorConfig config;
  private final Clock clock;
  private final WorkHeartbeat heartbeat;
  private final ScheduledExecutorService scheduler;
  private final Executor flushExecutor;
  private final Consumer<ModelExecution> ownerRelease;

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

  private final List<BatchItem> batchItems = new ArrayList<>();
  private long batchPayloadBytes;
  private ScheduledFuture<?> batchTimer;
  private long batchGeneration;

  private long lastAcceptedSequence;
  private long lastCommittedSequence;
  private long lastSafeSequence;

  ModelExecution(
      HarnessStore store,
      RealtimeEventSink realtimeEventSink,
      ClaimedWork claim,
      UUID threadId,
      int attempt,
      boolean compaction,
      ModelProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      Executor flushExecutor,
      Consumer<ModelExecution> ownerRelease) {
    this.store = Objects.requireNonNull(store, "store");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.claim = Objects.requireNonNull(claim, "claim");
    this.invocationId = claim.target().id();
    this.threadId = threadId;
    this.attempt = attempt;
    this.compaction = compaction;
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.flushExecutor = Objects.requireNonNull(flushExecutor, "flushExecutor");
    this.heartbeat =
        new WorkHeartbeat(
            this.store,
            this.scheduler,
            Objects.requireNonNull(heartbeatWorker, "heartbeatWorker"),
            config.leaseConfig(),
            this.clock,
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
        cancelBatchTimerLocked();
        pending.clear();
        pendingPayloadBytes = 0;
        batchItems.clear();
        batchPayloadBytes = 0;
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
    deliver(new Pending(PendingKind.EVENT, event, null, null));
  }

  @Override
  public void onSucceeded(ProviderCompletion completion) {
    if (abandoned.get() || !terminal.compareAndSet(false, true)) {
      return;
    }
    deliver(new Pending(PendingKind.SUCCEEDED, null, completion, null));
  }

  @Override
  public void onFailed(ModelInvocationError error) {
    if (abandoned.get() || !terminal.compareAndSet(false, true)) {
      return;
    }
    deliver(new Pending(PendingKind.FAILED, null, null, error));
  }

  @Override
  public void onUnknown(ModelInvocationError error) {
    if (abandoned.get() || !terminal.compareAndSet(false, true)) {
      return;
    }
    deliver(new Pending(PendingKind.UNKNOWN, null, null, error));
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
      cancelBatchTimerLocked();
      pending.clear();
      pendingPayloadBytes = 0;
      batchItems.clear();
      batchPayloadBytes = 0;
      cancelNow = !activationInProgress;
    }
    if (cancelNow) {
      cancelHandle();
    }
    ownerRelease.accept(this);
  }

  private void checkPendingCapacityLocked(ProviderStreamEvent event) {
    if (pending.size() >= config.streamFlushConfig().maxEvents()) {
      throw new BatchInfrastructureException(
          "activation pending capacity exceeded: " + pending.size());
    }
    long eventBytes = StreamFlushConfig.eventPayloadBytes(event);
    long newBytes;
    try {
      newBytes = Math.addExact(pendingPayloadBytes, eventBytes);
    } catch (ArithmeticException overflow) {
      throw new BatchInfrastructureException("activation pending payload bytes overflow", overflow);
    }
    if (!pending.isEmpty() && newBytes > config.streamFlushConfig().maxPayloadBytes()) {
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
      case SUCCEEDED -> processSucceeded(signal.completion());
      case FAILED -> processFailed(signal.error());
      case UNKNOWN -> processUnknown(signal.error());
    };
  }

  /**
   * 处理一个 stream delta：per-delta 只做本地缓冲，不触达数据库； 容量超限时同步 flush（提交前在同一事务内重校验 RUNNING + attempt +
   * claimed lease）形成背压；text/thinking delta 更新 lastSafeSequence； 未满容量时调度单次无防抖批次定时器。整个处理与发布在单 drain
   * owner 内顺序执行。
   *
   * <p>所有权损失的检测因此不在每个 token 上发生，而收敛到批次定时器 / 容量 flush / terminal 的提交围栏，或 heartbeat 丢失 lease 后触发的
   * {@link #abandon}：尚未提交的缓冲事件在 flush 失败时整体丢弃，绝不发布。
   */
  private Applied processEvent(ProviderStreamEvent event) {
    long eventBytes = StreamFlushConfig.eventPayloadBytes(event);
    StreamFlushConfig flushConfig = config.streamFlushConfig();

    List<Publish> publishesBefore = new ArrayList<>();
    Applied flushBeforeResult = Applied.PROGRESSED;
    synchronized (monitor) {
      if (abandoned.get()) {
        return Applied.LOST;
      }
      if (!batchItems.isEmpty()
          && (batchItems.size() + 1 > flushConfig.maxEvents()
              || batchPayloadBytes + eventBytes > flushConfig.maxPayloadBytes())) {
        flushBeforeResult = flushBatchLocked(publishesBefore);
      }
    }
    if (flushBeforeResult == Applied.LOST) {
      return Applied.LOST;
    }
    publishAll(publishesBefore);

    List<Publish> publishesCurrent = new ArrayList<>();
    Applied flushCurrentResult = Applied.PROGRESSED;
    boolean needScheduleTimer = false;
    long timerGeneration = 0;
    synchronized (monitor) {
      if (abandoned.get()) {
        return Applied.LOST;
      }
      long sequence = nextSequence();
      accumulator.append(event);
      boolean isSafe = isSafeDelta(event);
      if (isSafe) {
        lastSafeSequence = sequence;
      }
      batchItems.add(new BatchItem(event, sequence, isSafe));
      batchPayloadBytes += eventBytes;

      if (batchItems.size() >= flushConfig.maxEvents()
          || batchPayloadBytes >= flushConfig.maxPayloadBytes()) {
        flushCurrentResult = flushBatchLocked(publishesCurrent);
      } else if (batchTimer == null) {
        needScheduleTimer = true;
        timerGeneration = batchGeneration;
      }
    }
    if (flushCurrentResult == Applied.LOST) {
      return Applied.LOST;
    }
    publishAll(publishesCurrent);
    if (needScheduleTimer) {
      scheduleBatchTimer(timerGeneration);
    }
    if (abandoned.get()) {
      return Applied.LOST;
    }
    return Applied.PROGRESSED;
  }

  private Applied processSucceeded(ProviderCompletion completion) {
    List<Publish> publishes = new ArrayList<>();
    Applied applied;
    synchronized (monitor) {
      if (abandoned.get()) {
        return Applied.LOST;
      }
      applied = finishSuccessLocked(completion, publishes);
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

  private void scheduleBatchTimer(long targetGeneration) {
    ScheduledFuture<?> future;
    try {
      future =
          scheduler.schedule(
              () -> onTimerFired(targetGeneration),
              config.streamFlushConfig().maxDelay().toMillis(),
              TimeUnit.MILLISECONDS);
    } catch (RuntimeException rejected) {
      boolean shouldAbandon;
      synchronized (monitor) {
        shouldAbandon = claimTimerRejectionLocked(targetGeneration);
      }
      if (shouldAbandon) {
        log.warn(
            "model flush timer scheduling rejected for {}: {}",
            invocationId,
            ProcessorExceptions.describe(rejected));
        abandon();
      }
      return;
    }
    synchronized (monitor) {
      if (abandoned.get() || terminal.get() || targetGeneration != batchGeneration) {
        future.cancel(false);
        return;
      }
      batchTimer = future;
    }
  }

  private void onTimerFired(long targetGeneration) {
    synchronized (monitor) {
      if (abandoned.get()
          || terminal.get()
          || targetGeneration != batchGeneration
          || batchItems.isEmpty()) {
        return;
      }
    }
    try {
      flushExecutor.execute(() -> deliverFlush(targetGeneration));
    } catch (RuntimeException rejected) {
      boolean shouldAbandon;
      synchronized (monitor) {
        shouldAbandon = claimTimerRejectionLocked(targetGeneration);
      }
      if (shouldAbandon) {
        log.warn(
            "model flush executor rejected task for {}: {}",
            invocationId,
            ProcessorExceptions.describe(rejected));
        abandon();
      }
    }
  }

  private boolean claimTimerRejectionLocked(long targetGeneration) {
    if (abandoned.get()
        || targetGeneration != batchGeneration
        || batchItems.isEmpty()
        || !terminal.compareAndSet(false, true)) {
      return false;
    }
    return true;
  }

  private void cancelBatchTimerLocked() {
    if (batchTimer != null) {
      batchTimer.cancel(false);
      batchTimer = null;
    }
    batchGeneration++;
  }

  void deliverFlush(long generation) {
    if (abandoned.get()) {
      return;
    }
    drainLock.lock();
    try {
      if (abandoned.get()) {
        return;
      }
      List<Publish> publishes = new ArrayList<>();
      Applied applied = Applied.LOST;
      RuntimeException failure = null;
      synchronized (monitor) {
        if (abandoned.get()
            || terminal.get()
            || generation != batchGeneration
            || batchItems.isEmpty()) {
          return;
        }
        try {
          applied = flushBatchLocked(publishes);
        } catch (RuntimeException error) {
          failure = error;
        }
      }
      if (failure != null) {
        log.warn(
            "cannot persist model batch flush for {}: {}",
            invocationId,
            ProcessorExceptions.describe(failure));
        abandon();
        return;
      }
      if (applied == Applied.LOST) {
        abandon();
        return;
      }
      publishAll(publishes);
    } finally {
      drainLock.unlock();
    }
  }

  private Applied flushBatchLocked(List<Publish> publishes) {
    cancelBatchTimerLocked();
    if (batchItems.isEmpty()) {
      return Applied.PROGRESSED;
    }
    long firstSeq = batchItems.getFirst().sequence();
    if (firstSeq != lastCommittedSequence + 1) {
      throw new BatchInfrastructureException(
          "non-contiguous sequence publication: expected "
              + (lastCommittedSequence + 1)
              + " but got "
              + firstSeq);
    }
    long batchSafeSeq = 0;
    for (BatchItem item : batchItems) {
      if (item.safe()) {
        batchSafeSeq = Math.max(batchSafeSeq, item.sequence());
      }
    }
    StreamCheckpoint checkpointToPersist = null;
    if (batchSafeSeq > 0) {
      String text = accumulator.text();
      String thinking = accumulator.thinking();
      if (!text.isEmpty() || !thinking.isEmpty()) {
        checkpointToPersist = new StreamCheckpoint(attempt, batchSafeSeq, text, thinking);
      }
    }
    final StreamCheckpoint pendingCheckpoint = checkpointToPersist;
    Instant now = clock.instant();
    boolean committed;
    try {
      committed =
          Boolean.TRUE.equals(
              store.transaction(
                  tx -> {
                    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                    if (model == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                      return false;
                    }
                    if (model.status() != ModelInvocationStatus.RUNNING
                        || model.attempt() != attempt) {
                      return false;
                    }
                    if (pendingCheckpoint != null) {
                      tx.updateModelInvocation(model.checkpoint(pendingCheckpoint, now));
                    }
                    return true;
                  }));
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model batch flush for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      throw new BatchInfrastructureException("cannot persist model batch flush", failure);
    }
    if (!committed) {
      return Applied.LOST;
    }
    lastCommittedSequence = batchItems.getLast().sequence();
    for (BatchItem item : batchItems) {
      publishes.add(new Publish(item.event(), item.sequence()));
    }
    batchItems.clear();
    batchPayloadBytes = 0;
    return Applied.PROGRESSED;
  }

  private Applied finishSuccessLocked(ProviderCompletion completion, List<Publish> publishes) {
    cancelBatchTimerLocked();
    ProviderResponse response = completion.response();
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
      boolean committed = safeTerminal(() -> commitSuccess(validatedResponse, null, null));
      if (!committed) {
        return Applied.LOST;
      }
      batchItems.clear();
      batchPayloadBytes = 0;
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
    List<BatchItem> gapItems = new ArrayList<>();
    for (ProviderStreamEvent gap : accCompletion.gaps()) {
      long seq = nextSequence();
      boolean isSafe = isSafeDelta(gap);
      if (isSafe) {
        lastSafeSequence = seq;
      }
      gapItems.add(new BatchItem(gap, seq, isSafe));
    }

    String text = compaction ? validatedResponse.text() : accumulator.text();
    String thinking = compaction ? validatedResponse.thinking() : accumulator.thinking();
    StreamCheckpoint finalCheckpoint = null;
    if (!text.isEmpty() || !thinking.isEmpty()) {
      finalCheckpoint = new StreamCheckpoint(attempt, lastSafeSequence, text, thinking);
    }
    final StreamCheckpoint checkpointToCommit = finalCheckpoint;

    ProviderReplayState replayState = null;
    if (!compaction
        && (validatedResponse.stopReason() == GenerationStopReason.COMPLETE
            || validatedResponse.stopReason() == GenerationStopReason.LENGTH)
        && validatedResponse.toolCallDiagnostics().isEmpty()) {
      replayState = completion.replayState();
    }
    final ProviderReplayState replayStateToCommit = replayState;

    boolean committed =
        safeTerminal(
            () -> commitSuccess(validatedResponse, checkpointToCommit, replayStateToCommit));
    if (!committed) {
      return Applied.LOST;
    }

    if (!batchItems.isEmpty()) {
      long firstSeq = batchItems.getFirst().sequence();
      if (firstSeq != lastCommittedSequence + 1) {
        throw new IllegalStateException(
            "non-contiguous sequence publication: expected "
                + (lastCommittedSequence + 1)
                + " but got "
                + firstSeq);
      }
    }
    for (BatchItem item : batchItems) {
      publishes.add(new Publish(item.event(), item.sequence()));
    }
    for (BatchItem item : gapItems) {
      publishes.add(new Publish(item.event(), item.sequence()));
    }
    lastCommittedSequence = lastAcceptedSequence;
    batchItems.clear();
    batchPayloadBytes = 0;
    return Applied.TERMINAL;
  }

  private Applied finishFailureLocked(ModelInvocationError error, List<Publish> publishes) {
    cancelBatchTimerLocked();
    if (isRetryable(error) && config.retryPolicyProvider().retryPolicy().allowsRetry(attempt)) {
      Duration delay = config.retryPolicyProvider().retryPolicy().delayBeforeRetry(attempt);
      boolean committed = safeTerminal(() -> commitRetry(delay, error));
      if (committed) {
        log.info(
            "scheduled model retry for invocation {} (attempt {}) in {}",
            invocationId,
            attempt + 1,
            delay);
        batchItems.clear();
        batchPayloadBytes = 0;
        return Applied.RETRY;
      }
      return Applied.LOST;
    }
    TerminalKind kind =
        error.kind() == ProviderErrorKind.CANCELLED ? TerminalKind.CANCELLED : TerminalKind.FAILED;
    boolean committed = safeTerminal(() -> commitTerminal(kind, error));
    if (committed) {
      batchItems.clear();
      batchPayloadBytes = 0;
      return Applied.TERMINAL;
    }
    return Applied.LOST;
  }

  /** TRANSIENT 与 INVALID_RESPONSE 共享 {@link InvocationRetryPolicy}：两者耗尽后都转为 FAILED terminal。 */
  private static boolean isRetryable(ModelInvocationError error) {
    return error.kind() == ProviderErrorKind.TRANSIENT
        || error.kind() == ProviderErrorKind.INVALID_RESPONSE;
  }

  private Applied finishUnknownLocked(ModelInvocationError error, List<Publish> publishes) {
    cancelBatchTimerLocked();
    boolean committed = safeTerminal(() -> commitTerminal(TerminalKind.UNKNOWN, error));
    if (committed) {
      batchItems.clear();
      batchPayloadBytes = 0;
      return Applied.TERMINAL;
    }
    return Applied.LOST;
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
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
              if (model == null || tx.lockClaimedWork(claim, now).isEmpty()) {
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

  /**
   * RUNNING -&gt; READY：记录瞬态失败并排程重试。lease 所有权（{@code lockClaimedWork} / {@code
   * rescheduleWork}）继续使用原始 wall-clock 采样 {@code leaseNow}；持久化 failedAt 在锁内取 thread/model/上一 retryAt
   * 的下界，回拨时钟也不会违反 failedAttempts 不变量或后续 Entry 链时间顺序。
   */
  private boolean commitRetry(Duration delay, ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    Instant leaseNow = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
              if (model == null || tx.lockClaimedWork(claim, leaseNow).isEmpty()) {
                return false;
              }
              if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                return false;
              }
              Instant failedAt = durableFailedAt(leaseNow, thread, model);
              Instant retryAt = failedAt.plus(delay);
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
              tx.rescheduleWork(claim, leaseNow, retryAt);
              return true;
            }));
  }

  /** 失败审计时间下界：max(leaseNow, thread.updatedAt, model.updatedAt, 上一 retryAt)。 */
  private static Instant durableFailedAt(
      Instant leaseNow, ThreadState thread, ModelInvocation model) {
    Instant effective = leaseNow;
    if (effective.isBefore(thread.updatedAt())) {
      effective = thread.updatedAt();
    }
    if (effective.isBefore(model.updatedAt())) {
      effective = model.updatedAt();
    }
    if (!model.failedAttempts().isEmpty()) {
      Instant previousRetryAt = model.failedAttempts().getLast().retryAt();
      if (effective.isBefore(previousRetryAt)) {
        effective = previousRetryAt;
      }
    }
    return effective;
  }

  private boolean commitSuccess(
      ProviderResponse response,
      StreamCheckpoint finalCheckpoint,
      ProviderReplayState replayState) {
    Instant now = clock.instant();
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
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
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), now);
                if (tx.lockClaimedWork(claim, now).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.updateModelInvocation(
                    model.succeed(response, finalCheckpoint, replayState, now));
                tx.updateThread(thread.touchVersion(now));
                tx.completeWork(claim, now);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  private boolean commitTerminal(TerminalKind kind, ModelInvocationError error) {
    Instant now = clock.instant();
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
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
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), now);
                if (tx.lockClaimedWork(claim, now).isEmpty()) {
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
                tx.completeWork(claim, now);
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
   * 按 sequence 顺序发布一批已提交 delta。
   *
   * <p>发布以 {@link StreamFlushConfig} 的批次上界分块（事件数 + 增量载荷字节），每个分块恰好调用一次 {@link
   * RealtimeEventSink#appendAll}：终态补齐的大量 gap delta 或单个超大事件都不会构造无界列表或单次无界调用。分块失败按调用隔离，只降低实时体验，
   * 绝不改变已提交的 durable 状态或终态。
   */
  private void publishAll(List<Publish> publishes) {
    if (compaction) {
      return;
    }
    StreamFlushConfig publishConfig = config.streamFlushConfig();
    List<RealtimeEvent> chunk = new ArrayList<>();
    long chunkPayloadBytes = 0;
    for (Publish publish : publishes) {
      if (publish.sequence() > lastCommittedSequence) {
        throw new IllegalStateException(
            "cannot publish uncommitted sequence "
                + publish.sequence()
                + "; committed watermark is "
                + lastCommittedSequence);
      }
      long payloadBytes = StreamFlushConfig.eventPayloadBytes(publish.event());
      if (!chunk.isEmpty()
          && (chunk.size() >= publishConfig.maxEvents()
              || chunkPayloadBytes + payloadBytes > publishConfig.maxPayloadBytes())) {
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
    if (events.isEmpty()) {
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

  private record Pending(
      PendingKind kind,
      ProviderStreamEvent event,
      ProviderCompletion completion,
      ModelInvocationError error) {}

  private record Publish(ProviderStreamEvent event, long sequence) {}

  private record BatchItem(ProviderStreamEvent event, long sequence, boolean safe) {}

  /** 内部回滚信号：callback 事务失去 Work ownership 时使当前事务完整回滚。 */
  private static final class ClaimLostSignal extends RuntimeException {
    private ClaimLostSignal() {
      super("claimed work lost at final fence", null, false, false);
    }
  }
}
