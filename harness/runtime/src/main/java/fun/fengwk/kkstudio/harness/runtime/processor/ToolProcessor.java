package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;

/**
 * Target Tool processor：消费 dispatcher 已 claim 的 TOOL Work，驱动一次 Tool invocation 的完整生命周期。
 *
 * <p>只依赖单一 {@link HarnessStore} + {@link ToolGateway} + {@link RealtimeEventSink}，不自行全局 poll；不做任何
 * Entry / head / ToolResult Entry / Usage 写入。所有 durable mutation 都在短事务内通过 store 锁序（Thread -&gt;
 * ModelInvocation -&gt; ToolInvocation -&gt; Work；同事务含 THREAD Work 时先按 (type, id) 预锁 THREAD Work 再
 * lockClaimed TOOL Work）与 claim ownership 校验完成，lost / stale 一律完整 no-op。
 *
 * <p>状态机：READY + approval null 在 ensure 完整 lease margin 后于事务外执行 {@link ToolGateway#preflight}
 * （preflight 期间由本地 heartbeat 维持 lease），Allow 在同一短事务顺序转换 markApprovalNotRequired -&gt; beginDispatch
 * （Thread revision 只 +1）后进入 admission；Ask 转 WAITING_APPROVAL（revision+1 + complete，不 request
 * THREAD）； Deny 转 FAILED（revision+1 + THREAD wake + complete）；preflight 抛异常保持 READY / approval null
 * / revision 不变， 按 {@code preflightFailureDelay} reschedule。READY + completed approval 同事务
 * beginDispatch + revision+1 后直接 admission。WAITING_APPROVAL 只 complete TOOL Work；DISPATCHING /
 * RUNNING 旧 lease 恢复为 UNKNOWN（DISPATCHING 消费 proposed attempt，RUNNING 保留）+ revision+1 + THREAD wake
 * + complete，绝不重放 Tool；terminal 行只确保 THREAD Work 后 complete，不重复 bump revision。
 *
 * <p>admission 与回调并发协议与 {@link ModelProcessor} 一致：claimOwned Work-only 前置校验 -&gt; per-invocation
 * guard -&gt; registry；Started 后 handle 先安全 attach 再短事务校验 lease + DISPATCHING + proposed attempt 后
 * markRunning； listener 在 durable RUNNING 前门控缓冲。heartbeat 只 renew 当前 Work lease；进程内 registry 以
 * invocationId 为键， {@link #cancel} 提供唯一本地取消入口，{@link #close} 取消全部并停止各自 heartbeat（不 shutdown 注入的
 * scheduler），closed 后 {@link #process} 拒绝新 claim。
 */
@Slf4j
public final class ToolProcessor implements AutoCloseable {

  private final HarnessStore store;
  private final ToolGateway gateway;
  private final RealtimeEventSink realtimeEventSink;
  private final ToolProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentHashMap<Long, ToolExecution> executions = new ConcurrentHashMap<>();
  private final ClaimAdmissionGuard admissionGuard = new ClaimAdmissionGuard();
  private volatile boolean closed;

  public ToolProcessor(
      HarnessStore store,
      ToolGateway gateway,
      RealtimeEventSink realtimeEventSink,
      ToolProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.store = Objects.requireNonNull(store, "store");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /**
   * 处理一次 dispatcher 已 claim 的 TOOL Work。
   *
   * <p>任何 admission guard 替换 / active supersede 之前，先用 Work-only 短事务 {@code lockClaimedWork} 验证传入
   * claim 当前真实 owned：伪造 / 错误 / 已过期 token 一律 LOST_OWNERSHIP no-op，绝不 cancel 合法 active execution。 随后
   * per-invocation guard 覆盖 prepare 到 registry 插入：同一 claim（同 lease token）重复 / 并发投递直接返回
   * LOST_OWNERSHIP（不 cancel、不 mutation）；不同新 token（已通过 Work-only 校验，旧 lease 必然过期）先 supersede 旧本地
   * execution 再 prepare。
   */
  public ProcessResult process(ClaimedWork claim) {
    Objects.requireNonNull(claim, "claim");
    if (closed) {
      throw new IllegalStateException("tool processor is closed");
    }
    if (claim.target().type() != WorkTargetType.TOOL) {
      throw new IllegalArgumentException(
          "ToolProcessor requires a TOOL work claim, got " + claim.target());
    }
    long invocationId = claim.target().id();
    String token = claim.leaseToken();
    if (!claimOwned(claim)) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    if (!admissionGuard.tryAdmit(invocationId, token)) {
      return ProcessResult.LOST_OWNERSHIP;
    }
    try {
      ToolExecution active = executions.get(invocationId);
      if (active != null) {
        if (active.claim().leaseToken().equals(token)) {
          // 同一 claim 已在本地处理：重复 / 迟到投递，no-op，不 cancel、不 mutation。
          return ProcessResult.LOST_OWNERSHIP;
        }
        // 不同新 token：claimOwned 已证明新 claim 真实 owned（旧 lease 必然过期），supersede 旧本地 execution 安全。
        releaseExecution(active);
      }
      Prepare prepare = store.transaction(tx -> prepare(tx, claim));
      return switch (prepare) {
        case Prepare.Lost ignored -> ProcessResult.LOST_OWNERSHIP;
        case Prepare.Terminated ignored -> ProcessResult.TERMINATED;
        case Prepare.Preflight preflight -> preflight(claim, preflight);
        case Prepare.Dispatched dispatched -> dispatch(claim, dispatched, null);
      };
    } finally {
      admissionGuard.release(invocationId, token);
    }
  }

  /**
   * 取消一个进程内本地 execution（供 Stop durable commit 后 best-effort 调用）；结果不反写任何 durable 状态。
   *
   * @return 是否存在并已取消对应本地 execution
   */
  public boolean cancel(long invocationId) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    ToolExecution execution = executions.remove(invocationId);
    if (execution == null) {
      return false;
    }
    execution.abandon();
    return true;
  }

  /** 取消全部本地 execution 并停止各自 heartbeat；不 shutdown 调用方注入的 scheduler；幂等。 */
  @Override
  public void close() {
    closed = true;
    for (ToolExecution execution : List.copyOf(executions.values())) {
      execution.abandon();
    }
  }

  /** 当前 JVM 是否持有任何活跃的本地 Tool execution。 */
  public boolean hasActiveExecution() {
    return !executions.isEmpty();
  }

  /**
   * 任何 admission guard 替换 / active supersede 之前的 Work-only 前置校验：仅锁 Work 行（锁序：单实体事务只锁 Work）， 验证传入
   * claim 的 leaseToken 与 leaseUntil 当前仍真实 owned。不触碰 Thread / Invocation，无任何 mutation。
   */
  private boolean claimOwned(ClaimedWork claim) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(store.transaction(tx -> !tx.lockClaimedWork(claim, now).isEmpty()));
  }

  /** 按锁序 Thread -&gt; Model -&gt; Tool -&gt; Work 读取并分支当前 durable 状态；不重放、不重复 bump revision。 */
  private Prepare prepare(HarnessStore.Transaction tx, ClaimedWork claim) {
    Instant now = clock.instant();
    long invocationId = claim.target().id();
    ToolInvocation peek = tx.findToolInvocation(invocationId).orElse(null);
    if (peek == null) {
      return new Prepare.Lost();
    }
    ModelInvocation modelPeek = tx.findModelInvocation(peek.modelInvocationId()).orElse(null);
    if (modelPeek == null) {
      return new Prepare.Lost();
    }
    ThreadState thread = tx.lockThread(modelPeek.threadId()).orElse(null);
    if (thread == null) {
      return new Prepare.Lost();
    }
    ModelInvocation model = tx.lockModelInvocation(peek.modelInvocationId()).orElse(null);
    if (model == null) {
      return new Prepare.Lost();
    }
    ToolInvocation tool = tx.lockToolInvocation(invocationId).orElse(null);
    if (tool == null) {
      return new Prepare.Lost();
    }
    return switch (tool.status()) {
      case READY -> prepareReady(tx, claim, thread, model, tool, now);
      case WAITING_APPROVAL -> waitingApproval(tx, claim, now);
      case DISPATCHING, RUNNING -> recoverUnknown(tx, claim, thread, tool, now);
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> cleanupTerminal(tx, claim, thread, tool, now);
    };
  }

  /**
   * READY：approval null 走 preflight（ensure 完整 lease margin 后事务外执行，期间 heartbeat 维持 lease）；completed
   * approval 同事务 beginDispatch + revision+1 后直接 admission。
   */
  private Prepare prepareReady(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      ToolInvocation tool,
      Instant now) {
    Optional<Work> claimed = tx.lockClaimedWork(claim, now);
    if (claimed.isEmpty()) {
      return new Prepare.Lost();
    }
    // Gateway admission 前确保 lease 有完整 margin：剩余不足以撑到首次 heartbeat 时立即 renew。
    ProcessorLeaseSupport.ensureLeaseMargin(tx, claim, claimed.get(), config.leaseConfig(), now);
    if (tool.approval() == null) {
      // yolo 策略从 source model 的冻结 request 读取：ToolInvocation 本身不携带 yolo。
      return new Prepare.Preflight(
          thread.id(), tool.attempt(), tool.request(), model.request().yoloEnabled());
    }
    tx.updateToolInvocations(List.of(tool.beginDispatch(now)));
    tx.updateThread(thread.touchRevision(now));
    return new Prepare.Dispatched(thread.id(), tool.attempt(), tool.request());
  }

  /** WAITING_APPROVAL 不应执行：仅在 ownership 有效时 complete TOOL Work，不 bump revision、不 request THREAD。 */
  private Prepare waitingApproval(HarnessStore.Transaction tx, ClaimedWork claim, Instant now) {
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      return new Prepare.Lost();
    }
    tx.completeWork(claim, now);
    return new Prepare.Terminated();
  }

  /** 旧 lease 过期恢复：DISPATCHING 消费 proposed attempt，RUNNING 保留 attempt；绝不重放 Tool。 */
  private Prepare recoverUnknown(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now) {
    tx.lockWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      return new Prepare.Lost();
    }
    ToolInvocationError error =
        new ToolInvocationError(
            "LEASE_EXPIRED", "tool work lease expired; tool outcome cannot be confirmed");
    tx.updateToolInvocations(List.of(tool.unknown(error, now)));
    tx.updateThread(thread.touchRevision(now));
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    tx.completeWork(claim, now);
    return new Prepare.Terminated();
  }

  /** terminal 行：resultEntryId 仍 null 时确保 THREAD Work 请求，然后 complete TOOL Work；不重复 bump revision。 */
  private Prepare cleanupTerminal(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ToolInvocation tool,
      Instant now) {
    boolean needsThreadWake = tool.resultEntryId() == null;
    if (needsThreadWake) {
      // 锁序：同层 Work 按 (type, id) 升序，THREAD Work 必须先于 TOOL Work 预锁。
      tx.lockWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      return new Prepare.Lost();
    }
    if (needsThreadWake) {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    }
    tx.completeWork(claim, now);
    return new Prepare.Terminated();
  }

  /**
   * 事务外 preflight：先建本地 execution 并启动 heartbeat（preflight 期间维持 lease，且 close / cancel 可中止），再调用
   * {@link ToolGateway#preflight}。preflight 抛异常 / 返回 null（确定无副作用）时保持 READY / approval null /
   * revision 不变，按配置延迟 reschedule；所有结果提交前二次校验 claim + READY + attempt + approval null，lost 完整 no-op。
   */
  private ProcessResult preflight(ClaimedWork claim, Prepare.Preflight preflight) {
    long invocationId = claim.target().id();
    ToolExecution execution =
        newExecution(claim, preflight.threadId(), preflight.attempt(), preflight.request());
    ToolExecution existing = executions.putIfAbsent(invocationId, execution);
    if (existing != null) {
      // 防御分支（admission guard 串行化后实际不可达）：不启动第二个 preflight / execution。
      log.warn("a concurrent local execution already owns tool invocation {}", invocationId);
      return ProcessResult.LOST_OWNERSHIP;
    }
    if (closed) {
      // close 已拍快照但本 execution 尚未被 abandon：立即 abandon；durable 仍是 READY（无 DISPATCHING 可 bounce），
      // claim 仍 owned 时立即 reschedule，不留新 execution；Stop 已删除 Work 时则自然 LOST。
      execution.abandon();
      return reschedulePreflight(claim, preflight, config.preflightFailureDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (!execution.startHeartbeat()) {
      // scheduler 拒绝：无法维持 lease，按 bounce 实际结果返回（仍 owned 才 RESCHEDULED，已 lost 则 LOST）。
      execution.abandon();
      return reschedulePreflight(claim, preflight, config.preflightFailureDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (execution.abandoned()) {
      // close / 本地 cancel 在 heartbeat 启动窗口内获胜：不调用 preflight；claim 仍 owned 时立即重排。
      return reschedulePreflight(claim, preflight, config.preflightFailureDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    ToolGateway.PreflightResult result;
    try {
      result = gateway.preflight(preflight.request(), preflight.yoloEnabled());
    } catch (RuntimeException failure) {
      // 契约：抛异常表示 preflight 确定无副作用（listener / Gateway start 尚未发生）。
      log.warn("tool preflight failed for invocation {}: {}", invocationId, failure.toString());
      execution.abandon();
      return reschedulePreflight(claim, preflight, config.preflightFailureDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (execution.abandoned()) {
      // close / cancel / heartbeat loss 在 preflight 期间获胜：claim 仍 owned时立即重排，否则 LOST。
      return reschedulePreflight(claim, preflight, config.preflightFailureDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (result == null) {
      // 契约违反：preflight 结果未知但无副作用，按 preflightFailureDelay 重排。
      log.warn("tool preflight returned null for invocation {}", invocationId);
      execution.abandon();
      return reschedulePreflight(claim, preflight, config.preflightFailureDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    return switch (result) {
      case ToolGateway.Allow ignored -> applyPreflightAllow(claim, preflight, execution);
      case ToolGateway.Ask ask -> applyPreflightAsk(claim, preflight, execution, ask.reason());
      case ToolGateway.Deny deny -> applyPreflightDeny(claim, preflight, execution, deny.error());
    };
  }

  /**
   * Allow：在同一短事务把 READY / null approval 顺序转换 markApprovalNotRequired -&gt; beginDispatch（Store 允许同
   * tx 连续 update），Thread revision 只 +1；然后进入 admission。二次校验失败（lost / 状态被并发改写）完整 no-op。
   */
  private ProcessResult applyPreflightAllow(
      ClaimedWork claim, Prepare.Preflight preflight, ToolExecution execution) {
    Instant now = clock.instant();
    boolean dispatched =
        Boolean.TRUE.equals(
            store.transaction(
                tx -> {
                  ThreadState thread = tx.lockThread(preflight.threadId()).orElse(null);
                  if (thread == null) {
                    return false;
                  }
                  ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
                  if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                    return false;
                  }
                  if (tool.status() != ToolInvocationStatus.READY
                      || tool.attempt() != preflight.attempt()
                      || tool.approval() != null) {
                    return false;
                  }
                  ToolInvocation approved = tool.markApprovalNotRequired(now);
                  tx.updateToolInvocations(List.of(approved));
                  tx.updateToolInvocations(List.of(approved.beginDispatch(now)));
                  tx.updateThread(thread.touchRevision(now));
                  return true;
                }));
    if (!dispatched) {
      execution.abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    return dispatch(
        claim,
        new Prepare.Dispatched(preflight.threadId(), preflight.attempt(), preflight.request()),
        execution);
  }

  /**
   * Ask：READY -&gt; WAITING_APPROVAL(request reason)，revision+1，complete TOOL Work；不 request
   * THREAD。
   */
  private ProcessResult applyPreflightAsk(
      ClaimedWork claim, Prepare.Preflight preflight, ToolExecution execution, String reason) {
    Instant now = clock.instant();
    boolean committed =
        Boolean.TRUE.equals(
            store.transaction(
                tx -> {
                  ThreadState thread = tx.lockThread(preflight.threadId()).orElse(null);
                  if (thread == null) {
                    return false;
                  }
                  ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
                  if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                    return false;
                  }
                  if (tool.status() != ToolInvocationStatus.READY
                      || tool.attempt() != preflight.attempt()
                      || tool.approval() != null) {
                    return false;
                  }
                  tx.updateToolInvocations(List.of(tool.requestApproval(reason, now)));
                  tx.updateThread(thread.touchRevision(now));
                  tx.completeWork(claim, now);
                  return true;
                }));
    execution.abandon();
    return committed ? ProcessResult.TERMINATED : ProcessResult.LOST_OWNERSHIP;
  }

  /** Deny：READY -&gt; FAILED(error)，revision+1，request THREAD Work，complete。 */
  private ProcessResult applyPreflightDeny(
      ClaimedWork claim,
      Prepare.Preflight preflight,
      ToolExecution execution,
      ToolInvocationError error) {
    Instant now = clock.instant();
    boolean committed =
        Boolean.TRUE.equals(
            store.transaction(
                tx -> {
                  ThreadState thread = tx.lockThread(preflight.threadId()).orElse(null);
                  if (thread == null) {
                    return false;
                  }
                  ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
                  if (tool == null) {
                    return false;
                  }
                  tx.lockWork(new WorkTarget(WorkTargetType.THREAD, preflight.threadId()));
                  if (tx.lockClaimedWork(claim, now).isEmpty()) {
                    return false;
                  }
                  if (tool.status() != ToolInvocationStatus.READY
                      || tool.attempt() != preflight.attempt()
                      || tool.approval() != null) {
                    return false;
                  }
                  tx.updateToolInvocations(List.of(tool.fail(error, now)));
                  tx.updateThread(thread.touchRevision(now));
                  tx.requestWork(new WorkTarget(WorkTargetType.THREAD, preflight.threadId()), now);
                  tx.completeWork(claim, now);
                  return true;
                }));
    execution.abandon();
    return committed ? ProcessResult.TERMINATED : ProcessResult.LOST_OWNERSHIP;
  }

  /**
   * preflight 失败（抛异常 / null）：二次校验 claim + READY + attempt + approval null 后仅 reschedule，无 revision。
   */
  private boolean reschedulePreflight(
      ClaimedWork claim, Prepare.Preflight preflight, Duration delay) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(preflight.threadId()).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.READY
                  || tool.attempt() != preflight.attempt()
                  || tool.approval() != null) {
                return false;
              }
              tx.rescheduleWork(claim, now, now.plus(delay));
              return true;
            }));
  }

  /**
   * Gateway admission 续段：execution 可能已在 preflight 阶段创建（heartbeat 已启动，此时复用）。任何放弃路径都先 abandon 本地
   * execution 再 bounce / terminal 收敛；stale-start fence 校验 durable 仍 DISPATCHING + attempt 匹配 +
   * claim 仍 owned，失败绝不调用 Gateway。
   */
  private ProcessResult dispatch(
      ClaimedWork claim, Prepare.Dispatched dispatched, ToolExecution execution) {
    long invocationId = claim.target().id();
    if (execution == null) {
      execution =
          newExecution(claim, dispatched.threadId(), dispatched.attempt(), dispatched.request());
      ToolExecution existing = executions.putIfAbsent(invocationId, execution);
      if (existing != null) {
        // 防御分支（admission guard 串行化后实际不可达）：并发竞态，不启动第二个 Gateway 调用，也不做任何 durable mutation。
        log.warn("a concurrent local execution already owns tool invocation {}", invocationId);
        return ProcessResult.LOST_OWNERSHIP;
      }
    }
    if (closed) {
      // close 已拍快照但本 execution 尚未被 abandon（close snapshot 之前插入的窗口）：立即 abandon（从 registry
      // 移除，绝不留下新 execution），并把仍 owned 的 DISPATCHING 安全 bounce READY + reschedule，不启动 Gateway。
      execution.abandon();
      return bounceDispatch(claim, dispatched, config.dispatchBusyFallbackDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (!execution.startHeartbeat()) {
      // scheduler 拒绝：无法维持 lease，按 bounce 实际结果返回（仍 owned 才 RESCHEDULED，已 lost 则 LOST）。
      execution.abandon();
      return bounceDispatch(claim, dispatched, config.dispatchBusyFallbackDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (execution.abandoned()) {
      // close / 本地 cancel 在 heartbeat 启动窗口内获胜（close snapshot 之后插入的 execution 由 close 负责 abandon）：
      // 不启动 Gateway；DISPATCHING 仍 owned 则安全 bounce READY + reschedule。
      return bounceDispatch(claim, dispatched, config.dispatchBusyFallbackDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    // stale-start fence：Gateway.start 的确切 admission 边界。再校验 durable 仍 DISPATCHING + attempt 匹配 +
    // claim 仍 owned（覆盖跨实例 recovery：另一 JVM 的 processor 恢复 UNKNOWN 后本实例的本地 abandoned 检查看不到）。
    // 失败立即 abandon 并 LOST，绝不调用 Gateway；此检查之后并发 Stop / recovery 属于 DISPATCHING 不确定窗口，
    // 由 Gateway 的 Indeterminate / Started 后 markRunning 校验等现有 UNKNOWN / CANCEL 协议处理。
    if (!checkStartBoundary(claim, dispatched)) {
      execution.abandon();
      return ProcessResult.LOST_OWNERSHIP;
    }
    ToolGateway.StartResult result;
    try {
      result =
          gateway.start(
              new ToolGateway.Execution(
                  invocationId, Math.addExact(dispatched.attempt(), 1), dispatched.request()),
              execution);
    } catch (RuntimeException failure) {
      // 契约：抛异常表示 Gateway 肯定未接受，可安全转 READY 并 reschedule（attempt 不变）。
      log.warn("tool gateway start failed for invocation {}: {}", invocationId, failure.toString());
      execution.abandon();
      return bounceDispatch(claim, dispatched, config.dispatchBusyFallbackDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (result == null) {
      // 契约违反：acceptance 不确定，按 Indeterminate 语义收敛（UNKNOWN attempt+1 + THREAD wake + complete）。
      log.warn("tool gateway start returned null for invocation {}", invocationId);
      execution.abandon();
      ToolInvocationError error =
          new ToolInvocationError(
              "GATEWAY_UNKNOWN", "tool gateway start returned null; outcome cannot be confirmed");
      return unknownDispatch(claim, dispatched, error)
          ? ProcessResult.TERMINATED
          : ProcessResult.LOST_OWNERSHIP;
    }
    return switch (result) {
      case ToolGateway.Started started -> execution.activate(started.handle());
      case ToolGateway.Busy busy -> {
        execution.abandon();
        yield bounceDispatch(claim, dispatched, busy.retryAfter())
            ? ProcessResult.RESCHEDULED
            : ProcessResult.LOST_OWNERSHIP;
      }
      case ToolGateway.Overloaded overloaded -> {
        execution.abandon();
        yield bounceDispatch(claim, dispatched, overloaded.retryAfter())
            ? ProcessResult.RESCHEDULED
            : ProcessResult.LOST_OWNERSHIP;
      }
      case ToolGateway.Rejected rejected -> {
        execution.abandon();
        yield rejectDispatch(claim, dispatched, rejected.error())
            ? ProcessResult.TERMINATED
            : ProcessResult.LOST_OWNERSHIP;
      }
      case ToolGateway.Indeterminate indeterminate -> {
        execution.abandon();
        yield unknownDispatch(claim, dispatched, indeterminate.error())
            ? ProcessResult.TERMINATED
            : ProcessResult.LOST_OWNERSHIP;
      }
    };
  }

  private ToolExecution newExecution(
      ClaimedWork claim, long threadId, int attempt, ToolInvocationRequest request) {
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
        this::release);
  }

  /**
   * Gateway.start 的确切 admission 边界：无 mutation 短事务（锁序 ToolInvocation -&gt; Work）校验 durable 仍
   * DISPATCHING + attempt 匹配 + claim 仍 owned。失败表示 stale start（本地 abandoned 检查覆盖不到的跨实例 recovery /
   * Stop 已获胜），调用方不得启动 Gateway。
   */
  private boolean checkStartBoundary(ClaimedWork claim, Prepare.Dispatched dispatched) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              return tool.status() == ToolInvocationStatus.DISPATCHING
                  && tool.attempt() == dispatched.attempt();
            }));
  }

  /**
   * admission 肯定未开始（Busy / Overloaded / 异常）：DISPATCHING -&gt; READY + revision+1 + 按延迟 reschedule
   * TOOL Work（attempt 不变）。
   */
  private boolean bounceDispatch(ClaimedWork claim, Prepare.Dispatched dispatched, Duration delay) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(dispatched.threadId()).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
              if (tool == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.DISPATCHING
                  || tool.attempt() != dispatched.attempt()) {
                return false;
              }
              tx.updateToolInvocations(List.of(tool.dispatchBusy(now)));
              tx.updateThread(thread.touchRevision(now));
              tx.rescheduleWork(claim, now, now.plus(delay));
              return true;
            }));
  }

  /**
   * admission 明确拒绝：rejectDispatch FAILED + revision+1 + 请求 THREAD Work + complete TOOL Work（attempt
   * 不变）。
   */
  private boolean rejectDispatch(
      ClaimedWork claim, Prepare.Dispatched dispatched, ToolInvocationError error) {
    return terminalDispatch(claim, dispatched, (tool, now) -> tool.rejectDispatch(error, now));
  }

  /** admission 不确定：unknown UNKNOWN（attempt+1）+ revision+1 + 请求 THREAD Work + complete TOOL Work。 */
  private boolean unknownDispatch(
      ClaimedWork claim, Prepare.Dispatched dispatched, ToolInvocationError error) {
    return terminalDispatch(claim, dispatched, (tool, now) -> tool.unknown(error, now));
  }

  private boolean terminalDispatch(
      ClaimedWork claim,
      Prepare.Dispatched dispatched,
      BiFunction<ToolInvocation, Instant, ToolInvocation> transition) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(dispatched.threadId()).orElse(null);
              if (thread == null) {
                return false;
              }
              ToolInvocation tool = tx.lockToolInvocation(claim.target().id()).orElse(null);
              if (tool == null) {
                return false;
              }
              tx.lockWork(new WorkTarget(WorkTargetType.THREAD, dispatched.threadId()));
              if (tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (tool.status() != ToolInvocationStatus.DISPATCHING
                  || tool.attempt() != dispatched.attempt()) {
                return false;
              }
              tx.updateToolInvocations(List.of(transition.apply(tool, now)));
              tx.updateThread(thread.touchRevision(now));
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, dispatched.threadId()), now);
              tx.completeWork(claim, now);
              return true;
            }));
  }

  private void release(ToolExecution execution) {
    executions.remove(execution.invocationId(), execution);
  }

  private void releaseExecution(ToolExecution execution) {
    executions.remove(execution.invocationId(), execution);
    execution.abandon();
  }

  private sealed interface Prepare
      permits Prepare.Lost, Prepare.Preflight, Prepare.Dispatched, Prepare.Terminated {

    record Lost() implements Prepare {}

    record Preflight(long threadId, int attempt, ToolInvocationRequest request, boolean yoloEnabled)
        implements Prepare {}

    record Dispatched(long threadId, int attempt, ToolInvocationRequest request)
        implements Prepare {}

    record Terminated() implements Prepare {}
  }
}
