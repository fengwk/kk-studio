package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 一次 claim 的进程内 Model execution：Gateway listener 门控缓冲、durable fence 与 lease heartbeat。
 *
 * <p>listener 回调在 durable RUNNING 落地前只进缓冲区；两阶段激活（{@link #activate}）：attach handle -&gt; 持久化
 * markRunning -&gt; {@code handle.activate()}（Gateway 打开回调 gate）成功后按到达顺序重放。所有回调都先在校验 RUNNING +
 * attempt 与 claim ownership 的短事务中落地（text/thinking 单调 checkpoint，tool-call fragment 只推 sequence 不入
 * checkpoint），commit 后才 best-effort 发布 {@link RealtimeEvent.ModelDelta}。terminal 回调一次生效， duplicate
 * / late / stale 一律 no-op；lost ownership 立即关 gate、cancel handle 并停止 heartbeat，且不反写任何 durable 状态。
 * {@code handle.activate()} 抛异常即激活失败：恰好一次 UNKNOWN terminal，激活前缓冲的信号全部丢弃。
 */
@Slf4j
final class ModelExecution implements ModelGateway.Listener {

  private final HarnessStore store;
  private final RealtimeEventSink realtimeEventSink;
  private final ClaimedWork claim;
  private final long invocationId;
  private final long threadId;
  private final int attempt;
  private final ModelInvocationRequest request;
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
  private Instant lastCheckpointFlushedAt;
  private ModelGateway.Handle handle;

  ModelExecution(
      HarnessStore store,
      RealtimeEventSink realtimeEventSink,
      ClaimedWork claim,
      long threadId,
      int attempt,
      ModelInvocationRequest request,
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
    this.request = Objects.requireNonNull(request, "request");
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

  /** 启动 lease heartbeat；scheduler 拒绝时返回 false。 */
  boolean startHeartbeat() {
    return heartbeat.start(claim);
  }

  /**
   * Gateway admission 返回 Started 后调用：先安全 attach handle（若 execution 已被 abandon / heartbeat 已 lost，
   * 立即 best-effort cancel 且不 markRunning），再短事务重新验证 lease + DISPATCHING + proposed attempt 并
   * markRunning + Thread revision+1，随后调用 {@code handle.activate()} 让 Gateway 打开回调 gate，最后打开
   * listener 门并重放缓冲回调。activate 抛异常即激活失败：恰好一次 UNKNOWN terminal。失败（lost / stale / Stop 获胜）关闭 listener
   * 并 cancel handle，不写任何 durable 状态。handle 在 attach 之后的所有 abandon 路径都会被恰好 best-effort cancel。
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
    // 两阶段激活：durable markRunning 落地之后、打开自身 listener 门之前，才允许 Gateway 打开回调 gate / 启动外部执行。
    try {
      startedHandle.activate();
    } catch (RuntimeException failure) {
      // 先收敛 durable UNKNOWN，再记录：异常渲染（toString）绝不能 bypass 状态转换。
      ProcessResult result =
          activationFailure(
              new ModelInvocationError(
                  ProviderErrorKind.TRANSIENT,
                  "model gateway activation failed; provider outcome cannot be confirmed"));
      log.warn(
          "model gateway activation failed for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
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
   * （绝不 markRunning）；外部 {@link ModelGateway.Handle#cancel} 的调用**必须在 monitor 之外**（cancel 可能同步触发
   * listener 回调 / 阻塞等待确认，锁内调用会与需要 monitor 的回调路径死锁），因此锁内只置标记，锁外统一 best-effort cancel。
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
                ProviderErrorKind.INVALID_REQUEST, message(failure, "invalid provider callback")));
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
   * 处理一个 stream delta：sequence 单调分配；每个事件都先在校验 RUNNING + attempt 与 ownership 的短事务中落地 （text/thinking
   * 按 {@code checkpointFlushInterval} 节流写 checkpoint，首个 safe delta 立即 flush；tool-call fragment 永不
   * checkpoint），commit 后 best-effort 发布 realtime delta。
   */
  private Applied processEventLocked(ProviderStreamEvent event, List<Publish> publishes) {
    long sequence = nextSequence();
    accumulator.append(event);
    boolean safeContent =
        event instanceof ProviderStreamEvent.TextDelta
            || event instanceof ProviderStreamEvent.ThinkingDelta;
    Instant now = clock.instant();
    StreamCheckpoint pending = null;
    if (safeContent && shouldFlushCheckpoint(now)) {
      String text = accumulator.text();
      String thinking = accumulator.thinking();
      if (!text.isBlank() || !thinking.isBlank()) {
        pending = new StreamCheckpoint(attempt, sequence, text, thinking);
      }
    }
    final StreamCheckpoint pendingCheckpoint = pending;
    boolean committed =
        Boolean.TRUE.equals(store.transaction(tx -> persistEvent(tx, pendingCheckpoint, now)));
    if (!committed) {
      return Applied.LOST;
    }
    if (pendingCheckpoint != null) {
      lastCheckpointFlushedAt = now;
    }
    lastCommittedSequence = sequence;
    publishes.add(new Publish(event, sequence));
    return Applied.PROGRESSED;
  }

  /** 首个 safe delta 立即 flush；之后只有距上次 flush 达到 interval 才写 checkpoint。 */
  private boolean shouldFlushCheckpoint(Instant now) {
    return lastCheckpointFlushedAt == null
        || !lastCheckpointFlushedAt.plus(config.checkpointFlushInterval()).isAfter(now);
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
      validatedResponse = ModelResponseValidator.validate(request, completion.response());
    } catch (RuntimeException failure) {
      log.warn(
          "invalid provider response for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return finishFailureLocked(
          new ModelInvocationError(
              ProviderErrorKind.INVALID_REQUEST, message(failure, "invalid provider response")),
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
    if (error.kind() == ProviderErrorKind.TRANSIENT && config.retryPolicy().allowsRetry(attempt)) {
      Duration delay = config.retryPolicy().delayBeforeRetry(attempt);
      boolean committed = safeTerminal(() -> commitRetry(delay));
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
              tx.updateThread(thread.touchRevision(now));
              return true;
            }));
  }

  private boolean commitRetry(Duration delay) {
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
              if (model.status() != ModelInvocationStatus.RUNNING || model.attempt() != attempt) {
                return false;
              }
              tx.updateModelInvocation(model.retryReady(now));
              tx.updateThread(thread.touchRevision(now));
              tx.rescheduleWork(claim, now, now.plus(delay));
              return true;
            }));
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
                String text = request.compaction() == null ? accumulator.text() : response.text();
                String thinking =
                    request.compaction() == null ? accumulator.thinking() : response.thinking();
                if (!text.isBlank() || !thinking.isBlank()) {
                  ModelInvocation checkpointed =
                      model.checkpoint(
                          new StreamCheckpoint(attempt, finalSequence, text, thinking), now);
                  tx.updateModelInvocation(checkpointed);
                  model = checkpointed;
                }
                tx.updateModelInvocation(model.succeed(response, now));
                tx.updateThread(thread.touchRevision(now));
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
                ModelInvocation next =
                    switch (kind) {
                      case FAILED -> model.fail(error, now);
                      case CANCELLED -> model.cancel(error, now);
                      case UNKNOWN -> model.unknown(error, now);
                    };
                tx.updateModelInvocation(next);
                tx.updateThread(thread.touchRevision(now));
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
    if (request.compaction() != null) {
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
