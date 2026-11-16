package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.spi.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionLifecycle;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimAdmissionGuard;
import fun.fengwk.kkstudio.harness.spi.work.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedExecutionSupport;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * 已通过 prepare 的 Model 启动路径：物化请求、本地准入、RUNNING 持久化，再把阻塞执行交给虚拟线程。
 *
 * <p>调用线程上完成物化、准入与 {@link ModelExecution#markRunning()}，因此返回值如实反映本次处理：Busy / 准入异常为
 * RESCHEDULED，确定性拒绝为 TERMINATED，已开始运行为 STARTED。RUNNING 持久化先于请求发出，并充当陈旧启动围栏；abandon / supersede
 * 在任一窗口获胜时都不启动，按未启动收敛本地代次。
 */
@Slf4j
final class ModelExecutionLauncher {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final LocalExecutionLifecycle lifecycle;
  private final ModelGateway gateway;
  private final RealtimeEventSink realtimeEventSink;
  private final ModelProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor heartbeatWorker;
  private final Executor executionExecutor;
  private final ClaimedExecutionSupport<ModelExecution> executions;
  private final ModelRequestMaterializer materializer = new ModelRequestMaterializer();

  ModelExecutionLauncher(
      HarnessStore store,
      BuiltinStore builtinStore,
      LocalExecutionLifecycle lifecycle,
      ModelGateway gateway,
      RealtimeEventSink realtimeEventSink,
      ModelProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      Executor executionExecutor,
      ClaimedExecutionSupport<ModelExecution> executions) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = Objects.requireNonNull(builtinStore, "builtinStore");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.heartbeatWorker = Objects.requireNonNull(heartbeatWorker, "heartbeatWorker");
    this.executionExecutor = Objects.requireNonNull(executionExecutor, "executionExecutor");
    this.executions = Objects.requireNonNull(executions, "executions");
  }

  /** 登记 execution 并启动 heartbeat，在调用线程上完成物化请求、准入与 RUNNING 持久化，只把阻塞的 Provider 运行与终态提交交给一个虚拟线程。 */
  ProcessResult start(
      ClaimedWork claim,
      ModelClaimPreparer.Result.Dispatched dispatched,
      ClaimAdmissionGuard.Ticket<ModelExecution> ticket,
      ProcessorLeaseConfig leaseConfig,
      Duration admitFailureDelay) {
    // 本次真实执行身份只在此处派生一次：组调用按 durable 成员游标取成员快照，attempt 是该次 READY->RUNNING 的目标值。
    ModelRequestSpec request = actualRequest(dispatched);
    int attempt = Math.addExact(dispatched.attempt(), 1);
    ModelExecution execution = newExecution(claim, dispatched, request, attempt, leaseConfig);
    if (!ticket.attach(execution)) {
      execution.abandon();
      return executions.isClosed()
          ? abortStart(claim, admitFailureDelay)
          : ProcessResult.LOST_OWNERSHIP;
    }
    boolean started = false;
    try {
      if (executions.isClosed()
          || !execution.startHeartbeat()
          || execution.abandoned()
          || ticket.isSuperseded()) {
        // close 已拍快照、scheduler 拒绝导致无法维持 lease，或 close / cancel 在 heartbeat 启动窗口内获胜：不启动，按 reschedule
        // 实际结果返回。
        return abortStart(claim, admitFailureDelay);
      }
      ProcessResult result =
          admitAndRun(execution, claim, dispatched, request, attempt, ticket, admitFailureDelay);
      if (result == ProcessResult.STARTED) {
        started = true;
      }
      return result;
    } finally {
      if (!started) {
        execution.abandon();
      }
    }
  }

  private ModelExecution newExecution(
      ClaimedWork claim,
      ModelClaimPreparer.Result.Dispatched dispatched,
      ModelRequestSpec request,
      int attempt,
      ProcessorLeaseConfig leaseConfig) {
    return new ModelExecution(
        store,
        gateway,
        realtimeEventSink,
        claim,
        dispatched.threadId(),
        attempt,
        dispatched.ownerSettledSummary(),
        request.toolBindings(),
        executionConfig(leaseConfig),
        request.model().providerName(),
        request.groupExecution() != null,
        clock,
        scheduler,
        heartbeatWorker,
        executionExecutor,
        executions::release,
        lifecycle);
  }

  private ModelProcessorConfig executionConfig(ProcessorLeaseConfig leaseConfig) {
    return new ModelProcessorConfig(
        () -> leaseConfig,
        config.retryPolicyProvider(),
        config.admitFailureDelayProvider(),
        config.streamConfig(),
        config.toolHistoryActionResolver(),
        config.httpErrorPolicyProvider());
  }

  /** 物化请求、准入并持久化 RUNNING；准入成功后把阻塞运行交给执行线程。 */
  private ProcessResult admitAndRun(
      ModelExecution execution,
      ClaimedWork claim,
      ModelClaimPreparer.Result.Dispatched dispatched,
      ModelRequestSpec request,
      int attempt,
      ClaimAdmissionGuard.Ticket<ModelExecution> ticket,
      Duration admitFailureDelay) {
    ProviderRequest providerRequest;
    try {
      providerRequest = materializeRequest(claim, dispatched, request);
    } catch (ClaimLostSignal lost) {
      return ProcessResult.LOST_OWNERSHIP;
    } catch (ProviderException setupFailure) {
      return rejectBeforeStart(
          claim,
          dispatched,
          setupFailure,
          new ModelInvocationError(
              setupFailure.kind(),
              messageOrClass(setupFailure, "cannot materialize provider request")));
    } catch (IllegalArgumentException | IllegalStateException setupFailure) {
      return rejectBeforeStart(
          claim,
          dispatched,
          setupFailure,
          new ModelInvocationError(
              ProviderErrorKind.INVALID_REQUEST,
              messageOrClass(setupFailure, "cannot materialize provider request")));
    }
    ModelGateway.Admission admission;
    try {
      admission =
          gateway.admit(
              new ModelGateway.Execution(
                  claim.target().id(), attempt, request.providerType(), providerRequest));
    } catch (RuntimeException failure) {
      // 契约：admit 抛异常表示尚未占用任何名额也未发出请求，按兜底延迟重排。
      log.warn(
          "model gateway admission failed for invocation {}: {}",
          claim.target().id(),
          ProcessorExceptions.describe(failure));
      return abortStart(claim, admitFailureDelay);
    }
    return switch (admission) {
      case ModelGateway.Busy busy -> reschedule(claim, busy.retryAfter())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
      case ModelGateway.Rejected rejected -> reject(claim, dispatched, rejected.error())
          ? ProcessResult.TERMINATED
          : ProcessResult.LOST_OWNERSHIP;
      case ModelGateway.Admitted admitted -> runAdmitted(
          execution, claim, admitted, ticket, admitFailureDelay);
    };
  }

  /** 在 MODEL claim 仍 owned 期间加载不可变 basis EntryPath，并纯重建内存 ProviderRequest。 */
  private ProviderRequest materializeRequest(
      ClaimedWork claim,
      ModelClaimPreparer.Result.Dispatched dispatched,
      ModelRequestSpec request) {
    EntryPath path =
        inTransaction(
            tx -> {
              if (tx.work().lockClaimed(claim).isEmpty()) {
                throw new ClaimLostSignal();
              }
              return tx.entries().loadPath(dispatched.requestHeadEntryId());
            });
    return materializer.materialize(path, request);
  }

  /** 先持久化 RUNNING（陈旧启动围栏），再把阻塞运行与终态提交交给执行线程。 */
  private ProcessResult runAdmitted(
      ModelExecution execution,
      ClaimedWork claim,
      ModelGateway.Admitted admitted,
      ClaimAdmissionGuard.Ticket<ModelExecution> ticket,
      Duration admitFailureDelay) {
    if (execution.abandoned() || ticket.isSuperseded()) {
      // close / cancel / supersede 在准入期间获胜：尚未登记执行，只释放一次名额。
      closeAdmitted(admitted);
      return abortStart(claim, admitFailureDelay);
    }
    try {
      if (!execution.markRunning()) {
        releaseUnstarted(execution, admitted);
        return ProcessResult.LOST_OWNERSHIP;
      }
      if (execution.abandoned() || ticket.isSuperseded()) {
        releaseUnstarted(execution, admitted);
        return ProcessResult.LOST_OWNERSHIP;
      }
      executionExecutor.execute(() -> runToTerminal(execution, admitted));
      return ProcessResult.STARTED;
    } catch (RejectedExecutionException rejected) {
      log.warn(
          "model execution executor rejected invocation {}", execution.invocationId(), rejected);
      releaseUnstarted(execution, admitted);
      return ProcessResult.LOST_OWNERSHIP;
    }
  }

  private void runToTerminal(ModelExecution execution, ModelGateway.Admitted admitted) {
    try {
      execution.finish(execution.run(admitted));
    } catch (RuntimeException unexpected) {
      // 绝不猜测 complete / reschedule：保留 lease 待过期后由后续 claim 收敛。
      log.error(
          "model execution failed unexpectedly for invocation {}: {}",
          execution.invocationId(),
          ProcessorExceptions.describe(unexpected));
      execution.abandon();
    } finally {
      if (closeAdmitted(admitted)) {
        if (execution.bodyStarted()) {
          execution.confirmStudioExit();
        } else {
          execution.confirmNotStarted();
        }
      } else {
        execution.exitNotProven();
      }
    }
  }

  /** 已取得物理句柄但确定未进入 body：资源释放成功才确认未启动，失败保持未证明退出。 */
  private void releaseUnstarted(ModelExecution execution, ModelGateway.Admitted admitted) {
    if (closeAdmitted(admitted)) {
      execution.confirmNotStarted();
    } else {
      execution.exitNotProven();
    }
  }

  /** 本地 admission 资源释放成功才允许确认；失败保持该代次未确认。 */
  private boolean closeAdmitted(ModelGateway.Admitted admitted) {
    try {
      admitted.close();
      return true;
    } catch (RuntimeException failure) {
      log.warn(
          "cannot release model admission resources; execution remains unconfirmed;"
              + " phase=MODEL_ADMISSION_CLOSE; exception={}",
          failure.getClass().getSimpleName());
      return false;
    }
  }

  private ProcessResult rejectBeforeStart(
      ClaimedWork claim,
      ModelClaimPreparer.Result.Dispatched dispatched,
      RuntimeException setupFailure,
      ModelInvocationError error) {
    log.warn(
        "model request materialization failed for invocation {}: {}",
        claim.target().id(),
        ProcessorExceptions.describe(setupFailure));
    return reject(claim, dispatched, error)
        ? ProcessResult.TERMINATED
        : ProcessResult.LOST_OWNERSHIP;
  }

  /** 启动前明确拒绝：READY -&gt; FAILED（attempt 不变）+ version+1 + 请求 THREAD Work + complete MODEL Work。 */
  private boolean reject(
      ClaimedWork claim,
      ModelClaimPreparer.Result.Dispatched dispatched,
      ModelInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          inTransaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(dispatched.threadId()).orElse(null);
                if (thread == null) {
                  return false;
                }
                ModelInvocation model = tx.lockModelInvocation(claim.target().id()).orElse(null);
                if (model == null) {
                  return false;
                }
                if (model.status() != ModelInvocationStatus.READY
                    || model.attempt() != dispatched.attempt()) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, dispatched.threadId()));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.updateModelInvocation(model.fail(error, now));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  /** 准入 Busy / 异常（肯定未发出请求）：invocation 保持 READY，仅按延迟重排 MODEL Work。 */
  private boolean reschedule(ClaimedWork claim, Duration delay) {
    return Boolean.TRUE.equals(
        inTransaction(
            tx -> {
              if (tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  private ProcessResult abortStart(ClaimedWork claim, Duration admitFailureDelay) {
    return reschedule(claim, admitFailureDelay)
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  /** 组调用按 durable 成员游标派生本次真实执行身份；普通调用原样返回冻结 spec。 */
  private static ModelRequestSpec actualRequest(ModelClaimPreparer.Result.Dispatched dispatched) {
    if (dispatched.request().groupExecution() == null) {
      return dispatched.request();
    }
    if (dispatched.groupProgress() == null) {
      throw new IllegalStateException("group invocation is missing durable group progress");
    }
    return dispatched.request().forMember(dispatched.groupProgress().memberIndex());
  }

  private static String messageOrClass(RuntimeException error, String fallback) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? fallback : detail;
  }

  private <T> T inTransaction(Function<BuiltinStore.Transaction, T> work) {
    return store.transaction(tx -> work.apply(builtinStore.in(tx)));
  }
}
