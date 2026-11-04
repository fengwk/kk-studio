package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.spi.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimAdmissionGuard;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.spi.work.WorkHeartbeat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 一次 claim 的进程内 Model execution 编排：运行线程上的流式增量接收、终态提交、所有权围栏与 lease heartbeat。
 *
 * <p>整个 execution 由 {@link ModelProcessor} 的一个虚拟线程驱动：{@link #markRunning} 持久化 RUNNING，{@link #run}
 * 阻塞等待 Gateway 结束（增量在运行线程上同步进入 {@link ModelStreamPublisher#onEvent}），{@link #finish}
 * 提交终态。三个协作组件分别负责单一职责：
 *
 * <ul>
 *   <li>{@link ModelStreamPublisher}：增量接收、推送窗口与追帧节奏，拥有流式状态与唯一锁；
 *   <li>{@link ModelCheckpointCommitter}：累计 text/thinking 的 checkpoint 落库与 RUNNING + attempt +
 *       claim 围栏；
 *   <li>{@link ModelTerminalCommitter}：成功 / 重试 / 失败 / UNKNOWN / 模型组终态的唯一提交事务。
 * </ul>
 *
 * <p>推送与落库是两条独立节奏：delta 在 {@link ModelStreamConfig#pushWindow()} 内合并为小批次后直接发布（不等待数据库、不依赖提交）；累计
 * text/thinking 按 {@link ModelStreamConfig#checkpointInterval()}（或追帧请求）持久化，事务只锁 ModelInvocation 与
 * claimed Work（不取执行树锁，因为不唤醒 Thread），围栏重校验 RUNNING + attempt + lease。terminal / retry 只写本 Thread 的
 * Thread/Model/Work：事务按本 Thread 行 -&gt; ModelInvocation -&gt; claimed Work 加锁并以 Thread 行锁与
 * delete/Stop 串行化，唤醒 THREAD Work 只是 hint，不需要执行树锁。随后只发布已提交的终态补齐 delta；lost ownership 或 Stop 竞态下
 * {@link #abandon} 中断运行线程并停止一切发布与写入。
 */
@Slf4j
public final class ModelExecution
    implements ModelGateway.Sink, ClaimAdmissionGuard.GuardedExecution {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ExecutionExitConfirmer exitConfirmer;
  private final ClaimedWork claim;
  private final UUID invocationId;
  private final UUID executionId = UUID.randomUUID();
  private final UUID threadId;
  private final int attempt;

  /** 登记成功后冻结的本代身份。确认只比对这一身份，不读取之后可能已更换的 lease。 */
  private volatile ExecutionExitFact registered;

  private final Clock clock;
  private final WorkHeartbeat heartbeat;
  private final Consumer<ModelExecution> ownerRelease;
  private final LongSupplier nanoTime;

  private final AtomicBoolean abandoned = new AtomicBoolean();

  /** 保护 {@link #runner}：abandon 只在 Gateway 运行窗口内中断运行线程，绝不打断持久化事务。 */
  private final Object runGuard = new Object();

  private Thread runner;

  /** 是否真正进入 Gateway body；在 runner 登记锁内与 cancel 判定串行化。 */
  private volatile boolean bodyStarted;

  private final ModelStreamPublisher stream;
  private final ModelTerminalCommitter terminal;

  /**
   * 完整构造器：{@code providerName} 是本次 Invocation 冻结的 Provider 身份；{@code nanoTime} 是采集流式生成计时的单调时间源
   * （checkpoint 节奏固定用 System.nanoTime）， 生产默认 {@link System#nanoTime}，测试可注入可控来源。
   */
  public ModelExecution(
      HarnessStore store,
      ModelGateway gateway,
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
        gateway,
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
      ModelGateway gateway,
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
    this.claim = Objects.requireNonNull(claim, "claim");
    this.invocationId = claim.target().id();
    this.threadId = Objects.requireNonNull(threadId, "threadId");
    this.attempt = attempt;
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.ownerRelease = Objects.requireNonNull(ownerRelease, "ownerRelease");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.heartbeat =
        new WorkHeartbeat(
            this.store,
            Objects.requireNonNull(scheduler, "scheduler"),
            Objects.requireNonNull(heartbeatWorker, "heartbeatWorker"),
            Objects.requireNonNull(config, "config").leaseConfig(),
            this::abandon);
    ModelCheckpointCommitter checkpoints =
        new ModelCheckpointCommitter(this.store, builtinStore, this.claim, invocationId, attempt);
    this.stream =
        new ModelStreamPublisher(
            threadId,
            invocationId,
            attempt,
            config,
            Objects.requireNonNull(realtimeEventSink, "realtimeEventSink"),
            this.clock,
            scheduler,
            Objects.requireNonNull(flushExecutor, "flushExecutor"),
            this.nanoTime,
            abandoned::get,
            checkpoints,
            this::abandon);
    this.terminal =
        new ModelTerminalCommitter(
            this.store,
            builtinStore,
            Objects.requireNonNull(gateway, "gateway"),
            config,
            this.claim,
            threadId,
            invocationId,
            attempt,
            ownerSettledSummary,
            Objects.requireNonNull(bindings, "bindings"),
            requireProviderName(providerName),
            stream);
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
                    ThreadState thread = tx.threads().lock(threadId).orElse(null);
                    if (thread == null) {
                      return false;
                    }
                    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
                    if (model == null || tx.work().lockClaimed(claim).isEmpty()) {
                      return false;
                    }
                    if (model.status() != ModelInvocationStatus.READY
                        || model.attempt() != attempt - 1) {
                      return false;
                    }
                    tx.updateModelInvocation(model.markRunning(now));
                    tx.threads().update(thread.touchVersion(now));
                    registered =
                        ExecutionExitLedger.register(
                            tx,
                            executionId(),
                            threadId,
                            invocationId,
                            attempt,
                            claim.leaseToken(),
                            ExecutionExitKind.DRIVER,
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
    stream.onEvent(event);
  }

  /**
   * 追帧请求：仅当本 execution 仍是 active owner 且 attempt 匹配时，合并安排一次立即 checkpoint。
   *
   * <p>best-effort：每个 execution 最多一项 in-flight 追帧任务；重复请求在已有任务未完成时返回 {@code true}（已有请求会覆盖当前累计状态）。
   * executor 拒绝只返回 {@code false}，绝不 abandon execution，也不影响周期 checkpoint 与 terminal。终态、取消/abandon 或
   * attempt 不匹配返回 {@code false}。
   */
  public boolean requestCheckpoint(int requestedAttempt) {
    return stream.requestCheckpoint(requestedAttempt);
  }

  /**
   * 运行结束后在运行线程上提交终态并释放 execution。{@code outcome == null}（已 abandon）或提交时所有权已丢失一律不写任何状态，durable 状态留给
   * lease recovery 收敛。
   */
  public ProcessResult finish(ModelGateway.Outcome outcome) {
    ModelTerminalCommitter.Applied applied = ModelTerminalCommitter.Applied.LOST;
    try {
      if (outcome != null && !abandoned.get()) {
        long successNanos = outcome instanceof ModelGateway.Succeeded ? nanoTime.getAsLong() : 0L;
        // Renderer 属于可插拔 Tool 代码，不在 execution 锁内调用。
        ProviderCompletion completion = null;
        if (outcome instanceof ModelGateway.Succeeded succeeded && stream.eventFailure() == null) {
          completion = terminal.freezeHistoryActions(succeeded.completion());
        }
        List<ModelStreamPublisher.Publish> publishes = new ArrayList<>();
        synchronized (stream.lock()) {
          stream.markFinished();
          if (!abandoned.get()) {
            applied = terminal.commit(outcome, completion, successNanos, publishes);
          }
        }
        if (applied != ModelTerminalCommitter.Applied.LOST) {
          stream.publish(publishes);
        }
      }
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist model terminal for {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      applied = ModelTerminalCommitter.Applied.LOST;
    } finally {
      synchronized (stream.lock()) {
        stream.markFinished();
      }
      abandon();
    }
    return switch (applied) {
      case TERMINAL -> ProcessResult.TERMINATED;
      case RETRY -> ProcessResult.RESCHEDULED;
      case LOST -> ProcessResult.LOST_OWNERSHIP;
    };
  }

  /** 停止 heartbeat 与 tick、中断运行线程（仅 Gateway 运行窗口内）并从 registry 释放；幂等。 */
  @Override
  public void abandon() {
    if (!abandoned.compareAndSet(false, true)) {
      return;
    }
    heartbeat.stop();
    stream.cancelTick();
    synchronized (runGuard) {
      if (runner != null && runner != Thread.currentThread()) {
        runner.interrupt();
      }
    }
    ownerRelease.accept(this);
  }
}
