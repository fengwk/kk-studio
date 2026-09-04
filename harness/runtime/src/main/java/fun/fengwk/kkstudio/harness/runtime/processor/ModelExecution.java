package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次 claim 的进程内 Model execution：Gateway Listener 回调门控缓冲、所有权围栏与 lease heartbeat。
 *
 * <p>Listener 回调在持久化 RUNNING 落地前由回调门控缓冲；两阶段激活（{@link #activate}）：先安全 attach handle，再持久化
 * markRunning，最后调用外部 {@code handle.activate()}（通知 Gateway 打开回调门控），成功后按到达顺序重放缓冲信号。激活仲裁（abandon -&gt;
 * activate 竞态）：activation 开始前 abandon 获胜则 activate 绝不调用；开始后 abandon 把 cancel 推迟到 activate 返回，外部调用序
 * 恒为 ACTIVATE -&gt; CANCEL（外部 activate / cancel 绝不持有 monitor 避免死锁）。所有回调都先在校验 RUNNING + attempt 与
 * claim ownership 的短事务中落地（text/thinking 单调 checkpoint，tool-call fragment 只推 sequence 不入
 * checkpoint），commit 后才 best-effort 发布 {@link RealtimeEvent.ModelDelta}。terminal 回调一次生效， duplicate
 * / late / stale 一律 no-op；lost ownership 立即关闭回调门控、cancel handle 并停止 heartbeat，且不反写任何持久化状态。{@code
 * handle.activate()} 抛异常即激活失败：恰好一次 UNKNOWN terminal，激活前缓冲的信号全部丢弃。
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
  private final Consumer<ModelExecution> ownerRelease;

  private final AtomicBoolean terminal = new AtomicBoolean();
  private final AtomicBoolean abandoned = new AtomicBoolean();
  private final Object monitor = new Object();
  private final ModelStreamAccumulator accumulator = new ModelStreamAccumulator();
  private final ArrayDeque<Pending> pending = new ArrayDeque<>();
  private boolean gateOpen;
  private long lastCommittedSequence;
  private ModelGateway.Handle handle;

  /** monitor 保护；activation 期间 abandon 推迟 handle cancel。 */
  private boolean activationInProgress;

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
    this.heartbeat =
        new WorkHeartbeat(
            Objects.requireNonNull(store, "store"),
            Objects.requireNonNull(scheduler, "scheduler"),
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
    List<Publish> publishes = new ArrayList<>();
    Applied applied;
    synchronized (monitor) {
      gateOpen = true;
      applied = abandoned.get() ? Applied.LOST : drain(publishes);
    }
    publishAll(publishes);
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
    Applied applied;
    synchronized (monitor) {
      if (abandoned.get()) {
        return ProcessResult.LOST_OWNERSHIP;
      }
      // 缓冲的 terminal（若存在）从未落地：丢弃并强制 UNKNOWN；terminal 标志无条件收敛，DB 层保证至多一次 terminal。
      pending.clear();
      terminal.set(true);
      applied = finishUnknownLocked(error, publishes);
    }
    publishAll(publishes);
    if (applied == Applied.LOST) {
      abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    abandon();
    return ProcessResult.TERMINATED;
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
    deliver(new Pending(PendingKind.EVENT, event, null, null));
  }

  @Override
  public void onSucceeded(ProviderResponse response) {
    deliver(new Pending(PendingKind.SUCCEEDED, null, response, null));
  }

  @Override
  public void onFailed(ModelInvocationError error) {
    deliver(new Pending(PendingKind.FAILED, null, null, error));
  }

  @Override
  public void onUnknown(ModelInvocationError error) {
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
      pending.clear();
      cancelNow = !activationInProgress;
    }
    if (cancelNow) {
      cancelHandle();
    }
    ownerRelease.accept(this);
  }

  private void deliver(Pending signal) {
    if (terminal.get() || abandoned.get()) {
      return;
    }
    boolean terminalSignal = signal.kind != PendingKind.EVENT;
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
      if (terminalSignal) {
        log.warn(
            "cannot persist model terminal for {}: {}",
            invocationId,
            ProcessorExceptions.describe(failure));
        abandon();
      } else {
        deliverFailure(
            new ModelInvocationError(
                ProviderErrorKind.INVALID_RESPONSE, message(failure, "invalid provider callback")));
      }
      return;
    }
    if (applied == Applied.LOST) {
      abandon();
      return;
    }
    publishAll(publishes);
    if (terminalSignal) {
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
      case EVENT -> processEventLocked(signal.event, publishes);
      case SUCCEEDED -> finishSuccessLocked(signal.response, publishes);
      case FAILED -> finishFailureLocked(signal.error, publishes);
      case UNKNOWN -> finishUnknownLocked(signal.error, publishes);
    };
  }

  /**
   * 处理一个 stream delta：sequence 单调分配；每个 safe text/thinking 事件都先在校验 RUNNING + attempt 与 ownership
   * 的短事务中落地完整 checkpoint，tool-call fragment 永不 checkpoint；commit 后 best-effort 发布 realtime delta。
   */
  private Applied processEventLocked(ProviderStreamEvent event, List<Publish> publishes) {
    long sequence = nextSequence();
    accumulator.append(event);
    boolean safeContent =
        event instanceof ProviderStreamEvent.TextDelta
            || event instanceof ProviderStreamEvent.ThinkingDelta;
    Instant now = clock.instant();
    StreamCheckpoint pending = null;
    if (safeContent) {
      String text = accumulator.text();
      String thinking = accumulator.thinking();
      if (!text.isEmpty() || !thinking.isEmpty()) {
        pending = new StreamCheckpoint(attempt, sequence, text, thinking);
      }
    }
    final StreamCheckpoint pendingCheckpoint = pending;
    boolean committed =
        Boolean.TRUE.equals(store.transaction(tx -> persistEvent(tx, pendingCheckpoint, now)));
    if (!committed) {
      return Applied.LOST;
    }
    lastCommittedSequence = sequence;
    publishes.add(new Publish(event, sequence));
    return Applied.PROGRESSED;
  }

  private boolean persistEvent(
      HarnessStore.Transaction tx, StreamCheckpoint pendingCheckpoint, Instant now) {
    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
    if (model == null || tx.lockClaimedWork(claim, now).isEmpty()) {
      return false;
    }
    if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
      return false;
    }
    if (pendingCheckpoint != null) {
      tx.updateModelInvocation(model.checkpoint(pendingCheckpoint, now));
    }
    return true;
  }

  private Applied finishSuccessLocked(ProviderResponse response, List<Publish> publishes) {
    ModelStreamAccumulator.Completion completion;
    ProviderResponse validatedResponse;
    try {
      completion = accumulator.complete(response);
      validatedResponse = ModelResponseValidator.validate(completion.response());
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
    long finalSequence = lastCommittedSequence;
    for (ProviderStreamEvent ignored : completion.gaps()) {
      finalSequence = nextSequence(finalSequence);
    }
    final long finalCheckpointSequence = finalSequence;
    boolean committed =
        safeTerminal(() -> commitSuccess(validatedResponse, finalCheckpointSequence));
    if (!committed) {
      return Applied.LOST;
    }
    long sequence = lastCommittedSequence;
    for (ProviderStreamEvent gap : completion.gaps()) {
      sequence = nextSequence(sequence);
      lastCommittedSequence = sequence;
      publishes.add(new Publish(gap, sequence));
    }
    return Applied.TERMINAL;
  }

  private Applied finishFailureLocked(ModelInvocationError error, List<Publish> publishes) {
    if (isRetryable(error) && config.retryPolicyProvider().retryPolicy().allowsRetry(attempt)) {
      Duration delay = config.retryPolicyProvider().retryPolicy().delayBeforeRetry(attempt);
      boolean committed = safeTerminal(() -> commitRetry(delay, error));
      if (committed) {
        log.info(
            "scheduled model retry for invocation {} (attempt {}) in {}",
            invocationId,
            attempt + 1,
            delay);
        return Applied.RETRY;
      }
      return Applied.LOST;
    }
    if (error.kind() == ProviderErrorKind.CANCELLED) {
      return safeTerminal(() -> commitTerminal(TerminalKind.CANCELLED, error))
          ? Applied.TERMINAL
          : Applied.LOST;
    }
    return safeTerminal(() -> commitTerminal(TerminalKind.FAILED, error))
        ? Applied.TERMINAL
        : Applied.LOST;
  }

  /** TRANSIENT 与 INVALID_RESPONSE 共享 {@link InvocationRetryPolicy}：两者耗尽后都转为 FAILED terminal。 */
  private static boolean isRetryable(ModelInvocationError error) {
    return error.kind() == ProviderErrorKind.TRANSIENT
        || error.kind() == ProviderErrorKind.INVALID_RESPONSE;
  }

  private Applied finishUnknownLocked(ModelInvocationError error, List<Publish> publishes) {
    return safeTerminal(() -> commitTerminal(TerminalKind.UNKNOWN, error))
        ? Applied.TERMINAL
        : Applied.LOST;
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
                      lastCommittedSequence,
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

  private boolean commitSuccess(ProviderResponse response, long finalSequence) {
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
                String text = compaction ? response.text() : accumulator.text();
                String thinking = compaction ? response.thinking() : accumulator.thinking();
                if (!text.isEmpty() || !thinking.isEmpty()) {
                  ModelInvocation checkpointed =
                      model.checkpoint(
                          new StreamCheckpoint(attempt, finalSequence, text, thinking), now);
                  tx.updateModelInvocation(checkpointed);
                  model = checkpointed;
                }
                tx.updateModelInvocation(model.succeed(response, now));
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
                if (!text.isEmpty() || !thinking.isEmpty()) {
                  ModelInvocation checkpointed =
                      model.checkpoint(
                          new StreamCheckpoint(attempt, lastCommittedSequence, text, thinking),
                          now);
                  tx.updateModelInvocation(checkpointed);
                  model = checkpointed;
                }
                ModelInvocation next =
                    switch (kind) {
                      case FAILED -> model.fail(error, now);
                      case CANCELLED -> model.cancel(error, now);
                      case UNKNOWN -> model.unknown(error, now);
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

  private void deliverFailure(ModelInvocationError error) {
    if (!terminal.compareAndSet(false, true)) {
      return;
    }
    List<Publish> publishes = new ArrayList<>();
    Applied applied;
    synchronized (monitor) {
      if (abandoned.get()) {
        return;
      }
      applied = finishFailureLocked(error, publishes);
    }
    if (applied == Applied.LOST) {
      abandon();
      return;
    }
    publishAll(publishes);
    abandon();
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

  private void publishAll(List<Publish> publishes) {
    if (compaction) {
      return;
    }
    for (Publish publish : publishes) {
      appendRealtime(publish.event, publish.sequence);
    }
  }

  private void appendRealtime(ProviderStreamEvent event, long sequence) {
    try {
      realtimeEventSink.append(
          new RealtimeEvent.ModelDelta(
              threadId, invocationId, attempt, sequence, event, clock.instant()));
    } catch (RuntimeException failure) {
      log.warn("realtime model delta projection failed for invocation {}", invocationId, failure);
    }
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
    return nextSequence(lastCommittedSequence);
  }

  private static long nextSequence(long current) {
    if (current == Long.MAX_VALUE) {
      throw new IllegalStateException("model delta sequence overflow");
    }
    return current + 1;
  }

  private static String message(Throwable failure, String fallback) {
    if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
      return fallback;
    }
    return failure.getMessage();
  }

  private enum PendingKind {
    EVENT,
    SUCCEEDED,
    FAILED,
    UNKNOWN
  }

  /** 一次回调落地后的本地结果：事件已 checkpoint / retry 已排程 / terminal 已写 / ownership 已丢失。 */
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

  private record Pending(
      PendingKind kind,
      ProviderStreamEvent event,
      ProviderResponse response,
      ModelInvocationError error) {}

  private record Publish(ProviderStreamEvent event, long sequence) {}

  /** 内部回滚信号：callback 事务失去 Work ownership 时使当前事务完整回滚。 */
  private static final class ClaimLostSignal extends RuntimeException {
    private ClaimLostSignal() {
      super("claimed work lost at final fence", null, false, false);
    }
  }
}
