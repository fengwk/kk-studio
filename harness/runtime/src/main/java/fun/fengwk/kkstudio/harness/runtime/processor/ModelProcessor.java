package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;

/**
 * Target Model processor：消费 dispatcher 已 claim 的 MODEL Work，驱动一次 Model invocation 的完整生命周期。
 *
 * <p>只依赖单一 {@link HarnessStore} + {@link ModelGateway} + {@link RealtimeEventSink}，不自行全局 poll；不做任何
 * Entry / head / Usage 写入。所有 durable mutation 都在短事务内通过 store 锁序（Thread -&gt; ModelInvocation -&gt;
 * Work；同事务含 THREAD Work 时先按 (type, id) 预锁 THREAD Work 再 lockClaimed MODEL Work）与 claim ownership
 * 校验完成，lost / stale 一律完整 no-op。
 *
 * <p>admission 后 listener 由 {@link ModelExecution} 门控缓冲，callback 不可能早于 durable RUNNING 落地；heartbeat
 * 只 renew 当前 Work lease。进程内 execution registry 以 invocationId 为键，{@link #cancel} 提供唯一本地取消入口， {@link
 * #close} 取消全部并停止各自 heartbeat（不 shutdown 注入的 scheduler），closed 后 {@link #process} 拒绝新 claim；close 与
 * process 竞态下，registry 插入后启动 heartbeat 前会再次检查 closed，已关闭时立即 abandon 并把仍 owned 的 DISPATCHING 安全
 * bounce 回 READY + reschedule，绝不启动 Gateway，也不留下新 execution。
 *
 * <p>duplicate admission fencing：任何 guard 替换 / active supersede 之前先用 Work-only 短事务校验传入 claim 当前真实
 * owned（伪造 / 错误 / 已过期 token 一律 LOST_OWNERSHIP no-op，不 cancel 合法 active execution）；通过后再进
 * per-invocation guard 覆盖 prepare 到 registry 插入。同一 claim（同 lease token）重复 / 并发投递一律 LOST_OWNERSHIP
 * no-op（不 cancel、不 mutation）；只有不同新 token（已通过 Work-only 校验，旧 lease 必然过期）才 supersede 旧本地 execution 并按
 * durable DISPATCHING / RUNNING 恢复 UNKNOWN。heartbeat 启动后、{@link ModelGateway#start} 前还有一道无 mutation
 * 的 stale-start fence（durable 仍 DISPATCHING + attempt 匹配 + claim 仍 owned），失败立即 abandon 并 LOST，绝不启动
 * Provider（覆盖另一 JVM 实例 recovery 后本实例本地 abandoned 检查不可见的场景）。
 */
@Slf4j
public final class ModelProcessor implements AutoCloseable {

  private final HarnessStore store;
  private final ModelGateway gateway;
  private final RealtimeEventSink realtimeEventSink;
  private final ModelProcessorConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final ConcurrentHashMap<Long, ModelExecution> executions = new ConcurrentHashMap<>();
  private final ClaimAdmissionGuard admissionGuard = new ClaimAdmissionGuard();
  private volatile boolean closed;

  public ModelProcessor(
      HarnessStore store,
      ModelGateway gateway,
      RealtimeEventSink realtimeEventSink,
      ModelProcessorConfig config,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.store = Objects.requireNonNull(store, "store");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /**
   * 处理一次 dispatcher 已 claim 的 MODEL Work。
   *
   * <p>任何 admission guard 替换 / active supersede 之前，先用 Work-only 短事务 {@code lockClaimedWork} 验证传入
   * claim 当前真实 owned：伪造 / 错误 / 已过期 token 一律 LOST_OWNERSHIP no-op，绝不 cancel 合法 active execution。随后
   * per-invocation guard 覆盖 prepare 到 registry 插入：同一 claim（同 lease token）重复 / 并发投递直接返回
   * LOST_OWNERSHIP（不 cancel、不 mutation）；不同新 token（已通过 Work-only 校验，旧 lease 必然过期）先 supersede 旧本地
   * execution 再 prepare。prepare 短事务按 Thread -&gt; ModelInvocation -&gt; Work 锁序二次校验：READY 转
   * DISPATCHING + Thread revision+1（事务外启动 heartbeat / Gateway）；DISPATCHING / RUNNING（旧 lease
   * 过期恢复）收敛为 UNKNOWN + revision+1 + 请求 THREAD Work + complete MODEL Work，绝不重放 Provider；terminal
   * 行只确保 THREAD Work 请求 （resultEntryId 仍 null 时）后 complete MODEL Work，不重复 bump revision。
   */
  public ProcessResult process(ClaimedWork claim) {
    Objects.requireNonNull(claim, "claim");
    if (closed) {
      throw new IllegalStateException("model processor is closed");
    }
    if (claim.target().type() != WorkTargetType.MODEL) {
      throw new IllegalArgumentException(
          "ModelProcessor requires a MODEL work claim, got " + claim.target());
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
      ModelExecution active = executions.get(invocationId);
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
        case Prepare.Dispatched dispatched -> dispatch(claim, dispatched);
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
    ModelExecution execution = executions.remove(invocationId);
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
    for (ModelExecution execution : List.copyOf(executions.values())) {
      execution.abandon();
    }
  }

  /** 当前 JVM 是否持有任何活跃的本地 Model execution。 */
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

  /**
   * per-invocation admission guard：覆盖 prepare 到 registry 插入窗口（逻辑见 {@link ClaimAdmissionGuard}）。同一
   * claim（同 token）并发投递被拒绝；不同 token（旧 lease 已过期）并发投递抢占 guard。
   */
  private void releaseExecution(ModelExecution execution) {
    executions.remove(execution.invocationId(), execution);
    execution.abandon();
  }

  private ProcessResult dispatch(ClaimedWork claim, Prepare.Dispatched dispatched) {
    long invocationId = claim.target().id();
    int proposedAttempt = Math.addExact(dispatched.attempt(), 1);
    ModelExecution execution =
        new ModelExecution(
            store,
            realtimeEventSink,
            claim,
            dispatched.threadId(),
            proposedAttempt,
            dispatched.request(),
            config,
            clock,
            scheduler,
            this::release);
    ModelExecution existing = executions.putIfAbsent(invocationId, execution);
    if (existing != null) {
      // 防御分支（admission guard 串行化后实际不可达）：并发（不同 token 抢占 guard 后）竞态，不启动第二个 Provider
      // 调用，也不做任何 durable mutation；未启动的本地 execution 无副作用（无 heartbeat / handle / 缓冲），直接丢弃。
      log.warn("a concurrent local execution already owns invocation {}", invocationId);
      return ProcessResult.LOST_OWNERSHIP;
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
    ModelGateway.StartResult result;
    try {
      result =
          gateway.start(
              new ModelGateway.Execution(invocationId, proposedAttempt, dispatched.request()),
              execution);
    } catch (RuntimeException failure) {
      // 契约：抛异常表示 Gateway 肯定未接受，可安全转 READY 并 reschedule（attempt 不变）。
      log.warn(
          "model gateway start failed for invocation {}: {}",
          invocationId,
          ProcessorExceptions.describe(failure));
      execution.abandon();
      return bounceDispatch(claim, dispatched, config.dispatchBusyFallbackDelay())
          ? ProcessResult.RESCHEDULED
          : ProcessResult.LOST_OWNERSHIP;
    }
    if (result == null) {
      // 契约违反：acceptance 不确定，按 Indeterminate 语义收敛（UNKNOWN attempt+1 + THREAD wake + complete）。
      log.warn("model gateway start returned null for invocation {}", invocationId);
      execution.abandon();
      ModelInvocationError error =
          new ModelInvocationError(
              ProviderErrorKind.TRANSIENT,
              "model gateway start returned null; provider outcome cannot be confirmed");
      return unknownDispatch(claim, dispatched, error)
          ? ProcessResult.TERMINATED
          : ProcessResult.LOST_OWNERSHIP;
    }
    return switch (result) {
      case ModelGateway.Started started -> execution.activate(started.handle());
      case ModelGateway.Busy busy -> {
        execution.abandon();
        yield bounceDispatch(claim, dispatched, busy.retryAfter())
            ? ProcessResult.RESCHEDULED
            : ProcessResult.LOST_OWNERSHIP;
      }
      case ModelGateway.Rejected rejected -> {
        execution.abandon();
        yield rejectDispatch(claim, dispatched, rejected.error())
            ? ProcessResult.TERMINATED
            : ProcessResult.LOST_OWNERSHIP;
      }
      case ModelGateway.Indeterminate indeterminate -> {
        execution.abandon();
        yield unknownDispatch(claim, dispatched, indeterminate.error())
            ? ProcessResult.TERMINATED
            : ProcessResult.LOST_OWNERSHIP;
      }
    };
  }

  private Prepare prepare(HarnessStore.Transaction tx, ClaimedWork claim) {
    Instant now = clock.instant();
    long invocationId = claim.target().id();
    ModelInvocation peek = tx.findModelInvocation(invocationId).orElse(null);
    if (peek == null) {
      return new Prepare.Lost();
    }
    ThreadState thread = tx.lockThread(peek.threadId()).orElse(null);
    if (thread == null) {
      return new Prepare.Lost();
    }
    ModelInvocation model = tx.lockModelInvocation(invocationId).orElse(null);
    if (model == null) {
      return new Prepare.Lost();
    }
    return switch (model.status()) {
      case READY -> prepareReady(tx, claim, thread, model, now);
      case DISPATCHING, RUNNING -> recoverUnknown(tx, claim, thread, model, now);
      case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> cleanupTerminal(tx, claim, thread, model, now);
    };
  }

  private Prepare prepareReady(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    Optional<Work> claimed = tx.lockClaimedWork(claim, now);
    if (claimed.isEmpty()) {
      return new Prepare.Lost();
    }
    // Gateway admission 前确保 lease 有完整 margin：剩余不足以撑到首次 heartbeat 时立即 renew。
    ProcessorLeaseSupport.ensureLeaseMargin(tx, claim, claimed.get(), config.leaseConfig(), now);
    tx.updateModelInvocation(model.beginDispatch(now));
    tx.updateThread(thread.touchRevision(now));
    return new Prepare.Dispatched(thread.id(), model.attempt(), model.request());
  }

  /**
   * Gateway.start 的确切 admission 边界：无 mutation 短事务（锁序 ModelInvocation -&gt; Work）校验 durable 仍
   * DISPATCHING + attempt 匹配 + claim 仍 owned。失败表示 stale start（本地 abandoned 检查覆盖不到的跨实例 recovery /
   * Stop 已获胜），调用方不得启动 Provider。
   */
  private boolean checkStartBoundary(ClaimedWork claim, Prepare.Dispatched dispatched) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ModelInvocation model = tx.lockModelInvocation(claim.target().id()).orElse(null);
              if (model == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              return model.status() == ModelInvocationStatus.DISPATCHING
                  && model.attempt() == dispatched.attempt();
            }));
  }

  /** 旧 lease 过期恢复：DISPATCHING 消费 proposed attempt，RUNNING 保留 attempt；绝不重放 Provider。 */
  private Prepare recoverUnknown(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    tx.lockWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    if (tx.lockClaimedWork(claim, now).isEmpty()) {
      return new Prepare.Lost();
    }
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT,
            "model work lease expired; provider outcome cannot be confirmed");
    tx.updateModelInvocation(model.unknown(error, now));
    tx.updateThread(thread.touchRevision(now));
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    tx.completeWork(claim, now);
    return new Prepare.Terminated();
  }

  /**
   * terminal 行：resultEntryId 仍 null 时确保 THREAD Work 请求，然后 complete MODEL Work；不重复 bump revision。
   */
  private Prepare cleanupTerminal(
      HarnessStore.Transaction tx,
      ClaimedWork claim,
      ThreadState thread,
      ModelInvocation model,
      Instant now) {
    boolean needsThreadWake = model.resultEntryId() == null;
    if (needsThreadWake) {
      // 锁序：同层 Work 按 (type, id) 升序，THREAD Work 必须先于 MODEL Work 预锁。
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
   * admission 肯定未开始（Busy / 异常）：DISPATCHING -&gt; READY + revision+1 + 按延迟 reschedule MODEL Work。
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
              ModelInvocation model = tx.lockModelInvocation(claim.target().id()).orElse(null);
              if (model == null || tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (model.status() != ModelInvocationStatus.DISPATCHING
                  || model.attempt() != dispatched.attempt()) {
                return false;
              }
              tx.updateModelInvocation(model.dispatchBusy(now));
              tx.updateThread(thread.touchRevision(now));
              tx.rescheduleWork(claim, now, now.plus(delay));
              return true;
            }));
  }

  /** admission 明确拒绝：rejectDispatch FAILED + revision+1 + 请求 THREAD Work + complete MODEL Work。 */
  private boolean rejectDispatch(
      ClaimedWork claim, Prepare.Dispatched dispatched, ModelInvocationError error) {
    return terminalDispatch(claim, dispatched, (model, now) -> model.rejectDispatch(error, now));
  }

  /**
   * admission 不确定：unknown UNKNOWN（attempt+1）+ revision+1 + 请求 THREAD Work + complete MODEL Work。
   */
  private boolean unknownDispatch(
      ClaimedWork claim, Prepare.Dispatched dispatched, ModelInvocationError error) {
    return terminalDispatch(claim, dispatched, (model, now) -> model.unknown(error, now));
  }

  private boolean terminalDispatch(
      ClaimedWork claim,
      Prepare.Dispatched dispatched,
      BiFunction<ModelInvocation, Instant, ModelInvocation> transition) {
    Instant now = clock.instant();
    return Boolean.TRUE.equals(
        store.transaction(
            tx -> {
              ThreadState thread = tx.lockThread(dispatched.threadId()).orElse(null);
              if (thread == null) {
                return false;
              }
              ModelInvocation model = tx.lockModelInvocation(claim.target().id()).orElse(null);
              if (model == null) {
                return false;
              }
              tx.lockWork(new WorkTarget(WorkTargetType.THREAD, dispatched.threadId()));
              if (tx.lockClaimedWork(claim, now).isEmpty()) {
                return false;
              }
              if (model.status() != ModelInvocationStatus.DISPATCHING
                  || model.attempt() != dispatched.attempt()) {
                return false;
              }
              tx.updateModelInvocation(transition.apply(model, now));
              tx.updateThread(thread.touchRevision(now));
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, dispatched.threadId()), now);
              tx.completeWork(claim, now);
              return true;
            }));
  }

  private void release(ModelExecution execution) {
    executions.remove(execution.invocationId(), execution);
  }

  private sealed interface Prepare permits Prepare.Lost, Prepare.Dispatched, Prepare.Terminated {

    record Lost() implements Prepare {}

    record Dispatched(long threadId, int attempt, ModelInvocationRequest request)
        implements Prepare {}

    record Terminated() implements Prepare {}
  }
}
