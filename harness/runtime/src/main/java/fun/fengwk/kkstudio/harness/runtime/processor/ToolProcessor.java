package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputTool;
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
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
      Prepare prepare;
      try {
        prepare =
            store.transaction(
                tx -> prepareLocked(tx, claim, tx.now(), currentDelays.get().leaseConfig()));
      } catch (ExecutionScopeReadFailure failure) {
        // 事务已完整回滚：工具未执行、也没有任何外部副作用，按 admission 失败路径有界重排。
        return rescheduleScopeReadFailure(claim, failure);
      }
      return switch (prepare) {
        case Prepare.Lost ignored -> ProcessResult.LOST_OWNERSHIP;
        case Prepare.Terminated ignored -> ProcessResult.TERMINATED;
        case Prepare.Input input -> requestInput(claim, input.threadId());
        case Prepare.Preflight preflight -> preflight(claim, preflight, ticket);
        case Prepare.Allowed allowed -> applyPreflightAllow(
            claim, allowed.scope(), allowed.attempt(), allowed.request(), null, ticket);
        case Prepare.Dispatched dispatched -> launch(claim, dispatched, ticket);
        case Prepare.Reattach reattach -> launchReattach(claim, reattach, ticket);
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

  /** 按锁序 Thread -&gt; Tool -&gt; Work 读取并分支当前 durable 状态；不重放、不重复 bump version。 */
  private Prepare prepareLocked(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      Instant now,
      ProcessorLeaseConfig leaseConfig) {
    UUID invocationId = claim.target().id();
    ToolInvocation peek = tx.tools().find(invocationId).orElse(null);
    if (peek == null) {
      return new Prepare.Lost();
    }
    ThreadState thread = tx.threads().lock(peek.threadId()).orElse(null);
    if (thread == null) {
      return new Prepare.Lost();
    }
    ToolInvocation tool = tx.tools().lock(invocationId).orElse(null);
    if (tool == null) {
      return new Prepare.Lost();
    }
    // 公共工具归属与能力已冻结在 TurnStart；执行路径不读取/锁定 Driver 私有行。
    if (!tool.threadId().equals(thread.id())) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its thread during preparation");
    }
    return switch (tool.status()) {
      case READY -> prepareReady(tx, claim, thread, tool, leaseConfig);
      case WAITING_APPROVAL, WAITING_INPUT -> waitingForHuman(tx, claim, now);
      case RUNNING -> prepareRunning(tx, claim, thread, tool, now, leaseConfig);
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> cleanupTerminal(tx, claim, thread, tool, now);
    };
  }

  /**
   * READY：内部人工输入工具在锁内直接冻结为 WAITING_INPUT；其它调用 approval null 走 preflight（ensure 完整 lease margin
   * 后事务外执行，期间 heartbeat 维持 lease）；completed approval 直接进入执行。
   *
   * <p>YOLO 决策在锁内完成：只锁本 Thread 后解析的实际开关为 true 时直接返回 {@link Prepare.Allowed}（一次 preflight
   * 只做一次控制决定，不调用 gateway / evaluator，后续切换不追溯已完成的派发）；false 才走普通 gateway preflight。 人输入判定先于 YOLO 与
   * preflight：YOLO 不代替用户作答，问卷也不经过工具权限审批。
   */
  private Prepare prepareReady(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      ProcessorLeaseConfig leaseConfig) {
    Optional<Work> claimed = tx.work().lockClaimed(claim);
    if (claimed.isEmpty()) {
      return new Prepare.Lost();
    }
    if (HumanInputTool.isHumanInputTool(tool.binding())) {
      return new Prepare.Input(thread.id());
    }
    // admission 前确保 lease 有完整 margin：剩余不足以撑到首次 heartbeat 时立即 renew。
    executions.ensureLeaseMargin(tx, claim, leaseConfig);
    // READY 边界临时构造 transient executable request（不持久化）。
    ToolInvocationRequest request = new ToolInvocationRequest(tool.call(), tool.binding());
    // 执行上下文事实只在锁内读取一次：Thread/会话/执行根取自已锁定行，assistant 分支与媒体能力取该调用的冻结依据。
    ExecutionScope scope = executionScope(tx, thread, tool);
    if (tool.approval() == null) {
      if (effectiveYoloEnabled(tx, thread)) {
        // YOLO=true：锁内快照直接 Allow，绝不调用 permission evaluator / ToolGateway.preflight。
        return new Prepare.Allowed(scope, tool.attempt(), request);
      }
      return new Prepare.Preflight(scope, tool.attempt(), request);
    }
    return new Prepare.Dispatched(scope, tool.attempt(), request, tool.approval());
  }

  /**
   * 冻结本次执行的上下文事实：Thread 自身会话、不可变执行根与其会话、该调用的 assistant 分支入口、该 Contributor 在该 assistant
   * 分支上的只读视图，以及该调用 TURN_START 已冻结的消费者媒体能力。
   *
   * <p>事实全部来自当前已锁定 Thread 与该调用 assistant path 的同一事务读取，因此 admission 与工具执行无需查询 Harness 存储。执行根会话
   * 从真实执行根 Thread 行读取，缺失时显式失败，绝不按当前 Thread 合成。
   *
   * <p>任一读取抛出 {@link RuntimeException} 时包装为 {@link ExecutionScopeReadFailure} 重抛，使 prepare
   * 事务完整回滚，再由 {@link #process} 按 admission 失败路径有界重排；事实本身绝不回落默认值。
   */
  private static ExecutionScope executionScope(
      HarnessStore.Transaction tx, ThreadState thread, ToolInvocation tool) {
    try {
      return readExecutionScope(tx, thread, tool);
    } catch (RuntimeException failure) {
      throw new ExecutionScopeReadFailure(thread.id(), tool.attempt(), failure);
    }
  }

  /** 上下文事实的实际读取；读取失败由 {@link #executionScope} 统一包装为回滚信号。 */
  private static ExecutionScope readExecutionScope(
      HarnessStore.Transaction tx, ThreadState thread, ToolInvocation tool) {
    UUID rootThreadId = thread.rootThreadId();
    UUID rootSessionId =
        rootThreadId.equals(thread.id())
            ? thread.sessionId()
            : tx.threads()
                .find(rootThreadId)
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "thread " + thread.id() + " lost its execution root " + rootThreadId))
                .sessionId();
    return new ExecutionScope(
        thread.id(),
        thread.sessionId(),
        rootThreadId,
        rootSessionId,
        tool.assistantEntryId(),
        frozenMediaCapabilities(tx, thread, tool));
  }

  /** 消费者媒体能力的唯一来源是该调用 TURN_START 冻结的能力快照；绝不读取 live 模型或 Driver 私有状态。 */
  private static TurnMediaCapabilities frozenMediaCapabilities(
      HarnessStore.Transaction tx, ThreadState thread, ToolInvocation tool) {
    Entry turnStart =
        tx.entries()
            .find(tool.turnStartEntryId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool invocation "
                            + tool.id()
                            + " lost its turn start entry "
                            + tool.turnStartEntryId()));
    if (!(turnStart.payload() instanceof TurnStartPayload start)
        || !start.ownerThreadId().equals(thread.id())
        || start.capabilities() == null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " has no frozen turn capabilities for this thread");
    }
    return start.capabilities().mediaCapabilities();
  }

  /**
   * WAITING_APPROVAL / WAITING_INPUT 不应执行：仅在 ownership 有效时 complete TOOL Work，不 bump version、不
   * request THREAD。审批与问卷由用户侧显式决策/作答唤醒。
   */
  private Prepare waitingForHuman(HarnessStore.Transaction tx, ClaimedWork claim, Instant now) {
    if (tx.work().lockClaimed(claim).isEmpty()) {
      return new Prepare.Lost();
    }
    tx.work().complete(claim);
    return new Prepare.Terminated();
  }

  /**
   * RUNNING 判定在途远端执行： 1) 工具有冻结 binding 且带 environmentId； 2) loadRemote(invocationId, attempt)
   * 恰好返回一条事实； 3) 该事实状态为 UNCONFIRMED 或 REMOTE_ACKNOWLEDGED。 满足条件则进入重新挂接（reattach）流程；否则沿用
   * recoverUnknown 收敛为 UNKNOWN。
   */
  private Prepare prepareRunning(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now,
      ProcessorLeaseConfig leaseConfig) {
    if (tool.binding().environmentId() != null) {
      List<ExecutionExitFact> remotes = tx.exits().loadRemote(tool.id(), tool.attempt());
      if (remotes.size() == 1) {
        ExecutionExitFact remoteFact = remotes.getFirst();
        if (remoteFact.state() == ExecutionExitState.UNCONFIRMED
            || remoteFact.state() == ExecutionExitState.REMOTE_ACKNOWLEDGED) {
          Optional<Work> claimed = tx.work().lockClaimed(claim);
          if (claimed.isEmpty()) {
            return new Prepare.Lost();
          }
          executions.ensureLeaseMargin(tx, claim, leaseConfig);
          ToolInvocationRequest request = new ToolInvocationRequest(tool.call(), tool.binding());
          // 挂接只连接既有远端执行，不复用 admission 资源、也不使用执行上下文事实：这里不读取分支 / TURN_START / 执行根。
          return new Prepare.Reattach(thread.id(), tool.attempt(), request, remoteFact);
        }
      }
    }
    return recoverUnknown(tx, claim, thread, tool, now);
  }

  /** 过期租约恢复：执行持有者已失效，绝不重放 Tool。RUNNING 表示可能已产生副作用；如实给出中断原因与最后已知状态，并明确外部效果未知， 交给下一轮模型自行决定查询或重试。 */
  private Prepare recoverUnknown(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now) {
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    ToolInvocationError error = leaseLostError(tool.status());
    tx.tools().update(List.of(tool.unknown(error, now)));
    tx.threads().update(thread.touchVersion(now));
    tx.work().complete(claim);
    return new Prepare.Terminated();
  }

  /** 失联恢复的如实反馈：只报告可证实的原因与最后已知状态，并明确外部效果未知；不把未知效果写成“确定未执行”。 */
  private static ToolInvocationError leaseLostError(ToolInvocationStatus lastKnownStatus) {
    return new ToolInvocationError(
        "LEASE_EXPIRED",
        "tool invocation was interrupted because its execution holder was lost; no persisted final"
            + " result was obtained. Last known status: "
            + lastKnownStatus
            + ". Whether the external operation took effect, or is still running, cannot be"
            + " confirmed by this result.");
  }

  /**
   * 内部人工输入：READY -&gt; WAITING_INPUT，version+1，complete TOOL Work，不 request THREAD、不进入 preflight /
   * gateway。
   *
   * <p>问卷必须在接受时就能解析：解析失败说明 {@code ask_user} 契约被破坏（工具实现与运行时契约漂移），此时以确定性失败收敛并唤醒
   * Thread，让模型看到无效调用，而不是冻结一个无法作答的等待。提交前按 own Thread 锁序锁定后二次校验 claim + READY + attempt 0 + approval
   * null，lost 完整 no-op。错误信息只报告原因，不回显问卷原文。
   */
  private ProcessResult requestInput(ClaimedWork claim, UUID threadId) {
    boolean committed =
        Boolean.TRUE.equals(
            store.transaction(
                tx -> {
                  Instant now = tx.now();
                  ThreadState thread = tx.threads().lock(threadId).orElse(null);
                  if (thread == null) {
                    return false;
                  }
                  ToolInvocation tool = tx.tools().lock(claim.target().id()).orElse(null);
                  if (tool == null || !isFrozenReadyInput(tool)) {
                    return false;
                  }
                  // 破坏契约的问卷以失败收敛并唤醒 Thread；Work 锁必须按 (type, id) 升序获取，因此先 request THREAD。
                  boolean answerable = isFrozenQuestionnaire(tool);
                  if (!answerable) {
                    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
                  }
                  if (tx.work().lockClaimed(claim).isEmpty()) {
                    throw new ClaimLostSignal();
                  }
                  tx.tools()
                      .update(
                          List.of(
                              answerable
                                  ? tool.requestInput(now)
                                  : tool.fail(
                                      new ToolInvocationError(
                                          INVALID_QUESTIONNAIRE_KIND,
                                          "ask_user invocation does not carry a valid questionnaire"),
                                      now)));
                  tx.threads().update(thread.touchVersion(now));
                  tx.work().complete(claim);
                  return true;
                }));
    return committed ? ProcessResult.TERMINATED : ProcessResult.LOST_OWNERSHIP;
  }

  /** 只有尚未审批、尚未执行（READY + attempt 0 + approval null）的调用才能被冻结为等待或确定性失败。 */
  private static boolean isFrozenReadyInput(ToolInvocation tool) {
    return tool.status() == ToolInvocationStatus.READY
        && tool.attempt() == 0
        && tool.approval() == null;
  }

  /** 冻结问卷是否可解析；失败只说明契约被破坏，绝不写回问题原文。 */
  private static boolean isFrozenQuestionnaire(ToolInvocation tool) {
    try {
      INPUT_JSON.decodeQuestionnaire(tool.call().argumentsJson());
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  /**
   * terminal 行：始终确保 THREAD Work 请求（outcome 尚未物化才可能有 terminal 行），然后 complete TOOL Work；不重复 bump
   * version。
   */
  private Prepare cleanupTerminal(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now) {
    // requestWork 同时 upsert 并锁定 THREAD Work；即使原行不存在，也能建立正确锁序。
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.work().lockClaimed(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.work().complete(claim);
    return new Prepare.Terminated();
  }

  /**
   * 事务外 preflight：先建本地 execution 并启动 heartbeat（preflight 期间维持 lease，且 close / cancel 可中止），再调用
   * {@link ToolGateway#preflight}。preflight 抛异常 / 返回 null（确定无副作用）时保持 READY / approval null /
   * version 不变，按配置延迟 reschedule；所有结果提交前二次校验 claim + READY + attempt + approval null，lost 完整 no-op。
   */
  private ProcessResult preflight(
      ClaimedWork claim,
      Prepare.Preflight preflight,
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

  private ProcessResult bouncePreflight(ClaimedWork claim, Prepare.Preflight preflight) {
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
    Prepare.Dispatched dispatched =
        new Prepare.Dispatched(scope, attempt, request, ToolApproval.notRequired());
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
      ClaimedWork claim, Prepare.Preflight preflight, ToolGateway.Ask ask) {
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
      ClaimedWork claim, Prepare.Preflight preflight, ToolInvocationError error) {
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
      ClaimedWork claim, ExecutionScopeReadFailure failure) {
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
      Prepare.Dispatched dispatched,
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
      Prepare.Dispatched dispatched,
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
      ClaimedWork claim, Prepare.Dispatched dispatched, Duration delay) {
    return reschedule(claim, dispatched.scope().threadId(), dispatched.attempt(), delay)
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  private ProcessResult launchReattach(
      ClaimedWork claim,
      Prepare.Reattach reattach,
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
      Prepare.Reattach reattach,
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
      ClaimedWork claim, Prepare.Reattach reattach, Duration delay) {
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

  /** 有效 YOLO 只由执行根的开关决定；子代理读取其不可变执行根。 */
  private static boolean effectiveYoloEnabled(HarnessStore.Transaction tx, ThreadState thread) {
    if (thread.rootThreadId().equals(thread.id())) {
      return thread.yoloEnabled();
    }
    return tx.threads().find(thread.rootThreadId()).orElseThrow().yoloEnabled();
  }

  /** 冻结问卷无法解析时的确定性错误 kind：契约漂移，可重试前提是工具实现修好。 */
  private static final String INVALID_QUESTIONNAIRE_KIND = "INVALID_QUESTIONNAIRE";

  private static final HumanInputJsonCodec INPUT_JSON = new HumanInputJsonCodec();

  /**
   * 执行上下文事实（会话 / 执行根 / 分支视图 / 冻结媒体能力）在 prepare 事务内读取失败的回滚信号。
   *
   * <p>只在 {@link #executionScope} 内产生：携带已锁定的 Thread 与 attempt，使 {@link #process} 能在事务回滚后按 admission
   * 失败路径锁后重新校验并重排；它不表达持久事实，原始失败保留为 cause，绝不被吞掉。
   */
  private static final class ExecutionScopeReadFailure extends RuntimeException {

    private final UUID threadId;
    private final int attempt;

    ExecutionScopeReadFailure(UUID threadId, int attempt, RuntimeException cause) {
      super("tool execution scope read failed", cause);
      this.threadId = Objects.requireNonNull(threadId, "threadId");
      this.attempt = attempt;
    }

    UUID threadId() {
      return threadId;
    }

    int attempt() {
      return attempt;
    }
  }

  private sealed interface Prepare
      permits Prepare.Lost,
          Prepare.Input,
          Prepare.Preflight,
          Prepare.Allowed,
          Prepare.Dispatched,
          Prepare.Reattach,
          Prepare.Terminated {

    record Lost() implements Prepare {}

    /** 内部人工输入工具：READY 边界已冻结为 WAITING_INPUT，不进入 preflight / gateway。 */
    record Input(UUID threadId) implements Prepare {}

    /** 普通 gateway preflight（锁内 YOLO 快照为 false）。 */
    record Preflight(ExecutionScope scope, int attempt, ToolInvocationRequest request)
        implements Prepare {}

    /** 锁内 YOLO 快照为 true 的直接 Allow：不调用 gateway preflight，transient 决策标志。 */
    record Allowed(ExecutionScope scope, int attempt, ToolInvocationRequest request)
        implements Prepare {}

    record Dispatched(
        ExecutionScope scope, int attempt, ToolInvocationRequest request, ToolApproval approval)
        implements Prepare {}

    /** 远端重新挂接：只需要所属 Thread id，不携带执行上下文快照。 */
    record Reattach(
        UUID threadId, int attempt, ToolInvocationRequest request, ExecutionExitFact remoteFact)
        implements Prepare {}

    record Terminated() implements Prepare {}
  }
}
