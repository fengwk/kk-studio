package fun.fengwk.kkstudio.harness.builtin.processor;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.ModelCheckpointRequester;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.spi.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimAdmissionGuard;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedExecutionSupport;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Target Model processor：消费 dispatcher 已 claim 的 MODEL Work，驱动一次 Model invocation 的完整生命周期。
 *
 * <p>只依赖单一 {@link HarnessStore} + {@link ModelGateway} + {@link RealtimeEventSink}，不自行全局 poll；不做任何
 * Entry / head / Usage 写入。短事务只锁 own Thread -&gt; ModelInvocation -&gt; Work （同事务需要 THREAD Work 时先
 * request/upsert 并锁定 THREAD Work，再 lockClaimed MODEL Work）；不取执行树锁、Session 锁或 祖先锁。公共所有权与准入协议见包说明。
 *
 * <p>执行模型：{@link #process} 在 dispatcher 线程上完成 claim 准入与短 prepare 事务（{@link
 * ModelClaimPreparer}），再把物化请求、本地 admission、RUNNING 持久化与阻塞执行的启动交给 {@link
 * ModelExecutionLauncher}。heartbeat 在整个阻塞期间续租当前 Work lease。
 *
 * <p>崩溃或失联后 RUNNING 行的 lease 过期，由下一次 claim 按既有重试策略收敛，绝不重放可能已发出的请求。{@code markRunning} 的 READY +
 * attempt + claim 校验同时充当陈旧启动围栏：Stop / 跨实例 recovery 已获胜时绝不发出请求。
 */
public final class ModelProcessor implements ModelCheckpointRequester, AutoCloseable {

  private final ModelProcessorConfig config;
  private final ClaimedExecutionSupport<ModelExecution> executions;
  private final ModelClaimPreparer preparer;
  private final ModelExecutionLauncher launcher;

  /**
   * {@code executionExecutor} 必须为每个任务提供独立的（虚拟）线程：执行线程在 Provider 调用期间长时间阻塞，同时承载 execution 的定时刷新任务。
   */
  public ModelProcessor(
      HarnessStore store,
      ModelGateway gateway,
      RealtimeEventSink realtimeEventSink,
      ModelProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      Executor executionExecutor,
      LocalExecutionLifecycle lifecycle) {
    HarnessStore requiredStore = Objects.requireNonNull(store, "store");
    BuiltinStore builtinStore = requireBuiltinStore(requiredStore);
    ModelGateway requiredGateway = Objects.requireNonNull(gateway, "gateway");
    this.config = Objects.requireNonNull(config, "config");
    this.executions = new ClaimedExecutionSupport<>(requiredStore, BuiltinWorkType.MODEL);
    this.preparer =
        new ModelClaimPreparer(
            requiredStore, builtinStore, requiredGateway, this.config, executions);
    this.launcher =
        new ModelExecutionLauncher(
            requiredStore,
            builtinStore,
            Objects.requireNonNull(lifecycle, "lifecycle"),
            requiredGateway,
            Objects.requireNonNull(realtimeEventSink, "realtimeEventSink"),
            this.config,
            HarnessStoreTime.millisecondClock(clock),
            Objects.requireNonNull(scheduler, "scheduler"),
            Objects.requireNonNull(heartbeatWorker, "heartbeatWorker"),
            Objects.requireNonNull(executionExecutor, "executionExecutor"),
            executions);
  }

  private static BuiltinStore requireBuiltinStore(HarnessStore store) {
    if (store instanceof BuiltinStore builtin) {
      return builtin;
    }
    throw new IllegalArgumentException("model processor requires a BuiltinStore runtime store");
  }

  /**
   * 处理一次 dispatcher 已 claim 的 MODEL Work。
   *
   * <p>经 {@link ClaimAdmissionGuard} 准入后，prepare 短事务复核状态与所有权：READY 只确认 lease margin 并交给执行线程（不写
   * invocation）；RUNNING（旧 lease 过期恢复）按既有重试策略记录一次失败 attempt 后回到 READY 并重排 MODEL Work，预算耗尽则以 FAILED
   * 明确终止并唤醒 THREAD，绝不重放 Provider；terminal 行只确保 THREAD Work 请求 （resultEntryId 仍 null 时）后 complete
   * MODEL Work，不重复 bump version。
   *
   * @return 启动为 STARTED，重排为 RESCHEDULED，已收敛为 TERMINATED，失权或重复投递为 LOST_OWNERSHIP
   */
  public ProcessResult process(ClaimedWork claim) {
    ProcessorLeaseConfig leaseConfig = config.leaseConfigProvider().get();
    Duration admitFailureDelay = config.admitFailureDelayProvider().get();
    ClaimAdmissionGuard.Ticket<ModelExecution> ticket = executions.admit(claim);
    if (ticket == null) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    try (ticket) {
      return switch (preparer.inTransaction(claim, leaseConfig)) {
        case ModelClaimPreparer.Result.Lost ignored -> ProcessResult.LOST_OWNERSHIP;
        case ModelClaimPreparer.Result.Terminated ignored -> ProcessResult.TERMINATED;
        case ModelClaimPreparer.Result.Retry ignored -> ProcessResult.RESCHEDULED;
        case ModelClaimPreparer.Result.Dispatched dispatched -> launcher.start(
            claim, dispatched, ticket, leaseConfig, admitFailureDelay);
      };
    } catch (ClaimLostSignal ignored) {
      return ProcessResult.LOST_OWNERSHIP;
    }
  }

  /**
   * 取消一个进程内本地 execution（供 Stop durable commit 后 best-effort 调用）：中断运行线程并关闭连接；结果不反写任何 durable 状态。
   *
   * @return 是否存在并已取消对应本地 execution
   */
  public boolean cancel(UUID invocationId) {
    return executions.cancel(invocationId);
  }

  /**
   * 追帧：由 active execution 集合精确定位当前 owner，仅当 {@code attempt} 与持有中的 execution 一致时立即持久化 checkpoint 并发布
   * checkpoint 事件。
   *
   * <p>不匹配（invocation 不在本进程执行、attempt 已被其它 attempt 接管、execution 已终态/取消/abandoned）返回 {@code false}
   * 且不触发任何持久化或发布；只有确实登记为 active 且 attempt 一致时返回 {@code true}。不阻塞调用方，可从实时通道的任意线程调用。
   */
  @Override
  public boolean requestCheckpoint(UUID invocationId, int attempt) {
    Objects.requireNonNull(invocationId, "invocationId");
    if (attempt <= 0) {
      return false;
    }
    ModelExecution execution = executions.execution(invocationId);
    return execution != null && execution.requestCheckpoint(attempt);
  }

  /** 取消全部本地 execution 并停止各自 heartbeat；不 shutdown 调用方注入的 scheduler / executor；幂等。 */
  @Override
  public void close() {
    executions.close();
  }

  /** 当前 JVM 是否持有任何活跃的本地 Model execution。 */
  public boolean hasActiveExecution() {
    return executions.hasActiveExecution();
  }
}
