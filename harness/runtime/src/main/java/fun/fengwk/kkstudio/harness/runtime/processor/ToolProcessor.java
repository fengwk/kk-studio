package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputTool;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

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
 * reschedule。READY + completed approval 直接进入执行。WAITING_APPROVAL 只 complete TOOL Work；RUNNING 旧
 * lease 恢复为 UNKNOWN（attempt 保留）+ version+1 + THREAD wake + complete，绝不重放 Tool；terminal 行只确保 THREAD
 * Work 后 complete，不重复 bump version。
 *
 * <p>执行：{@link ToolGateway#admit} 做纯本地 admission（Busy 按 retryAfter reschedule、Rejected 转
 * FAILED，两者都肯定无副作用，attempt 不变）， Admitted 后在任何副作用之前持久化 {@code READY -> RUNNING}，再由 {@link
 * ToolExecution} 在独占虚拟线程上阻塞执行至终态， 终态与资源释放都发生在该线程上。heartbeat 只 renew 当前 Work lease。
 */
@Slf4j
public final class ToolProcessor implements AutoCloseable {

  private final HarnessStore store;
  private final ExecutionExitConfirmer exitConfirmer;
  private final ToolGateway gateway;
  private final RealtimeEventSink realtimeEventSink;
  private final ToolProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Executor heartbeatWorker;
  private final ToolResultHistoryMaterializer toolResultHistoryMaterializer;
  private final ClaimAdmissionGuard<ToolExecution> admissionGuard = new ClaimAdmissionGuard<>();

  public ToolProcessor(
      HarnessStore store,
      ToolGateway gateway,
      RealtimeEventSink realtimeEventSink,
      ToolProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Executor heartbeatWorker,
      ExecutionExitConfirmer exitConfirmer,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer) {
    this.store = Objects.requireNonNull(store, "store");
    this.exitConfirmer = Objects.requireNonNull(exitConfirmer, "exitConfirmer");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.heartbeatWorker = Objects.requireNonNull(heartbeatWorker, "heartbeatWorker");
    this.toolResultHistoryMaterializer =
        Objects.requireNonNull(toolResultHistoryMaterializer, "toolResultHistoryMaterializer");
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
    Objects.requireNonNull(claim, "claim");
    if (admissionGuard.isClosed()) {
      throw new IllegalStateException("tool processor is closed");
    }
    if (claim.target().type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "ToolProcessor requires a TOOL work claim, got " + claim.target());
    }
    if (!claimOwned(claim)) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    ClaimAdmissionGuard.Ticket<ToolExecution> ticket =
        admissionGuard.tryAdmit(claim, () -> claimOwned(claim));
    if (ticket == null) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    try (ticket) {
      Prepare prepare = store.transaction(tx -> prepareLocked(tx, claim, tx.now()));
      return switch (prepare) {
        case Prepare.Lost ignored -> ProcessResult.LOST_OWNERSHIP;
        case Prepare.Terminated ignored -> ProcessResult.TERMINATED;
        case Prepare.Input input -> requestInput(claim, input.threadId());
        case Prepare.Preflight preflight -> preflight(claim, preflight, ticket);
        case Prepare.Allowed allowed -> applyPreflightAllow(
            claim,
            allowed.threadId(),
            allowed.assistantEntryId(),
            allowed.attempt(),
            allowed.request(),
            null,
            ticket);
        case Prepare.Dispatched dispatched -> launch(claim, dispatched, ticket);
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
    Objects.requireNonNull(invocationId, "invocationId");
    return admissionGuard.cancel(invocationId);
  }

  /** 取消全部本地 execution 并停止各自 heartbeat；不 shutdown 调用方注入的 scheduler；幂等。 */
  @Override
  public void close() {
    admissionGuard.close();
  }

  /** 当前 JVM 是否持有任何活跃的本地 Tool execution。 */
  public boolean hasActiveExecution() {
    return admissionGuard.hasActiveExecution();
  }

  /** 仅锁 Work 验证所有权，用于前置校验与 guard 内复核。 */
  private boolean claimOwned(ClaimedWork claim) {
    return Boolean.TRUE.equals(store.transaction(tx -> !tx.lockClaimedWork(claim).isEmpty()));
  }

  /** 按锁序 Thread -&gt; Tool -&gt; Work 读取并分支当前 durable 状态；不重放、不重复 bump version。 */
  private Prepare prepareLocked(HarnessStore.Transaction tx, ClaimedWork claim, Instant now) {
    UUID invocationId = claim.target().id();
    ToolInvocation peek = tx.findToolInvocation(invocationId).orElse(null);
    if (peek == null) {
      return new Prepare.Lost();
    }
    ThreadState thread = tx.lockThread(peek.threadId()).orElse(null);
    if (thread == null) {
      return new Prepare.Lost();
    }
    ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
    if (tool == null) {
      return new Prepare.Lost();
    }
    // 公共工具归属与能力已冻结在 TurnStart；执行路径不读取/锁定 ModelInvocation。
    if (!tool.threadId().equals(thread.id())) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its thread during preparation");
    }
    return switch (tool.status()) {
      case READY -> prepareReady(tx, claim, thread, tool, now);
      case WAITING_APPROVAL, WAITING_INPUT -> waitingForHuman(tx, claim, now);
      case RUNNING -> recoverUnknown(tx, claim, thread, tool, now);
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
      Instant now) {
    Optional<Work> claimed = tx.lockClaimedWork(claim);
    if (claimed.isEmpty()) {
      return new Prepare.Lost();
    }
    if (HumanInputTool.isHumanInputTool(tool.binding())) {
      return new Prepare.Input(thread.id());
    }
    // admission 前确保 lease 有完整 margin：剩余不足以撑到首次 heartbeat 时立即 renew。
    ProcessorLeaseSupport.ensureLeaseMargin(tx, claim, config.leaseConfig(), now);
    // READY 边界临时构造 transient executable request（不持久化）。
    ToolInvocationRequest request = new ToolInvocationRequest(tool.call(), tool.binding());
    if (tool.approval() == null) {
      if (effectiveYoloEnabled(tx, thread)) {
        // YOLO=true：锁内快照直接 Allow，绝不调用 permission evaluator / ToolGateway.preflight。
        return new Prepare.Allowed(thread.id(), tool.assistantEntryId(), tool.attempt(), request);
      }
      return new Prepare.Preflight(thread.id(), tool.assistantEntryId(), tool.attempt(), request);
    }
    return new Prepare.Dispatched(
        thread.id(), tool.assistantEntryId(), tool.attempt(), request, tool.approval());
  }

  /**
   * WAITING_APPROVAL / WAITING_INPUT 不应执行：仅在 ownership 有效时 complete TOOL Work，不 bump version、不
   * request THREAD。审批与问卷由用户侧显式决策/作答唤醒。
   */
  private Prepare waitingForHuman(HarnessStore.Transaction tx, ClaimedWork claim, Instant now) {
    if (tx.lockClaimedWork(claim).isEmpty()) {
      return new Prepare.Lost();
    }
    tx.completeWork(claim);
    return new Prepare.Terminated();
  }

  /**
   * 旧 lease 过期恢复：执行持有者已失效，绝不重放 Tool。RUNNING 表示可能已产生副作用；如实给出中断原因与最后已知状态，并明确外部效果未知， 交给下一轮模型自行决定查询或重试。
   */
  private Prepare recoverUnknown(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now) {
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.lockClaimedWork(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    ToolInvocationError error = leaseLostError(tool.status());
    tx.updateToolInvocations(List.of(tool.unknown(error, now)));
    tx.updateThread(thread.touchVersion(now));
    tx.completeWork(claim);
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
                  ThreadState thread = tx.lockThread(threadId).orElse(null);
                  if (thread == null) {
                    return false;
                  }
                  ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
                  if (tool == null || !isFrozenReadyInput(tool)) {
                    return false;
                  }
                  // 破坏契约的问卷以失败收敛并唤醒 Thread；Work 锁必须按 (type, id) 升序获取，因此先 request THREAD。
                  boolean answerable = isFrozenQuestionnaire(tool);
                  if (!answerable) {
                    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
                  }
                  if (tx.lockClaimedWork(claim).isEmpty()) {
                    throw new ClaimLostSignal();
                  }
                  tx.updateToolInvocations(
                      List.of(
                          answerable
                              ? tool.requestInput(now)
                              : tool.fail(
                                  new ToolInvocationError(
                                      INVALID_QUESTIONNAIRE_KIND,
                                      "ask_user invocation does not carry a valid questionnaire"),
                                  now)));
                  tx.updateThread(thread.touchVersion(now));
                  tx.completeWork(claim);
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
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.lockClaimedWork(claim).isEmpty()) {
      throw new ClaimLostSignal();
    }
    tx.completeWork(claim);
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
        newExecution(claim, preflight.threadId(), preflight.attempt(), preflight.request());
    if (!ticket.attach(execution)) {
      execution.abandon();
      return admissionGuard.isClosed()
          ? bouncePreflight(claim, preflight)
          : ProcessResult.LOST_OWNERSHIP;
    }
    boolean started = false;
    try {
      if (admissionGuard.isClosed()
          || !execution.startHeartbeat()
          || execution.abandoned()
          || ticket.isSuperseded()) {
        return bouncePreflight(claim, preflight);
      }
      ToolGateway.PreflightResult result;
      try {
        result = gateway.preflight(preflight.request());
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
                preflight.threadId(),
                preflight.assistantEntryId(),
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
            claim, preflight.threadId(), preflight.attempt(), config.preflightFailureDelay())
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
      UUID threadId,
      UUID assistantEntryId,
      int attempt,
      ToolInvocationRequest request,
      ToolExecution execution,
      ClaimAdmissionGuard.Ticket<ToolExecution> ticket) {
    boolean allowed =
        Boolean.TRUE.equals(
            store.transaction(tx -> allowIntent(tx, claim, threadId, attempt, tx.now())));
    if (!allowed) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    Prepare.Dispatched dispatched =
        new Prepare.Dispatched(
            threadId, assistantEntryId, attempt, request, ToolApproval.notRequired());
    if (execution != null) {
      if (admissionGuard.isClosed() || execution.abandoned() || ticket.isSuperseded()) {
        // Allow 意图提交后 close / cancel 获胜：不 admit，保持 READY 并重排。
        return rescheduleResult(claim, dispatched, config.admitFailureDelay());
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
    ThreadState thread = tx.lockThread(threadId).orElse(null);
    if (thread == null) {
      return false;
    }
    ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
    if (tool == null || tx.lockClaimedWork(claim).isEmpty()) {
      return false;
    }
    if (tool.status() != ToolInvocationStatus.READY
        || tool.attempt() != attempt
        || tool.approval() != null) {
      return false;
    }
    tx.updateToolInvocations(List.of(tool.markApprovalNotRequired(now)));
    tx.updateThread(thread.touchVersion(now));
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
                  ThreadState thread = tx.lockThread(preflight.threadId()).orElse(null);
                  if (thread == null) {
                    return false;
                  }
                  ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
                  if (tool == null || tx.lockClaimedWork(claim).isEmpty()) {
                    return false;
                  }
                  if (tool.status() != ToolInvocationStatus.READY
                      || tool.attempt() != preflight.attempt()
                      || tool.approval() != null) {
                    return false;
                  }
                  tx.updateToolInvocations(
                      List.of(tool.requestApproval(ask.reason(), ask.editReview(), now)));
                  tx.updateThread(thread.touchVersion(now));
                  tx.completeWork(claim);
                  return true;
                }));
    return committed ? ProcessResult.TERMINATED : ProcessResult.LOST_OWNERSHIP;
  }

  /** Deny：READY -&gt; FAILED(error)，version+1，request THREAD Work，complete。 */
  private ProcessResult applyPreflightDeny(
      ClaimedWork claim, Prepare.Preflight preflight, ToolInvocationError error) {
    boolean committed = failReady(claim, preflight.threadId(), preflight.attempt(), error);
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
                ThreadState thread = tx.lockThread(threadId).orElse(null);
                if (thread == null) {
                  return false;
                }
                ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
                if (tool == null) {
                  return false;
                }
                if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt) {
                  return false;
                }
                tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId));
                if (tx.lockClaimedWork(claim).isEmpty()) {
                  throw new ClaimLostSignal();
                }
                tx.updateToolInvocations(List.of(tool.fail(error, now)));
                tx.updateThread(thread.touchVersion(now));
                tx.completeWork(claim);
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
              ThreadState thread = tx.lockThread(threadId).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.READY || tool.attempt() != attempt) {
                return false;
              }
              tx.rescheduleWork(claim, delay);
              return true;
            }));
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
        newExecution(claim, dispatched.threadId(), dispatched.attempt(), dispatched.request());
    if (!ticket.attach(execution)) {
      execution.abandon();
      return admissionGuard.isClosed()
          ? rescheduleResult(claim, dispatched, config.admitFailureDelay())
          : ProcessResult.LOST_OWNERSHIP;
    }
    boolean started = false;
    try {
      if (admissionGuard.isClosed()
          || !execution.startHeartbeat()
          || execution.abandoned()
          || ticket.isSuperseded()) {
        return rescheduleResult(claim, dispatched, config.admitFailureDelay());
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
                  dispatched.threadId(),
                  dispatched.assistantEntryId(),
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
      return rescheduleResult(claim, dispatched, config.admitFailureDelay());
    }
    if (admission == null) {
      // 契约违反：admission 结果未知但无副作用，按 admitFailureDelay 重排。
      log.warn("tool admission returned null for invocation {}", invocationId);
      return rescheduleResult(claim, dispatched, config.admitFailureDelay());
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
          yield rescheduleResult(claim, dispatched, config.admitFailureDelay());
        }
        yield execution.start(admitted.run());
      }
      case ToolGateway.Busy busy -> rescheduleResult(claim, dispatched, busy.retryAfter());
      case ToolGateway.Rejected rejected -> failReady(
              claim, dispatched.threadId(), dispatched.attempt(), rejected.error())
          ? ProcessResult.TERMINATED
          : ProcessResult.LOST_OWNERSHIP;
    };
  }

  private ProcessResult rescheduleResult(
      ClaimedWork claim, Prepare.Dispatched dispatched, Duration delay) {
    return reschedule(claim, dispatched.threadId(), dispatched.attempt(), delay)
        ? ProcessResult.RESCHEDULED
        : ProcessResult.LOST_OWNERSHIP;
  }

  private ToolExecution newExecution(
      ClaimedWork claim, UUID threadId, int attempt, ToolInvocationRequest request) {
    return new ToolExecution(
        store,
        realtimeEventSink,
        claim,
        threadId,
        Math.addExact(attempt, 1),
        request,
        config,
        clock,
        scheduler,
        heartbeatWorker,
        this::release,
        exitConfirmer,
        toolResultHistoryMaterializer);
  }

  /** 有效 YOLO 只由执行根的开关决定；子代理读取其不可变执行根。 */
  private static boolean effectiveYoloEnabled(HarnessStore.Transaction tx, ThreadState thread) {
    if (thread.rootThreadId().equals(thread.id())) {
      return thread.yoloEnabled();
    }
    return tx.findThread(thread.rootThreadId()).orElseThrow().yoloEnabled();
  }

  private void release(ToolExecution execution) {
    admissionGuard.releaseExecution(execution);
  }

  /** 内部回滚信号：最终 Work ownership fence 失败时使当前事务完整回滚。 */
  /** 冻结问卷无法解析时的确定性错误 kind：契约漂移，可重试前提是工具实现修好。 */
  private static final String INVALID_QUESTIONNAIRE_KIND = "INVALID_QUESTIONNAIRE";

  private static final HumanInputJsonCodec INPUT_JSON = new HumanInputJsonCodec();

  private static final class ClaimLostSignal extends RuntimeException {
    private ClaimLostSignal() {
      super("claimed work lost at final fence", null, false, false);
    }
  }

  private sealed interface Prepare
      permits Prepare.Lost,
          Prepare.Input,
          Prepare.Preflight,
          Prepare.Allowed,
          Prepare.Dispatched,
          Prepare.Terminated {

    record Lost() implements Prepare {}

    /** 内部人工输入工具：READY 边界已冻结为 WAITING_INPUT，不再进入 preflight / gateway。 */
    record Input(UUID threadId) implements Prepare {}

    /** 普通 gateway preflight（锁内 YOLO 快照为 false）。 */
    record Preflight(
        UUID threadId, UUID assistantEntryId, int attempt, ToolInvocationRequest request)
        implements Prepare {}

    /** 锁内 YOLO 快照为 true 的直接 Allow：不调用 gateway preflight，transient 决策标志。 */
    record Allowed(UUID threadId, UUID assistantEntryId, int attempt, ToolInvocationRequest request)
        implements Prepare {}

    record Dispatched(
        UUID threadId,
        UUID assistantEntryId,
        int attempt,
        ToolInvocationRequest request,
        ToolApproval approval)
        implements Prepare {}

    record Terminated() implements Prepare {}
  }
}
