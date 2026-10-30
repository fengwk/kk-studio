package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.processor.ClaimAdmissionGuard;
import fun.fengwk.kkstudio.harness.runtime.processor.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.runtime.processor.WorkHeartbeat;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 一次 claim 的进程内 Model execution：运行线程上的流式增量接收、推送 / checkpoint 节奏、终态提交、所有权围栏与 lease heartbeat。
 *
 * <p>整个 execution 由 {@link ModelProcessor} 的一个虚拟线程驱动：{@link #markRunning} 持久化 RUNNING，{@link #run}
 * 阻塞等待 Gateway 结束（增量在运行线程上同步进入 {@link #onEvent}），{@link #finish} 提交终态。增量只追加到内存缓冲；单个按需武装的定时 tick
 * （以及追帧请求 {@link #requestCheckpoint}）在同一把锁下刷新推送批次与 checkpoint，因此终态提交与定时刷新天然串行。
 *
 * <p>推送与落库是两条独立节奏：delta 在 {@link ModelStreamConfig#pushWindow()} 内合并为小批次后直接发布（不等待数据库、不依赖提交）；累计
 * text/thinking 按 {@link ModelStreamConfig#checkpointInterval()}（或追帧请求）持久化，事务只锁 ModelInvocation 与
 * claimed Work（不取执行树锁，因为不唤醒 Thread），围栏重校验 RUNNING + attempt + lease，提交后才发布 {@link
 * RealtimeEvent.ModelCheckpoint}。terminal / retry 只写本 Thread 的 Thread/Model/Work：事务按本 Thread 行
 * -&gt; ModelInvocation -&gt; claimed Work 加锁并以 Thread 行锁与 delete/Stop 串行化，唤醒 THREAD Work 只是
 * hint，不需要执行树锁。随后只发布已提交的终态补齐 delta；lost ownership 或 Stop 竞态下 {@link #abandon} 中断运行线程并停止一切发布与写入。
 */
@Slf4j
public final class ModelExecution
    implements ModelGateway.Sink, ClaimAdmissionGuard.GuardedExecution {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ExecutionExitConfirmer exitConfirmer;
  private final RealtimeEventSink realtimeEventSink;
  private final ClaimedWork claim;
  private final UUID invocationId;
  private final UUID executionId = UUID.randomUUID();
  private final UUID threadId;
  private final int attempt;

  /** 登记成功后冻结的本代身份。确认只比对这一身份，不读取之后可能已更换的 lease。 */
  private volatile ExecutionExitFact registered;

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

  private final AtomicBoolean abandoned = new AtomicBoolean();
  private final AtomicBoolean tickQueued = new AtomicBoolean();
  private final AtomicBoolean catchUpInFlight = new AtomicBoolean();

  /** 保护 {@link #runner}：abandon 只在 Gateway 运行窗口内中断运行线程，绝不打断持久化事务。 */
  private final Object runGuard = new Object();

  private Thread runner;

  /** 是否真正进入 Gateway body；在 runner 登记锁内与 cancel 判定串行化。 */
  private volatile boolean bodyStarted;

  /** 保护下列流式状态与所有发布 / checkpoint / 终态提交。 */
  private final Object lock = new Object();

  private final ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
  private final List<Publish> pushBuffer = new ArrayList<>();
  private long pushBufferBytes;
  private ScheduledFuture<?> tick;
  private boolean finished;
  private long lastAcceptedSequence;
  private long lastSafeSequence;
  private long lastCheckpointSequence;
  private long lastCheckpointNanos;

  /** 首个非空输出 delta 的观察时刻（单调纳秒）；null 表示尚未观察到任何模型输出。 */
  private Long firstOutputNanos;

  /** 增量与累积器不一致（INVALID_RESPONSE）：中止运行，终态以它为准。 */
  private ModelInvocationError eventFailure;

  /**
   * 完整构造器：{@code providerName} 是本次 Invocation 冻结的 Provider 身份；{@code nanoTime} 是采集流式生成计时的单调时间源
   * （checkpoint 节奏固定用 System.nanoTime）， 生产默认 {@link System#nanoTime}，测试可注入可控来源。
   */
  public ModelExecution(
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
      ExecutionExitConfirmer exitConfirmer) {
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
        System::nanoTime,
        exitConfirmer);
  }

  public ModelExecution(
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
      LongSupplier nanoTime,
      ExecutionExitConfirmer exitConfirmer) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = requireBuiltinStore(store);
    this.exitConfirmer = Objects.requireNonNull(exitConfirmer, "exitConfirmer");
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

  private static BuiltinStore requireBuiltinStore(HarnessStore store) {
    if (store instanceof BuiltinStore builtin) {
      return builtin;
    }
    throw new IllegalArgumentException("model execution requires a BuiltinStore runtime store");
  }

  private <T> T inTransaction(Function<BuiltinStore.Transaction, T> work) {
    return store.transaction(tx -> work.apply(builtinStore.in(tx)));
  }

  UUID invocationId() {
    return invocationId;
  }

  /** body 与本地资源释放之后确认 Studio 退出。持久化失败保持未确认。 */
  void confirmStudioExit() {
    confirm(ExecutionExitState.STUDIO_EXITED);
  }

  private void confirm(ExecutionExitState state) {
    ExecutionExitFact local = registered;
    if (local == null) {
      log.warn("model execution {} was not registered and remains unconfirmed", executionId);
      return;
    }
    try {
      ExecutionExitConfirmer.Result result = exitConfirmer.confirm(local, state);
      if (result.outcome() == ExecutionExitConfirmer.Outcome.THREAD_MISSING) {
        // 合法深删已先提交，不再回写该 Thread；缺失本身不构成退出证据。
        return;
      }
    } catch (RuntimeException failure) {
      log.warn(
          "cannot confirm model execution exit for {}; phase=MODEL_EXIT_CONFIRM; exception={}",
          local.executionId(),
          failure.getClass().getSimpleName());
    }
  }

  private UUID executionId() {
    return executionId;
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

  /** 是否真正进入过 Gateway body；未进入 body 的 cancel 不是退出，不能确认退出事实。 */
  boolean bodyStarted() {
    return bodyStarted;
  }

  /** 启动 lease heartbeat；scheduler 拒绝时返回 false。 */
  boolean startHeartbeat() {
    return heartbeat.start(claim);
  }

  /** READY -&gt; RUNNING（attempt + 1），先于任何请求发出持久化。lost / stale / Stop 获胜时返回 false，调用方不得发出请求。 */
  public boolean markRunning() {
    boolean committed;
    try {
      committed =
          Boolean.TRUE.equals(
              inTransaction(
                  tx -> {
                    Instant now = tx.now();
                    ThreadState thread = tx.lockThread(threadId).orElse(null);
                    if (thread == null) {
                      return false;
                    }
                    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                    if (model == null || tx.lockClaimedWork(claim).isEmpty()) {
                      return false;
                    }
                    if (model.status() != ModelInvocationStatus.READY
                        || model.attempt() != attempt - 1) {
                      return false;
                    }
                    tx.updateModelInvocation(model.markRunning(now));
                    tx.updateThread(thread.touchVersion(now));
                    registered =
                        ExecutionExitLedger.register(
                            tx,
                            executionId(),
                            threadId,
                            invocationId,
                            attempt,
                            claim.leaseToken(),
                            ExecutionExitKind.MODEL,
                            null);
                    return true;
                  }));
    } catch (RuntimeException failure) {
      log.warn("cannot persist model running state for {}", invocationId, failure);
      committed = false;
    }
    return committed && !abandoned.get();
  }

  /** 本代是否已经持久登记。启动失败只在登记成功后才能确认未启动。 */
  boolean registered() {
    return registered != null;
  }

  /** RUNNING 已登记但执行线程确定没有启动。登记失败时不确认。 */
  public void confirmNotStarted() {
    if (registered != null) {
      confirm(ExecutionExitState.NOT_STARTED);
    }
  }

  /**
   * 在当前（运行）线程上阻塞执行 Gateway；abandon 在此窗口内中断本线程。返回 {@code null} 表示 execution 已被 abandon，调用方不得再提交任何状态。
   */
  public ModelGateway.Outcome run(ModelGateway.Admitted admitted) {
    synchronized (runGuard) {
      if (abandoned.get()) {
        return null;
      }
      runner = Thread.currentThread();
      bodyStarted = true;
    }
    try {
      ModelGateway.Outcome outcome = admitted.run(this);
      return abandoned.get() ? null : outcome;
    } catch (RuntimeException failure) {
      if (abandoned.get()) {
        return null;
      }
      log.warn(
          "model gateway run failed for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return new ModelGateway.Unknown(
          new ModelInvocationError(
              ProviderErrorKind.TRANSIENT,
              "model gateway run failed unexpectedly; provider outcome cannot be confirmed"));
    } finally {
      synchronized (runGuard) {
        runner = null;
      }
      // 清除 abandon 留下的中断标记，之后的持久化事务不受影响。
      Thread.interrupted();
    }
  }

  /** 运行线程上的增量入口：只追加内存缓冲，不触达数据库。 */
  @Override
  public void onEvent(ProviderStreamEvent event) {
    if (abandoned.get()) {
      throw new StreamAborted();
    }
    try {
      synchronized (lock) {
        if (finished) {
          return;
        }
        if (isModelOutputDelta(event) && firstOutputNanos == null) {
          firstOutputNanos = nanoTime.getAsLong();
        }
        long sequence = nextSequence();
        accumulator.append(event);
        if (isSafeDelta(event)) {
          lastSafeSequence = sequence;
        }
        pushBuffer.add(new Publish(event, sequence));
        pushBufferBytes += ModelStreamConfig.eventPayloadBytes(event);
        ModelStreamConfig streamConfig = config.streamConfig();
        if (pushBuffer.size() >= streamConfig.maxBatchEvents()
            || pushBufferBytes >= streamConfig.maxBatchBytes()) {
          flushPushLocked();
        }
        armTickLocked();
      }
    } catch (RuntimeException failure) {
      eventFailure =
          new ModelInvocationError(
              ProviderErrorKind.INVALID_RESPONSE, message(failure, "invalid provider event"));
      throw new StreamAborted();
    }
    if (abandoned.get()) {
      throw new StreamAborted();
    }
  }

  /** 首个增量到达时武装单个定时 tick；之后每个推送窗口检查一次推送与 checkpoint。lock 内调用。 */
  private void armTickLocked() {
    if (tick != null) {
      return;
    }
    lastCheckpointNanos = System.nanoTime();
    long window = config.streamConfig().pushWindow().toMillis();
    try {
      tick =
          scheduler.scheduleWithFixedDelay(this::submitTick, window, window, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException rejected) {
      log.warn(
          "model flush tick scheduling rejected for {}: {}",
          invocationId,
          ProcessorExceptions.describe(rejected));
      abandon();
    }
  }

  /** 调度线程只负责把刷新交给 executor；上一轮未完成时合并跳过。 */
  private void submitTick() {
    if (abandoned.get() || !tickQueued.compareAndSet(false, true)) {
      return;
    }
    try {
      flushExecutor.execute(this::runTick);
    } catch (RejectedExecutionException rejected) {
      tickQueued.set(false);
      log.warn(
          "model flush executor rejected for {}: {}",
          invocationId,
          ProcessorExceptions.describe(rejected));
      abandon();
    }
  }

  private void runTick() {
    try {
      synchronized (lock) {
        if (abandoned.get() || finished) {
          return;
        }
        flushPushLocked();
        long interval = config.streamConfig().checkpointInterval().toNanos();
        if (lastSafeSequence > lastCheckpointSequence
            && System.nanoTime() - lastCheckpointNanos >= interval) {
          checkpointLocked(false);
        }
      }
    } finally {
      tickQueued.set(false);
    }
  }

  /**
   * 追帧请求：仅当本 execution 仍是 active owner 且 attempt 匹配时，合并安排一次立即 checkpoint。
   *
   * <p>best-effort：每个 execution 最多一项 in-flight 追帧任务；重复请求在已有任务未完成时返回 {@code true}（已有请求会覆盖当前累计状态）。
   * executor 拒绝只返回 {@code false}，绝不 abandon execution，也不影响周期 checkpoint 与 terminal。终态、取消/abandon 或
   * attempt 不匹配返回 {@code false}。
   */
  public boolean requestCheckpoint(int requestedAttempt) {
    if (requestedAttempt != attempt || abandoned.get()) {
      return false;
    }
    synchronized (lock) {
      if (finished) {
        return false;
      }
    }
    if (!catchUpInFlight.compareAndSet(false, true)) {
      return true;
    }
    try {
      flushExecutor.execute(this::runCatchUp);
      return true;
    } catch (RejectedExecutionException rejected) {
      catchUpInFlight.set(false);
      log.warn(
          "catch-up checkpoint rejected for {}: {}",
          invocationId,
          ProcessorExceptions.describe(rejected));
      return false;
    }
  }

  private void runCatchUp() {
    try {
      synchronized (lock) {
        if (!abandoned.get() && !finished) {
          checkpointLocked(true);
        }
      }
    } finally {
      catchUpInFlight.set(false);
    }
  }

  /**
   * 持久化 checkpoint（tick 到期或追帧强制）。只有比上次落库前进时才写库；强制请求在无前进时重发已落库的 checkpoint 事件，使等待方立即得到答复。
   * 提交失败或所有权丢失即 abandon，且不发布。lock 内调用。
   */
  private void checkpointLocked(boolean forced) {
    if (lastSafeSequence <= lastCheckpointSequence) {
      if (forced && lastCheckpointSequence > 0) {
        publishCheckpointEvent(lastCheckpointSequence);
      }
      return;
    }
    StreamCheckpoint checkpoint =
        new StreamCheckpoint(attempt, lastSafeSequence, accumulator.text(), accumulator.thinking());
    if (!persistCheckpoint(checkpoint)) {
      abandon();
      return;
    }
    lastCheckpointSequence = checkpoint.sequence();
    lastCheckpointNanos = System.nanoTime();
    if (!abandoned.get()) {
      publishCheckpointEvent(checkpoint.sequence());
    }
  }

  /** 发布并清空推送缓冲；lock 内调用。 */
  private void flushPushLocked() {
    if (pushBuffer.isEmpty()) {
      return;
    }
    List<Publish> toPush = List.copyOf(pushBuffer);
    clearPushBufferLocked();
    publishAll(toPush);
  }

  private void clearPushBufferLocked() {
    pushBuffer.clear();
    pushBufferBytes = 0;
  }

  private void cancelTickLocked() {
    if (tick != null) {
      tick.cancel(false);
    }
  }

  /**
   * 运行结束后在运行线程上提交终态并释放 execution。{@code outcome == null}（已 abandon）或提交时所有权已丢失一律不写任何状态，durable 状态留给
   * lease recovery 收敛。
   */
  public ProcessResult finish(ModelGateway.Outcome outcome) {
    Applied applied = Applied.LOST;
    try {
      if (outcome != null && !abandoned.get()) {
        long successNanos = outcome instanceof ModelGateway.Succeeded ? nanoTime.getAsLong() : 0L;
        // Renderer 属于可插拔 Tool 代码，不在 execution 锁内调用。
        ProviderCompletion completion = null;
        if (outcome instanceof ModelGateway.Succeeded succeeded && eventFailure == null) {
          completion = freezeHistoryActions(succeeded.completion());
        }
        List<Publish> publishes = new ArrayList<>();
        synchronized (lock) {
          finished = true;
          cancelTickLocked();
          if (!abandoned.get()) {
            applied = commitOutcomeLocked(outcome, completion, successNanos, publishes);
          }
        }
        if (applied != Applied.LOST) {
          publishAll(publishes);
        }
      }
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model terminal for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      applied = Applied.LOST;
    } finally {
      synchronized (lock) {
        finished = true;
        cancelTickLocked();
      }
      abandon();
    }
    return switch (applied) {
      case TERMINAL -> ProcessResult.TERMINATED;
      case RETRY -> ProcessResult.RESCHEDULED;
      case LOST -> ProcessResult.LOST_OWNERSHIP;
    };
  }

  private ProviderCompletion freezeHistoryActions(ProviderCompletion completion) {
    ProviderResponse frozen =
        ToolHistoryActions.freeze(
            completion.response(), bindings, config.toolHistoryActionResolver());
    return frozen == completion.response()
        ? completion
        : new ProviderCompletion(frozen, completion.replayState());
  }

  private Applied commitOutcomeLocked(
      ModelGateway.Outcome outcome,
      ProviderCompletion completion,
      long successNanos,
      List<Publish> publishes) {
    if (eventFailure != null) {
      return finishFailureLocked(eventFailure, publishes);
    }
    return switch (outcome) {
      case ModelGateway.Succeeded ignored -> finishSuccessLocked(
          completion, successNanos, publishes);
      case ModelGateway.Failed failed -> finishFailureLocked(failed.error(), publishes);
      case ModelGateway.Unknown unknown -> finishUnknownLocked(unknown.error(), publishes);
    };
  }

  /** 停止 heartbeat 与 tick、中断运行线程（仅 Gateway 运行窗口内）并从 registry 释放；幂等。 */
  @Override
  public void abandon() {
    if (!abandoned.compareAndSet(false, true)) {
      return;
    }
    heartbeat.stop();
    ScheduledFuture<?> timer = tick;
    if (timer != null) {
      timer.cancel(false);
    }
    synchronized (runGuard) {
      if (runner != null && runner != Thread.currentThread()) {
        runner.interrupt();
      }
    }
    ownerRelease.accept(this);
  }

  private boolean persistCheckpoint(StreamCheckpoint checkpoint) {
    try {
      return Boolean.TRUE.equals(
          inTransaction(
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
    Applied groupApplied = commitGroupFailure(error);
    if (groupApplied != null) {
      if (groupApplied != Applied.LOST) {
        clearPushBufferLocked();
      }
      return groupApplied;
    }
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
    boolean committed = safeTerminal(() -> commitTerminal(TerminalKind.UNKNOWN, error));
    if (committed) {
      clearPushBufferLocked();
      return Applied.TERMINAL;
    }
    return Applied.LOST;
  }

  /** RUNNING -&gt; READY：记录瞬态失败并排程重试：{@code retryAt = failedAt + delay}，Work 按同一 delay 重排。 */
  private boolean commitRetry(Duration delay, ModelInvocationError error) {
    Objects.requireNonNull(error, "error");
    return Boolean.TRUE.equals(
        inTransaction(
            tx -> {
              Instant now = tx.now();
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
              tx.updateModelInvocation(
                  model.retryReady(attemptFailure(model, error, now, now.plus(delay)), now));
              tx.updateThread(thread.touchVersion(now));
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
          inTransaction(
              tx -> {
                Instant now = tx.now();
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
          inTransaction(
              tx -> {
                Instant now = tx.now();
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

  /**
   * 模型组失败的唯一推进事务：成员内普通重试、立即降级、普通重试耗尽后的冻结与成员推进、全部成员耗尽的最终失败都在同一事务中确认。
   *
   * <p>返回 {@code null} 表示当前 invocation 不是模型组，或失败属于取消、上下文溢出等专用终止，调用方继续单模型路径；返回 LOST
   * 表示所有权/状态围栏失败或事务无法提交。
   */
  private Applied commitGroupFailure(ModelInvocationError error) {
    try {
      return inTransaction(
          tx -> {
            Instant now = tx.now();
            ThreadState thread = tx.lockThread(threadId).orElse(null);
            if (thread == null) {
              return Applied.LOST;
            }
            ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
            if (model == null) {
              return Applied.LOST;
            }
            if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
              return Applied.LOST;
            }
            ModelGroupExecution group = model.requestSpec().groupExecution();
            if (group == null) {
              return null;
            }
            ModelGroupProgress progress = model.groupProgress();
            if (progress == null
                || progress.memberIndex() >= group.members().size()
                || progress.groupVersion() != group.groupVersion()) {
              throw new IllegalStateException("invalid durable group progress");
            }
            boolean retryable = isRetryable(error);
            InvocationRetryDecision retryDecision =
                InvocationRetryDecision.decide(
                    config.retryPolicyProvider().retryPolicy(),
                    retryable,
                    Math.addExact(progress.memberAttempts(), 1));
            ModelGroupFailureDecider.Action action =
                ModelGroupFailureDecider.decide(error, group, retryable, retryDecision.retry());
            if (action == ModelGroupFailureDecider.Action.DEDICATED_TERMINAL) {
              return null;
            }
            if (action == ModelGroupFailureDecider.Action.RETRY_CURRENT) {
              if (tx.lockClaimedWork(claim).isEmpty()) {
                return Applied.LOST;
              }
              Duration delay = retryDecision.delay();
              tx.updateModelInvocation(
                  model
                      .retryReady(attemptFailure(model, error, now, now.plus(delay)), now)
                      .withGroupProgress(progress.retryCurrent(), now));
              tx.updateThread(thread.touchVersion(now));
              tx.rescheduleWork(claim, delay);
              return Applied.RETRY;
            }
            boolean exhausted = progress.memberIndex() + 1 >= group.members().size();
            if (exhausted) {
              // 终态先请求 THREAD Work，再锁 MODEL Work，维持 THREAD -> MODEL 的全局 Work 锁序。
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
            }
            if (tx.lockClaimedWork(claim).isEmpty()) {
              // 已写 THREAD wake 后丢失 MODEL claim：抛错回滚，避免提交无主唤醒。
              throw new ClaimLostSignal();
            }
            if (group.cooldownSeconds() > 0) {
              ModelGroupMemberExecution member = group.members().get(progress.memberIndex());
              tx.freezeGroupMember(
                  group.groupName(),
                  group.groupVersion(),
                  member.model().modelName(),
                  member.variant().id(),
                  now.plusSeconds(group.cooldownSeconds()));
            }
            ModelAttemptFailure failure = attemptFailure(model, error, now, now);
            if (exhausted) {
              tx.updateModelInvocation(model.failAfterAttempt(error, failure, now));
              tx.updateThread(thread.touchVersion(now));
              tx.completeWork(claim);
              return Applied.TERMINAL;
            }
            tx.updateModelInvocation(model.advanceGroupReady(failure, progress.nextMember(), now));
            tx.updateThread(thread.touchVersion(now));
            // 降级到下一成员不等待：立即重新调度同一逻辑 invocation，游标已在同事务推进。
            tx.rescheduleWork(claim, Duration.ZERO);
            return Applied.RETRY;
          });
    } catch (ClaimLostSignal ignored) {
      return Applied.LOST;
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model group failure for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return Applied.LOST;
    }
  }

  /** 本次失败 attempt 的审计快照：当前成员身份、请求时刻与已累积的安全 partial。 */
  private ModelAttemptFailure attemptFailure(
      ModelInvocation model, ModelInvocationError error, Instant failedAt, Instant retryAt) {
    return new ModelAttemptFailure(
        attempt,
        lastSafeSequence,
        accumulator.text(),
        accumulator.thinking(),
        model.actualSelection(),
        model.requestedAt(),
        error,
        failedAt,
        retryAt);
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
    Long startNanos = firstOutputNanos;
    if (startNanos == null) {
      return null;
    }
    return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, successNanos - startNanos));
  }

  /** 运行线程内部中止信号：execution 已 abandon 或增量不一致，调用栈直接展开回 Gateway。 */
  private static final class StreamAborted extends RuntimeException {
    private StreamAborted() {
      super("model stream aborted", null, false, false);
    }
  }

  /** 一次终态提交的本地应用结果。 */
  private enum Applied {
    /** 触发瞬态重试，对应 Work 已完成延迟重排。 */
    RETRY,

    /** 终态已持久化落盘，本地执行结束。 */
    TERMINAL,

    /** Claim 所有权已丢失或调用已被废弃，未写入任何状态。 */
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

  private record Publish(ProviderStreamEvent event, long sequence) {}
}
