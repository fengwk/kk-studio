package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.api.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.builtin.port.store.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.spi.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;

import java.time.Clock;
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
import java.util.function.LongSupplier;

/**
 * 一次 Model attempt 的流式发布组件：在运行线程上接收增量、维护累计与 sequence，并按窗口把有损 delta 发布到实时通道。
 *
 * <p>推送与落库是两条独立节奏：delta 在 {@link ModelStreamConfig#pushWindow()} 内合并为小批次后直接发布（不等待数据库、不依赖提交）； 累计
 * text/thinking 按 {@link ModelStreamConfig#checkpointInterval()} 或追帧请求交给 {@link
 * ModelCheckpointCommitter} 落库，提交成功后才发布 {@link RealtimeEvent.ModelCheckpoint}。增量只追加内存缓冲，任何推送失败都
 * 只降低实时体验。
 *
 * <p>本类拥有全部流式状态与唯一的锁。终态提交必须在 {@link #lock()} 内调用本类的完成 / 取快照方法，保证 checkpoint 与终态提交天然串行；锁外只允许 {@link
 * #publish(List)} 发布已提交的尾部 delta。
 */
@Slf4j
final class ModelStreamPublisher {

  private final UUID threadId;
  private final UUID invocationId;
  private final int attempt;
  private final ModelProcessorConfig config;
  private final RealtimeEventSink realtimeEventSink;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor flushExecutor;
  private final LongSupplier nanoTime;
  private final BooleanSupplier abandoned;
  private final ModelCheckpointCommitter checkpoints;
  private final Runnable abandonAction;

  private final AtomicBoolean tickQueued = new AtomicBoolean();
  private final AtomicBoolean catchUpInFlight = new AtomicBoolean();

  /** 保护下列流式状态与所有发布 / checkpoint。 */
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

  ModelStreamPublisher(
      UUID threadId,
      UUID invocationId,
      int attempt,
      ModelProcessorConfig config,
      RealtimeEventSink realtimeEventSink,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor flushExecutor,
      LongSupplier nanoTime,
      BooleanSupplier abandoned,
      ModelCheckpointCommitter checkpoints,
      Runnable abandonAction) {
    this.threadId = Objects.requireNonNull(threadId, "threadId");
    this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
    this.attempt = attempt;
    this.config = Objects.requireNonNull(config, "config");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.flushExecutor = Objects.requireNonNull(flushExecutor, "flushExecutor");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.abandoned = Objects.requireNonNull(abandoned, "abandoned");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    this.abandonAction = Objects.requireNonNull(abandonAction, "abandonAction");
  }

  /** 流式状态与终态提交共用的锁；调用方在读写快照或完成响应时必须持有。 */
  Object lock() {
    return lock;
  }

  /** 运行线程上的增量入口：只追加内存缓冲，不触达数据库。 */
  void onEvent(ProviderStreamEvent event) {
    if (abandoned.getAsBoolean()) {
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
              ProviderErrorKind.INVALID_RESPONSE,
              providerFailureMessage(failure, "invalid provider event"));
      throw new StreamAborted();
    }
    if (abandoned.getAsBoolean()) {
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
      abandonAction.run();
    }
  }

  /** 调度线程只负责把刷新交给 executor；上一轮未完成时合并跳过。 */
  private void submitTick() {
    if (abandoned.getAsBoolean() || !tickQueued.compareAndSet(false, true)) {
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
      abandonAction.run();
    }
  }

  private void runTick() {
    try {
      synchronized (lock) {
        if (abandoned.getAsBoolean() || finished) {
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
   * 追帧请求：仅当本次 attempt 匹配时合并安排一次立即 checkpoint。
   *
   * <p>best-effort：每个 execution 最多一项 in-flight 追帧任务；重复请求在已有任务未完成时返回 {@code true}（已有请求会覆盖当前累计状态）。
   * executor 拒绝只返回 {@code false}，绝不 abandon execution，也不影响周期 checkpoint 与 terminal。终态、取消/abandon 或
   * attempt 不匹配返回 {@code false}。
   */
  boolean requestCheckpoint(int requestedAttempt) {
    if (requestedAttempt != attempt || abandoned.getAsBoolean()) {
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
        if (!abandoned.getAsBoolean() && !finished) {
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
    if (!checkpoints.persist(checkpoint)) {
      abandonAction.run();
      return;
    }
    lastCheckpointSequence = checkpoint.sequence();
    lastCheckpointNanos = System.nanoTime();
    if (!abandoned.getAsBoolean()) {
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
    publish(toPush);
  }

  /** 预校验终态 response 并生成候选 durable response 与尾部 gap，不修改累积器；lock 内调用。 */
  ModelStreamAccumulator.PreparedCompletion prepareComplete(ProviderResponse response) {
    return accumulator.prepareComplete(response);
  }

  /** 用终态 response 收敛累积内容：返回可持久化的 response 与按 sequence 编号的尾部 gap。lock 内调用。 */
  Completion complete(ModelStreamAccumulator.PreparedCompletion prepared) {
    ModelStreamAccumulator.Completion completion = prepared.apply();
    List<Publish> gaps = new ArrayList<>();
    for (ProviderStreamEvent gap : completion.gaps()) {
      long sequence = nextSequence();
      if (isSafeDelta(gap)) {
        lastSafeSequence = sequence;
      }
      gaps.add(new Publish(gap, sequence));
    }
    return new Completion(completion.response(), List.copyOf(gaps));
  }

  /** 当前累计的可恢复快照；lock 内调用。 */
  Snapshot snapshot() {
    return new Snapshot(lastSafeSequence, accumulator.text(), accumulator.thinking());
  }

  /** 尚未发布的内存 delta（按 sequence 升序）；lock 内调用。 */
  List<Publish> bufferedPublishes() {
    return List.copyOf(pushBuffer);
  }

  /** 丢弃尚未发布的内存 delta；lock 内调用。 */
  void clearPublishes() {
    clearPushBufferLocked();
  }

  private void clearPushBufferLocked() {
    pushBuffer.clear();
    pushBufferBytes = 0;
  }

  /** 标记流已结束并停止周期 tick；lock 内调用，幂等。 */
  void markFinished() {
    finished = true;
    cancelTick();
  }

  /** 取消周期 tick；abandon 在任意线程调用，幂等。 */
  void cancelTick() {
    ScheduledFuture<?> timer = tick;
    if (timer != null) {
      timer.cancel(false);
    }
  }

  /** 首个非空输出 delta 到成功回调的生成计时；任一端点缺失时返回 null。 */
  Long decodeDurationMillis(long successNanos) {
    Long startNanos = firstOutputNanos;
    if (startNanos == null) {
      return null;
    }
    return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, successNanos - startNanos));
  }

  /** 增量与累积器不一致的失败；只在已记录后读取。 */
  ModelInvocationError eventFailure() {
    return eventFailure;
  }

  /**
   * 按 sequence 顺序发布一批 delta。
   *
   * <p>发布以 {@link ModelStreamConfig} 的批次上界分块（事件数 + 增量载荷字节），每个分块恰好调用一次 {@link
   * RealtimeEventSink#appendAll}：终态补齐的大量 gap delta 或单个超大事件都不会构造无界列表或单次无界调用。分块失败按调用隔离，只降低实时体验，
   * 绝不改变已提交的 durable 状态或终态。
   */
  void publish(List<Publish> publishes) {
    ModelStreamConfig publishConfig = config.streamConfig();
    List<RealtimeEvent> chunk = new ArrayList<>();
    long chunkPayloadBytes = 0;
    for (Publish publish : publishes) {
      if (abandoned.getAsBoolean()) {
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
    if (events.isEmpty() || abandoned.getAsBoolean()) {
      return;
    }
    try {
      realtimeEventSink.appendAll(events);
    } catch (RuntimeException failure) {
      log.warn("realtime model delta projection failed for invocation {}", invocationId, failure);
    }
  }

  private void publishCheckpointEvent(long sequence) {
    dispatchRealtime(
        List.of(
            new RealtimeEvent.ModelCheckpoint(
                threadId, invocationId, attempt, sequence, clock.instant())));
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

  /** 非抛出的失败描述：空白消息回退到调用方给出的稳定文案。 */
  static String providerFailureMessage(Throwable failure, String fallback) {
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

  /** 运行线程内部中止信号：execution 已 abandon 或增量不一致，调用栈直接展开回 Gateway。 */
  static final class StreamAborted extends RuntimeException {
    private StreamAborted() {
      super("model stream aborted", null, false, false);
    }
  }

  /** 已收敛的最终 response 与按发送顺序编号的尾部 gap。 */
  record Completion(ProviderResponse response, List<Publish> gaps) {}

  /** 提交终态时读取的流式进度快照。 */
  record Snapshot(long lastSafeSequence, String text, String thinking) {}

  /** 尚未发布或需要补发的一次 delta。 */
  record Publish(ProviderStreamEvent event, long sequence) {}
}
