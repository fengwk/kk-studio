package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.spi.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolGateway.ExecutionScope;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.spi.ToolResultHistoryMaterializer;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Target Tool processor：消费 dispatcher 已 claim 的 TOOL Work，驱动一次 Tool invocation 的完整生命周期。
 *
 * <p>只依赖单一 {@link HarnessStore} + {@link ToolGateway} + {@link RealtimeEventSink}，不自行全局 poll；不做任何
 * Entry / head / ToolResult Entry / Usage 写入。短事务只锁 own Thread -&gt; ToolInvocation -&gt; Work（同事务需要
 * THREAD Work 时先 request/upsert 并锁定 THREAD Work，再 lockClaimed TOOL Work）；不取执行树锁、Session
 * 锁或祖先锁；工具能力来自 TurnStart 冻结快照，不读取 Model。公共所有权与准入协议见包说明。
 *
 * <p>状态机：READY + approval null 在 ensure 完整 lease margin 后于事务外执行 {@link ToolGateway#preflight}
 * （preflight 期间由本地 heartbeat 维持 lease）；锁内解析实际 YOLO 开关（执行根读自身，子代理读执行根）为 true 时跳过 preflight 直接
 * Allow（不调用 evaluator / gateway），Allow 在短事务内持久 markApprovalNotRequired（Thread version +1）后进入执行；Ask
 * 转 WAITING_APPROVAL（version+1 + complete，不 request THREAD）；Deny 转 FAILED（version+1 + THREAD wake +
 * complete）；preflight 抛异常保持 READY / approval null / version 不变，按 {@code preflightFailureDelay}
 * reschedule。READY prepare 内读取执行上下文事实（会话 / 执行根 / 分支视图 / 冻结媒体能力）抛出异常时事务完整回滚，工具保持 READY 并按 {@code
 * admitFailureDelay} 有界重排。READY + completed approval 直接进入执行。WAITING_APPROVAL 只 complete TOOL
 * Work；RUNNING 过期租约恢复为 UNKNOWN（attempt 保留）+ version+1 + THREAD wake + complete，绝不重放 Tool；terminal
 * 行只确保 THREAD Work 后 complete，不重复 bump version。
 *
 * <p>执行：{@link ToolGateway#admit} 做纯本地 admission（Busy 按 retryAfter reschedule、Rejected 转
 * FAILED，两者都肯定无副作用，attempt 不变）， Admitted 后在任何副作用之前持久化 {@code READY -> RUNNING}，再由 {@link
 * ToolExecution} 在独占虚拟线程上阻塞执行至终态， 终态与资源释放都发生在该线程上。heartbeat 只 renew 当前 Work lease。
 */
@Slf4j
public final class ToolProcessor implements AutoCloseable {

  private final HarnessStore store;
  private final LocalExecutionLifecycle lifecycle;
  private final ExecutionExitConfirmer exitConfirmer;
  private final ToolGateway gateway;
  private final RealtimeEventSink realtimeEventSink;
  private final ToolProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor heartbeatWorker;
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;
  private final ClaimedExecutionSupport<ToolExecution> executions;
  private final ThreadLocal<ProcessorDelaySnapshot> currentDelays = new ThreadLocal<>();
  private final ToolPrepare toolPrepare;

  private record ProcessorDelaySnapshot(
      ProcessorLeaseConfig leaseConfig, Duration preflightFailure, Duration admitFailure) {}

  public ToolProcessor(
      HarnessStore store,
      ToolGateway gateway,
      RealtimeEventSink realtimeEventSink,
      ToolProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      LocalExecutionLifecycle lifecycle,
      ExecutionExitConfirmer exitConfirmer,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.exitConfirmer = Objects.requireNonNull(exitConfirmer, "exitConfirmer");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.heartbeatWorker = Objects.requireNonNull(heartbeatWorker, "heartbeatWorker");
    this.toolResultHistoryMaterializer =
        Objects.requireNonNull(toolResultHistoryMaterializer, "toolResultHistoryMaterializer");
    this.executions = new ClaimedExecutionSupport<>(store, WorkTargetType.TOOL);
    this.toolPrepare = new ToolPrepare(store, executions);
  }

  /**
   * 处理一次 dispatcher 已 claim 的 TOOL Work。
   *
   * <p>经 {@link ClaimAdmissionGuard} 准入后，prepare 短事务复核状态与所有权，按当前状态处理人工输入、进入 preflight
   * 或直接执行；恢复与终态分支只收敛持久事实，不重放工具。
   *
   * @return 启动为 STARTED，重排为 RESCHEDULED，已收敛为 TERMINATED，失权或重复投递为 LOST_OWNERSHIP
   */
  public ProcessResult process(ClaimedWork claim) {
    ProcessorDelaySnapshot previous = currentDelays.get();
    currentDelays.set(
        new ProcessorDelaySnapshot(
            config.leaseConfigProvider().get(),
            config.preflightFailureDelayProvider().get(),
            config.admitFailureDelayProvider().get()));
    try {
      return processClaimedWork(claim);
    } finally {
      if (previous == null) {
        currentDelays.remove();
      } else {
        currentDelays.set(previous);
      }
    }
  }

  private Duration preflightFailureDelay() {
    ProcessorDelaySnapshot snapshot = currentDelays.get();
    return snapshot == null
        ? config.preflightFailureDelayProvider().get()
        : snapshot.preflightFailure();
  }

  private Duration admitFailureDelay() {
    ProcessorDelaySnapshot snapshot = currentDelays.get();
    return snapshot == null ? config.admitFailureDelayProvider().get() : snapshot.admitFailure();
  }

  private ProcessResult processClaimedWork(ClaimedWork claim) {
    ClaimAdmissionGuard.Ticket<ToolExecution> ticket = executions.admit(claim);
    if (ticket == null) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    try (ticket) {
      ToolPrepare.Step prepare;
      try {
        prepare =
            store.transaction(
                tx -> toolPrepare.prepare(tx, claim, tx.now(), currentDelays.get().leaseConfig()));
      } catch (ToolPrepare.ExecutionScopeReadFailure failure) {
        // 事务已完整回滚：工具未执行、也没有任何外部副作用，按 admission 失败路径有界重排。
        return rescheduleScopeReadFailure(claim, failure);
      }
      return switch (prepare) {
        case ToolPrepare.Step.Lost ignored -> ProcessResult.LOST_OWNERSHIP;
        case ToolPrepare.Step.Terminated ignored -> ProcessResult.TERMINATED;
        case ToolPrepare.Step.Input input -> requestInput(claim, input.threadId());
        case ToolPrepare.Step.Preflight preflight -> preflight(claim, preflight, ticket);
        case ToolPrepare.Step.Allowed allowed -> applyPreflightAllow(
            claim, allowed.scope(), allowed.attempt(), allowed.request(), null, ticket);
        case ToolPrepare.Step.Dispatched dispatched -> launch(claim, dispatched, ticket);
        case ToolPrepare.Step.Reattach reattach -> launchReattach(claim, reattach, ticket);
      };
    } catch (ClaimLostSignal ignored) {
      return ProcessResult.LOST_OWNERSHIP;
    }
  }

  /**
   * 取消一个进程内本地 execution（供 Stop durable commit 后 best-effort 调用）；结果不反写任何 durable 状态。
   *
   * @return 是否存在并已取消对应本地 execution
   */
  public boolean cancel(UUID invocationId) {
    return executions.cancel(invocationId);
  }

  /** 取消全部本地 execution 并停止各自 heartbeat；不 shutdown 调用方注入的 scheduler；幂等。 */
  @Override
  public void close() {
    executions.close();
  }

  /** 在调用方给定的总停机预算内移交活跃执行：先快照仍在等待远端结果的执行并逐个移交（共用单调 deadline），然后调用 close 处理其余执行。 */
  public void drain(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("drain timeout must not be negative");
    }
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    List<ToolExecution> awaiting =
        executions.activeExecutions().stream().filter(ToolExecution::isAwaitingRemote).toList();
    for (ToolExecution execution : awaiting) {
      try {
        execution.handover(remainingUntil(deadlineNanos));
      } catch (RuntimeException failure) {
        log.warn(
            "Failed to handover awaiting remote tool execution for invocation {}; its claim remains until lease expiry",
            execution.invocationId(),
            failure);
      }
    }
    close();
  }

  /** 只使用单调时钟计算调用方剩余预算；已到期返回零，避免扩大上层 deadline。 */
  private static Duration remainingUntil(long deadlineNanos) {
    long remainingNanos = deadlineNanos - System.nanoTime();
    return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
  }

  /** 当前 JVM 是否持有任何活跃的本地 Tool execution。 */
  public boolean hasActiveExecution() {
    return executions.hasActiveExecution();
  }

  /** 内部人工输入工具的冻结结果：READY -&gt; WAITING_INPUT，或契约破坏时确定性失败。 */
  private ProcessResult requestInput(ClaimedWork claim, UUID threadId) {
    return toolPrepare.freezeInput(claim, threadId)
        ? ProcessResult.TERMINATED
        : ProcessResult.LOST_OWNERSHIP;
  }

  /**
   * 事务外 preflight：先建本地 execution 并启动 heartbeat（preflight 期间维持 lease，且 close / cancel 可中止），再调用
   * {@link ToolGateway#preflight}。preflight 抛异常 / 返回 null（确定无副作用）时保持 READY / approval null /
   * version 不变，按配置延迟 reschedule；所有结果提交前二次校验 claim + READY + attempt + approval null，lost 完整 no-op。
   */
  private ProcessResult preflight(
      ClaimedWork claim,
      ToolPrepare.Step.Preflight preflight,
      ClaimAdmissionGuard.Ticket<ToolExecution> ticket) {
    UUID invocationId = claim.target().id();
    ToolExecution execution =
        newExecution(
            claim,
            preflight.scope().threadId(),
            Math.addExact(preflight.attempt(), 1),
            preflight.request());
    if (!ticket.attach(execution)) {
      execution.abandon();
      return executions.isClosed()
          ? bouncePreflight(claim, preflight)
          : ProcessResult.LOST_OWNERSHIP;
    }
    boolean started = false;
    try {
      if (executions.isClosed()
          || !execution.startHeartbeat()
          || execution.abandoned()
          || ticket.isSuperseded()) {
        return bouncePreflight(claim, preflight);
      }
      ToolGateway.PreflightResult result;
      try {
        result = gateway.preflight(preflight.request(), preflight.scope());
      } catch (RuntimeException failure) {
        // 契约：抛异常表示 preflight 确定无副作用（listener / Gateway start 尚未发生）。
        log.warn(
            "tool preflight failed for invocation {}: {}",
            invocationId,
            ProcessorExceptions.describe(failure));
        return bouncePreflight(claim, preflight);
      }
      if (execution.abandoned() || ticket.isSuperseded()) {
        // close / cancel / heartbeat loss 在 preflight 期间获胜：claim 仍 owned时立即重排，否则 LOST。
        return bouncePreflight(claim, preflight);
      }
      if (result == null) {
        // 契约违反：preflight 结果未知但无副作用，按 preflightFailureDelay 重排。
        log.warn("tool preflight returned null for invocation {}", invocationId);
        return bouncePreflight(claim, preflight);
      }
      ProcessResult outcome =
          switch (result) {
            case ToolGateway.Allow ignored -> applyPreflightAllow(
                claim,
                preflight.scope(),
                preflight.attempt(),
                preflight.request(),
                execution,
                ticket);
            case ToolGateway.Ask ask -> applyPreflightAsk(claim, preflight, ask);
            case ToolGateway.Deny deny -> applyPreflightDeny(claim, preflight, deny.error());
          };
      if (outcome == ProcessResult.STARTED) {
        started = true;
      }
      return outcome;
    } finally {
      if (!started) {
        execution.abandon();
      }
    }
  }

  private ProcessResult bouncePreflight(ClaimedWork claim, ToolPrepare.Step.Preflight preflight) {
    return reschedule(
            claim, preflight.scope().threadId(), preflight.attempt(), preflightFailureDelay())
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  /**
   * Allow（含 YOLO 直接 Allow）：在短事务内把 READY / null approval 持久为 not-required approval（Thread version
   * +1）；然后进入 执行。二次校验失败（lost / 状态被并发改写）完整 no-op。
   *
   * <p>{@code execution} 为 null 表示 YOLO 直接 Allow 路径（未创建事务外 preflight execution）：执行段在 {@link
   * #launch} 中新建。
   */
  private ProcessResult applyPreflightAllow(
      ClaimedWork claim,
      ExecutionScope scope,
      int attempt,
      ToolInvocationRequest request,
      ToolExecution execution,
      ClaimAdmissionGuard.Ticket<ToolExecution> ticket) {
    boolean allowed =
        Boolean.TRUE.equals(
            store.transaction(tx -> allowIntent(tx, claim, scope.threadId(), attempt, tx.now())));
    if (!allowed) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    ToolPrepare.Step.Dispatched dispatched =
        new ToolPrepare.Step.Dispatched(scope, attempt, request, ToolApproval.notRequired());
    if (execution != null) {
      if (executions.isClosed() || execution.abandoned() || ticket.isSuperseded()) {
        // Allow 意图提交后 close / cancel 获胜：不 admit，保持 READY 并重排。
        return rescheduleResult(claim, dispatched, admitFailureDelay());
      }
      return admitAndRun(claim, dispatched, execution, ticket);
    }
    return launch(claim, dispatched, ticket);
  }

  /**
   * Allow 的持久意图：按 own Thread -&gt; Tool 锁序锁定后二次校验 claim + READY + attempt + approval null，再
   * markApprovalNotRequired。
   */
  private boolean allowIntent(
      HarnessStore.Transaction tx, ClaimedWork claim, UUID threadId, int attempt, Instant now) {
    ThreadState thread = tx.threads().lock(threadId).orElse(null);
    if (thread == null) {
      return false;
    }
    ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
    if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
      return false;
    }
    if (tool.status() != ToolInvocationStatus.READY
        || tool.attempt() != attempt
        || tool.approval() != null) {
      return false;
    }
    tx.tools().update(List.of(tool.markApprovalNotRequired(now)));
    tx.threads().update(thread.touchVersion(now));
    return true;
  }

  /**
   * Ask：READY -&gt; WAITING_APPROVAL(request reason)，version+1，complete TOOL Work；不 request THREAD。
   */
  private ProcessResult applyPreflightAsk(
      ClaimedWork claim, ToolPrepare.Step.Preflight preflight, ToolGateway.Ask ask) {
    boolean committed =
        Boolean.TRUE.equals(
            store.transaction(
                tx -> {
                  Instant now = tx.now();
                  ThreadState thread = tx.threads().lock(preflight.scope().threadId()).orElse(null);
                  if (thread == null) {
                    return false;
                  }
                  ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
                  if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
                    return false;
                  }
                  if (tool.status() != ToolInvocationStatus.READY
                      || tool.attempt() != preflight.attempt()
                      || tool.approval() != null) {
                    return false;
                  }
                  tx.tools()
                      .update(List.of(tool.requestApproval(ask.reason(), ask.editReview(), now)));
                  tx.threads().update(thread.touchVersion(now));
                  tx.work().complete(claim);
                  return true;
                }));
    return committed ? ProcessResult.TERMINATED : ProcessResult.LOST_OWNERSHIP;
  }

  /** Deny：READY -&gt; FAILED(error)，version+1，request THREAD Work，complete。 */
  private ProcessResult applyPreflightDeny(
      ClaimedWork claim, ToolPrepare.Step.Preflight preflight, ToolInvocationError error) {
    boolean committed = failReady(claim, preflight.scope().threadId(), preflight.attempt(), error);
    return committed ? ProcessResult.TERMINATED : ProcessResult.LOST_OWNERSHIP;
  }

  /**
   * 肯定未执行的确定性失败（preflight Deny / admission Rejected）：二次校验 claim + READY + attempt 后 READY -&gt;
   * FAILED + version+1 + 请求 THREAD Work + complete TOOL Work（attempt 不变）。
   */
  private boolean failReady(
      ClaimedWork claim, UUID threadId, int attempt, ToolInvocationError error) {
    try {
      return Boolean.TRUE.equals(
          store.transaction(
              tx -> {
                Instant now = tx.now();
                ThreadState thread = tx.threads().lock(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt) {
                  return false;
                }
                tx.work().request(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.work().lockClaimed(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.tools().update(List.of(tool.fail(error, now)));
                tx.threads().update(thread.touchVersion(now));
                tx.work().complete(claim);
                return true;
              }));
    } catch (ClaimLostSignal ignored) {
      return false;
    }
  }

  /**
   * 肯定未执行且无需改状态（preflight 失败 / admission Busy 或失败 / close）：二次校验 claim + READY + attempt 后仅按延迟
   * reschedule TOOL Work，无 version。
   */
  private boolean reschedule(ClaimedWork claim, UUID threadId, int attempt, Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
              if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt) {
                return false;
              }
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  /**
   * 执行上下文事实读取失败（例如分支 / TURN_START 查询异常）：prepare 事务已完整回滚，工具未执行、也没有任何外部副作用。按 {@code
   * admitFailureDelay} 有界重排；锁后重新校验 claim + Thread + READY + attempt，任一条件不满足即完整 no-op（绝不写入、绝不改
   * attempt）。
   *
   * <p>只处理 scope 读取的失败：其它 prepare 错误保持原样抛出，绝不被这里捕获或重排。
   */
  private ProcessResult rescheduleScopeReadFailure(
      ClaimedWork claim, ToolPrepare.ExecutionScopeReadFailure failure) {
    log.warn(
        "tool execution scope read failed for invocation {}: {}",
        claim.target().id(),
        ProcessorExceptions.describe(failure.getCause()));
    return reschedule(claim, failure.threadId(), failure.attempt(), admitFailureDelay())
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  /**
   * 执行段：新建 execution 并启动 heartbeat，随后准入并执行。任何未达 STARTED 的异常与放弃路径统一在 finally 中 abandon 本地
   * execution。Admitted 之后持久化 RUNNING 并启动执行线程，由 {@link ToolExecution} 接管 run 的关闭。
   */
  private ProcessResult launch(
      ClaimedWork claim,
      ToolPrepare.Step.Dispatched dispatched,
      ClaimAdmissionGuard.Ticket<ToolExecution> ticket) {
    ToolExecution execution =
        newExecution(
            claim,
            dispatched.scope().threadId(),
            Math.addExact(dispatched.attempt(), 1),
            dispatched.request());
    if (!ticket.attach(execution)) {
      execution.abandon();
      return executions.isClosed()
          ? rescheduleResult(claim, dispatched, admitFailureDelay())
          : ProcessResult.LOST_OWNERSHIP;
    }
    boolean started = false;
    try {
      if (executions.isClosed()
          || !execution.startHeartbeat()
          || execution.abandoned()
          || ticket.isSuperseded()) {
        return rescheduleResult(claim, dispatched, admitFailureDelay());
      }
      ProcessResult outcome = admitAndRun(claim, dispatched, execution, ticket);
      if (outcome == ProcessResult.STARTED) {
        started = true;
      }
      return outcome;
    } finally {
      if (!started) {
        execution.abandon();
      }
    }
  }

  private ProcessResult admitAndRun(
      ClaimedWork claim,
      ToolPrepare.Step.Dispatched dispatched,
      ToolExecution execution,
      ClaimAdmissionGuard.Ticket<ToolExecution> ticket) {
    UUID invocationId = claim.target().id();
    ToolGateway.Admission admission;
    try {
      admission =
          gateway.admit(
              new ToolGateway.Execution(
                  invocationId,
                  dispatched.scope(),
                  Math.addExact(dispatched.attempt(), 1),
                  dispatched.request(),
                  dispatched.approval(),
                  execution.remoteExecutions()));
    } catch (RuntimeException failure) {
      // 契约：抛异常表示 admission 没有任何副作用，保持 READY 并 reschedule（attempt 不变）。
      log.warn(
          "tool admission failed for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return rescheduleResult(claim, dispatched, admitFailureDelay());
    }
    if (admission == null) {
      // 契约违反：admission 结果未知但无副作用，按 admitFailureDelay 重排。
      log.warn("tool admission returned null for invocation {}", invocationId);
      return rescheduleResult(claim, dispatched, admitFailureDelay());
    }
    return switch (admission) {
      case ToolGateway.Admitted admitted -> {
        if (execution.abandoned() || ticket.isSuperseded()) {
          // close / cancel 在 admission 期间获胜：工具尚未执行，释放已持有的资源并 reschedule，不持久化 RUNNING。
          try {
            admitted.run().close();
          } catch (RuntimeException failure) {
            log.warn("cannot release tool run resources for {}", invocationId, failure);
          }
          yield rescheduleResult(claim, dispatched, admitFailureDelay());
        }
        yield execution.start(admitted.run());
      }
      case ToolGateway.Busy busy -> rescheduleResult(claim, dispatched, busy.retryAfter());
      case ToolGateway.Rejected rejected -> failReady(
              claim, dispatched.scope().threadId(), dispatched.attempt(), rejected.error())
          ? ProcessResult.TERMINATED
          : ProcessResult.LOST_OWNERSHIP;
    };
  }

  private ProcessResult rescheduleResult(
      ClaimedWork claim, ToolPrepare.Step.Dispatched dispatched, Duration delay) {
    return reschedule(claim, dispatched.scope().threadId(), dispatched.attempt(), delay)
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  private ProcessResult launchReattach(
      ClaimedWork claim,
      ToolPrepare.Step.Reattach reattach,
      ClaimAdmissionGuard.Ticket<ToolExecution> ticket) {
    ToolExecution execution =
        newExecution(claim, reattach.threadId(), reattach.attempt(), reattach.request());
    if (!ticket.attach(execution)) {
      execution.abandon();
      return executions.isClosed()
          ? rescheduleReattachResult(claim, reattach, admitFailureDelay())
          : ProcessResult.LOST_OWNERSHIP;
    }
    boolean started = false;
    try {
      if (executions.isClosed()
          || !execution.startHeartbeat()
          || execution.abandoned()
          || ticket.isSuperseded()) {
        return rescheduleReattachResult(claim, reattach, admitFailureDelay());
      }
      ProcessResult outcome = reattachAndRun(claim, reattach, execution, ticket);
      if (outcome == ProcessResult.STARTED) {
        started = true;
      }
      return outcome;
    } finally {
      if (!started) {
        execution.abandon();
      }
    }
  }

  private ProcessResult reattachAndRun(
      ClaimedWork claim,
      ToolPrepare.Step.Reattach reattach,
      ToolExecution execution,
      ClaimAdmissionGuard.Ticket<ToolExecution> ticket) {
    UUID invocationId = claim.target().id();
    ToolGateway.Run run;
    try {
      run =
          gateway.reattach(
              new ToolGateway.ReattachExecution(
                  invocationId,
                  reattach.threadId(),
                  reattach.request(),
                  reattach.request().binding().environmentId(),
                  reattach.remoteFact().executionId(),
                  config.reattachWindow(),
                  execution.remoteExecutions()));
    } catch (EnvironmentCapabilityUnavailableException unavailable) {
      log.warn(
          "tool reattach unavailable for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(unavailable));
      return rescheduleReattachResult(claim, reattach, admitFailureDelay());
    } catch (RuntimeException failure) {
      log.warn(
          "tool reattach failed for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      return rescheduleReattachResult(claim, reattach, admitFailureDelay());
    }
    if (execution.abandoned() || ticket.isSuperseded()) {
      try {
        run.close();
      } catch (RuntimeException failure) {
        log.warn("cannot release tool run resources for {}", invocationId, failure);
      }
      return rescheduleReattachResult(claim, reattach, admitFailureDelay());
    }
    return execution.startReattach(run, reattach.remoteFact());
  }

  private boolean rescheduleReattach(
      ClaimedWork claim, UUID threadId, int attempt, Duration delay) {
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.threads().lock(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
              if (tool == null || tx.work().lockClaimed(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.RUNNING || tool.attempt() != attempt) {
                return false;
              }
              tx.work().reschedule(claim, delay);
              return true;
            }));
  }

  private ProcessResult rescheduleReattachResult(
      ClaimedWork claim, ToolPrepare.Step.Reattach reattach, Duration delay) {
    return rescheduleReattach(claim, reattach.threadId(), reattach.attempt(), delay)
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  private ToolProcessorConfig executionConfig() {
    ProcessorDelaySnapshot snapshot =
        Objects.requireNonNull(currentDelays.get(), "processor settings snapshot");
    return new ToolProcessorConfig(
        () -> snapshot.leaseConfig(),
        config.retryPolicyProvider(),
        config.preflightFailureDelayProvider(),
        config.admitFailureDelayProvider(),
        config.reattachWindow());
  }

  private ToolExecution newExecution(
      ClaimedWork claim, UUID threadId, int attempt, ToolInvocationRequest request) {
    return new ToolExecution(
        store,
        realtimeEventSink,
        claim,
        threadId,
        attempt,
        request,
        executionConfig(),
        clock,
        scheduler,
        heartbeatWorker,
        executions::release,
        lifecycle,
        exitConfirmer,
        toolResultHistoryMaterializer);
  }
}
