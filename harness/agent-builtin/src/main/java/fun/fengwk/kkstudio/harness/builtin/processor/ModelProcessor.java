package fun.fengwk.kkstudio.harness.builtin.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.model.StreamCheckpoint;
import fun.fengwk.kkstudio.harness.builtin.port.ModelGateway;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.processor.ClaimAdmissionGuard;
import fun.fengwk.kkstudio.harness.runtime.processor.ClaimLostSignal;
import fun.fengwk.kkstudio.harness.runtime.processor.ClaimedExecutionSupport;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorExceptions;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryDecision;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * Target Model processor：消费 dispatcher 已 claim 的 MODEL Work，驱动一次 Model invocation 的完整生命周期。
 *
 * <p>只依赖单一 {@link HarnessStore} + {@link ModelGateway} + {@link RealtimeEventSink}，不自行全局 poll；不做任何
 * Entry / head / Usage 写入。短事务只锁 own Thread -&gt; ModelInvocation -&gt; Work （同事务需要 THREAD Work 时先
 * request/upsert 并锁定 THREAD Work，再 lockClaimed MODEL Work）；不取执行树锁、Session 锁或 祖先锁。公共所有权与准入协议见包说明。
 *
 * <p>执行模型：{@link #process} 在 dispatcher 线程上完成 claim 校验、短 prepare 事务、物化请求、{@link
 * ModelGateway#admit}（纯本地检查，Busy / Rejected 都发生在任何请求发出前，此时 invocation 仍是 READY）与 {@link
 * ModelExecution#markRunning}（READY -&gt; RUNNING，先于请求发出持久化），随后只把阻塞的 {@link ModelExecution#run}
 * （增量在执行线程同步到达）与终态提交交给 executor 上的一个虚拟线程并返回 {@link ProcessResult#STARTED}。heartbeat 在整个阻塞期间续租当前
 * Work lease。
 *
 * <p>崩溃或失联后 RUNNING 行的 lease 过期，由下一次 claim 按既有重试策略收敛，绝不重放可能已发出的请求。{@code markRunning} 的 READY +
 * attempt + claim 校验同时充当陈旧启动围栏：Stop / 跨实例 recovery 已获胜时绝不发出请求。
 */
@Slf4j
public final class ModelProcessor implements AutoCloseable {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;
  private final ExecutionExitConfirmer exitConfirmer;
  private final ModelGateway gateway;
  private final RealtimeEventSink realtimeEventSink;
  private final ModelProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor heartbeatWorker;
  private final Executor executionExecutor;
  private final ClaimedExecutionSupport<ModelExecution> executions;
  private final ModelRequestMaterializer materializer = new ModelRequestMaterializer();

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
      ExecutionExitConfirmer exitConfirmer) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = requireBuiltinStore(store);
    this.exitConfirmer = Objects.requireNonNull(exitConfirmer, "exitConfirmer");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.heartbeatWorker = Objects.requireNonNull(heartbeatWorker, "heartbeatWorker");
    this.executionExecutor = Objects.requireNonNull(executionExecutor, "executionExecutor");
    this.executions =
        new ClaimedExecutionSupport<>(store, WorkTargetType.MODEL, config.leaseConfig());
  }

  private static BuiltinStore requireBuiltinStore(HarnessStore store) {
    if (store instanceof BuiltinStore builtin) {
      return builtin;
    }
    throw new IllegalArgumentException("model processor requires a BuiltinStore runtime store");
  }

  private <T> T inTransaction(Function<BuiltinStore.Transaction, T> work) {
    return store.transaction(tx -> work.apply(builtinStore.in(tx)));
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
    ClaimAdmissionGuard.Ticket<ModelExecution> ticket = executions.admit(claim);
    if (ticket == null) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    try (ticket) {
      Prepare prepare = inTransaction(tx -> prepareLocked(tx, claim, tx.now()));
      return switch (prepare) {
        case Prepare.Lost ignored -> ProcessResult.LOST_OWNERSHIP;
        case Prepare.Terminated ignored -> ProcessResult.TERMINATED;
        case Prepare.Retry ignored -> ProcessResult.RESCHEDULED;
        case Prepare.Dispatched dispatched -> start(claim, dispatched, ticket);
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

  /**
   * 登记 execution 并启动 heartbeat，在调用线程上完成物化请求、准入与 RUNNING 持久化，只把阻塞的 Provider 运行与终态提交交给一个虚拟线程。
   * 因此返回值如实反映本次处理：Busy / 准入异常为 RESCHEDULED，确定性拒绝为 TERMINATED，已开始运行为 STARTED。
   */
  private ProcessResult start(
      ClaimedWork claim,
      Prepare.Dispatched dispatched,
      ClaimAdmissionGuard.Ticket<ModelExecution> ticket) {
    ModelExecution execution =
        new ModelExecution(
            store,
            realtimeEventSink,
            claim,
            dispatched.threadId(),
            Math.addExact(dispatched.attempt(), 1),
            dispatched.ownerSettledSummary(),
            actualRequest(dispatched).toolBindings(),
            config,
            actualRequest(dispatched).model().providerName(),
            clock,
            scheduler,
            heartbeatWorker,
            executionExecutor,
            executions::release,
            exitConfirmer);
    if (!ticket.attach(execution)) {
      execution.abandon();
      return executions.isClosed() ? abortStart(claim) : ProcessResult.LOST_OWNERSHIP;
    }
    boolean started = false;
    try {
      if (executions.isClosed()
          || !execution.startHeartbeat()
          || execution.abandoned()
          || ticket.isSuperseded()) {
        // close 已拍快照、scheduler 拒绝导致无法维持 lease，或 close / cancel 在 heartbeat 启动窗口内获胜：不启动，按 reschedule
        // 实际结果返回。
        return abortStart(claim);
      }
      ProcessResult result = admitAndRun(execution, claim, dispatched, ticket);
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

  private ProcessResult abortStart(ClaimedWork claim) {
    return reschedule(claim, config.admitFailureDelay())
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  /** 物化请求、准入并持久化 RUNNING；准入成功后把阻塞运行交给执行线程。 */
  private ProcessResult admitAndRun(
      ModelExecution execution,
      ClaimedWork claim,
      Prepare.Dispatched dispatched,
      ClaimAdmissionGuard.Ticket<ModelExecution> ticket) {
    UUID invocationId = claim.target().id();
    ProviderRequest providerRequest;
    try {
      providerRequest = materializeRequest(claim, dispatched);
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
                  invocationId,
                  Math.addExact(dispatched.attempt(), 1),
                  actualRequest(dispatched).providerType(),
                  actualRequest(dispatched).providerConnectionGenerationId(),
                  providerRequest));
    } catch (RuntimeException failure) {
      // 契约：admit 抛异常表示尚未占用任何名额也未发出请求，按兜底延迟重排。
      log.warn(
          "model gateway admission failed for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return abortStart(claim);
    }
    return switch (admission) {
      case ModelGateway.Busy busy -> {
        yield reschedule(claim, busy.retryAfter())
            ? ProcessResult.RESCHEDULED
            : ProcessResult.LOST_OWNERSHIP;
      }
      case ModelGateway.Rejected rejected -> {
        yield reject(claim, dispatched, rejected.error())
            ? ProcessResult.TERMINATED
            : ProcessResult.LOST_OWNERSHIP;
      }
      case ModelGateway.Admitted admitted -> runAdmitted(execution, claim, admitted, ticket);
    };
  }

  /** 先持久化 RUNNING（陈旧启动围栏），再把阻塞运行与终态提交交给执行线程。 */
  private ProcessResult runAdmitted(
      ModelExecution execution,
      ClaimedWork claim,
      ModelGateway.Admitted admitted,
      ClaimAdmissionGuard.Ticket<ModelExecution> ticket) {
    if (execution.abandoned() || ticket.isSuperseded()) {
      // close / cancel / supersede 在准入期间获胜：尚未登记执行，只释放一次名额。
      closeAdmitted(admitted);
      return abortStart(claim);
    }
    try {
      if (!execution.markRunning()) {
        if (execution.registered()) {
          releaseUnstarted(execution, admitted);
        } else {
          closeAdmitted(admitted);
        }
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

  /** 已登记但确定未进入 body：清理成功才确认未启动，失败保持未确认且不重复关闭。 */
  private void releaseUnstarted(ModelExecution execution, ModelGateway.Admitted admitted) {
    if (closeAdmitted(admitted)) {
      execution.confirmNotStarted();
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
      }
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
      Prepare.Dispatched dispatched,
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

  /** 在事务内按 own Thread -> Model -> Work 锁序锁定并校验状态，不取树锁/祖先锁，依据 durable status 路由准备结果。 */
  private Prepare prepareLocked(BuiltinStore.Transaction tx, ClaimedWork claim, Instant now) {
    UUID invocationId = claim.target().id();
    ModelInvocation peek = tx.findModelInvocation(invocationId).orElse(null);
    if (peek == null) {
      return new Prepare.Lost();
    }
    ThreadState thread = tx.threads().lock(peek.threadId()).orElse(null);
    if (thread == null) {
      return new Prepare.Lost();
    }
    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
    if (model == null) {
      return new Prepare.Lost();
    }
    return switch (model.status()) {
      case READY -> prepareReady(tx, claim, thread, model, now);
      case RUNNING -> recoverRunning(tx, claim, thread, model, now);
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> cleanupTerminal(tx, claim, thread, model, now);
    };
  }

  private Prepare prepareReady(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    ModelGroupExecution group = model.requestSpec().groupExecution();
    ModelGroupProgress progress = model.groupProgress();
    int memberIndex = 0;
    if (group != null) {
      if (progress == null || progress.groupVersion() != group.groupVersion()) {
        throw new IllegalStateException("group invocation is missing matching durable progress");
      }
      memberIndex = firstAvailableMember(tx, group, progress.memberIndex());
      if (memberIndex >= group.members().size()) {
        // 全是本地候选选择失败：不伪装成上游调用错误，也不进入普通重试预算。THREAD Work 先于 MODEL Work 加锁。
        tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
        if (tx.work().lockClaimed(claim).isEmpty()) {
          throw new ClaimLostSignal();
        }
        tx.updateModelInvocation(
            model.fail(
                new ModelInvocationError(
                    ProviderErrorKind.NO_AVAILABLE_MODEL,
                    "no available model in group: " + group.groupName()),
                now));
        tx.threads().update(thread.touchVersion(now));
        tx.work().complete(claim);
        return new Prepare.Terminated();
      }
    }
    Optional<Work> claimed = tx.work().lockClaimed(claim);
    if (claimed.isEmpty()) {
      return new Prepare.Lost();
    }
    // 运行前确保 lease 有完整 margin：剩余不足以撑到首次 heartbeat 时立即 renew。
    executions.ensureLeaseMargin(tx, claim);
    // ModelInvocation 必须挂在真实 TURN_START 上的不变量断言：压缩子身份不再从 TURN_START payload 推导（父压缩 turn
    // 不产生 ModelInvocation），但仍拒绝引用缺失 / 类型错误的 turn start。
    Entry turnStart =
        tx.entries()
            .find(model.turnStartEntryId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "model invocation "
                            + model.id()
                            + " references missing turn start "
                            + model.turnStartEntryId()));
    if (!(turnStart.payload() instanceof TurnStartPayload)) {
      throw new IllegalStateException(
          "model invocation " + model.id() + " turnStartEntryId does not reference a TURN_START");
    }
    // 摘要特有的完整性校验只属于「本线程当前直接持有 SETTLE_OWNER_TURN 票据」的执行；约束只读本行执行语义，绝不据此推断用途，
    // 否则普通子代理继承 allowAutoCompaction=false 时会被误当成摘要 Agent。票据查询只读本线程，不回溯父历史或祖先。
    boolean ownerSettledSummary =
        tx.joins().loadIncomplete(thread.id()).stream()
            .anyMatch(join -> join.delivery() == JoinDelivery.SETTLE_OWNER_TURN);
    if (group != null && memberIndex != progress.memberIndex()) {
      // 跳过冷却成员：新游标在运行前落库，执行身份与审计都来自该成员。
      progress =
          new ModelGroupProgress(progress.groupName(), progress.groupVersion(), memberIndex, 0);
      tx.updateModelInvocation(model.withGroupProgress(progress, now));
      tx.threads().update(thread.touchVersion(now));
    }
    return new Prepare.Dispatched(
        thread.id(),
        model.requestHeadEntryId(),
        model.attempt(),
        model.requestSpec(),
        progress,
        ownerSettledSummary);
  }

  /** 从 {@code fromIndex} 起第一个当前不在冷却的成员下标；全部冷却时返回成员数。 */
  private static int firstAvailableMember(
      BuiltinStore.Transaction tx, ModelGroupExecution group, int fromIndex) {
    int index = fromIndex;
    while (index < group.members().size()
        && tx.isGroupMemberCooling(
            group.groupName(),
            group.groupVersion(),
            group.members().get(index).model().modelName(),
            group.members().get(index).variant().id())) {
      index++;
    }
    return index;
  }

  /** 在 MODEL claim 仍 owned 期间加载不可变 basis EntryPath，并纯重建内存 ProviderRequest。 */
  private ProviderRequest materializeRequest(ClaimedWork claim, Prepare.Dispatched dispatched) {
    EntryPath path =
        inTransaction(
            tx -> {
              if (tx.work().lockClaimed(claim).isEmpty()) {
                throw new ClaimLostSignal();
              }
              return tx.entries().loadPath(dispatched.requestHeadEntryId());
            });
    return materializer.materialize(path, actualRequest(dispatched));
  }

  /** 组调用按 durable 成员游标派生本次真实执行身份；普通调用原样返回冻结 spec。 */
  private static ModelRequestSpec actualRequest(Prepare.Dispatched dispatched) {
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

  /**
   * 旧 lease 过期恢复：执行持有者已失效，但 RUNNING 已持久化，请求可能已经发出，不把它当成确定未执行。接入既有 {@link
   * fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy} 与持久 attempt/retryAt。
   *
   * <p>RUNNING 的 attempt 已被消耗。允许重试时记录一条 retryable failed attempt 后回到 READY，并按策略延迟重排 MODEL Work（不
   * request THREAD wake，旧 partial 只作为尝试审计，绝不与新 attempt 的文本或 tool-call 拼接）；预算耗尽时以 FAILED 明确终止并唤醒
   * THREAD。绝不重放 Provider，也不新建恢复专用预算或重置计数。
   */
  private Prepare recoverRunning(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    ModelGroupExecution group = model.requestSpec().groupExecution();
    ModelGroupProgress groupProgress = model.groupProgress();
    if (group != null
        && (groupProgress == null
            || groupProgress.groupVersion() != group.groupVersion()
            || groupProgress.memberIndex() >= group.members().size())) {
      throw new IllegalStateException("group invocation is missing matching durable progress");
    }
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT,
            "model work lease expired; provider outcome cannot be confirmed");
    // 组恢复只消耗当前成员的尝试预算，绝不因结果未知自动冻结或降级到下一成员。
    int budgetAttempt =
        group == null ? model.attempt() : Math.addExact(groupProgress.memberAttempts(), 1);
    InvocationRetryDecision decision =
        InvocationRetryDecision.decide(
            config.retryPolicyProvider().retryPolicy(), true, budgetAttempt);
    if (decision.retry()) {
      Instant retryAt = now.plus(decision.delay());
      ModelInvocation retried =
          model.retryReady(attemptFailure(model, model.attempt(), error, now, retryAt), now);
      if (group != null) {
        retried = retried.withGroupProgress(groupProgress.retryCurrent(), now);
      }
      tx.updateModelInvocation(retried);
      tx.threads().update(thread.touchVersion(now));
      if (tx.work().lockClaimed(claim).isEmpty()) {
        throw new ClaimLostSignal();
      }
      tx.work().reschedule(claim, decision.delay());
      return new Prepare.Retry();
    }
    // 预算耗尽：如实终止并唤醒 THREAD，不通过无限重试伪装持续推进。
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    // 组的恢复失败同样计入当前成员的失败审计；仍停留在原成员，且不冻结、不降级。
    tx.updateModelInvocation(
        group == null
            ? model.fail(error, now)
            : model.failAfterAttempt(
                error, attemptFailure(model, model.attempt(), error, now, now), now));
    tx.threads().update(thread.touchVersion(now));
    tx.work().complete(claim);
    return new Prepare.Terminated();
  }

  /** 恢复失败尝试的审计快照：旧 partial（RUNNING 的安全 checkpoint）只留作尝试记录，不参与新 attempt。 */
  private static ModelAttemptFailure attemptFailure(
      ModelInvocation model,
      int attempt,
      ModelInvocationError error,
      Instant failedAt,
      Instant retryAt) {
    StreamCheckpoint checkpoint = model.streamCheckpoint();
    return new ModelAttemptFailure(
        attempt,
        checkpoint == null ? 0L : checkpoint.sequence(),
        checkpoint == null ? "" : checkpoint.text(),
        checkpoint == null ? "" : checkpoint.thinking(),
        model.actualSelection(),
        model.requestedAt(),
        error,
        failedAt,
        retryAt);
  }

  /** terminal 行：resultEntryId 仍 null 时确保 THREAD Work 请求，然后 complete MODEL Work；不重复 bump version。 */
  private Prepare cleanupTerminal(
      BuiltinStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    boolean needsThreadWake = model.resultEntryId() == null;
    if (needsThreadWake) {
      // requestWork 同时 upsert 并锁定 THREAD Work；即使原行不存在，也能建立正确锁序。
      tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().complete(claim);
    return new Prepare.Terminated();
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

  /** 启动前明确拒绝：READY -&gt; FAILED（attempt 不变）+ version+1 + 请求 THREAD Work + complete MODEL Work。 */
  private boolean reject(
      ClaimedWork claim, Prepare.Dispatched dispatched, ModelInvocationError error) {
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

  private sealed interface Prepare
      permits Prepare.Lost, Prepare.Dispatched, Prepare.Terminated, Prepare.Retry {

    record Lost() implements Prepare {}

    /** 失联恢复已按策略回到 READY 并重排 MODEL Work：不启动 Provider，不 request THREAD wake。 */
    record Retry() implements Prepare {}

    /**
     * 已冻结的 dispatch：{@code groupProgress} 是模型组本次运行的 durable 成员游标（单模型为 null）；{@code
     * ownerSettledSummary} 来自本线程当前有效未完成票据的 SETTLE_OWNER_TURN 交付方式，不查询祖先。
     */
    record Dispatched(
        UUID threadId,
        UUID requestHeadEntryId,
        int attempt,
        ModelRequestSpec request,
        ModelGroupProgress groupProgress,
        boolean ownerSettledSummary)
        implements Prepare {}

    record Terminated() implements Prepare {}
  }
}
