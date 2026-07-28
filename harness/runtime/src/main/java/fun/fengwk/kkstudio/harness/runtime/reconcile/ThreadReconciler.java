package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.execution.Failure;
import fun.fengwk.kkstudio.harness.runtime.execution.StepResult;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread Reconciler：一次 activation 内收敛 durable facts 并返回 {@link StepResult}。
 *
 * <p>它不是常驻 Java 线程。用户 Input、Tool 终态、Interaction 与 Model 完成先在各自事务中提交 durable facts，再于提交后 best-effort
 * 发出 activation 信号；HTTP/SSE/Provider 回调本身都不是待执行工作队列。跨节点单飞由数据库 processor lease + fencing token
 * 保证；丢失的 kick 由 runnable 扫描 / recovery 补偿。
 *
 * <p>严格按以下优先级推进 owned Thread，之间用 bounded step loop 防止死循环：
 *
 * <ol>
 *   <li>apply terminal ModelInvocation；
 *   <li>apply terminal Tool sibling batch；
 *   <li>atomic suspend/recheck snapshot 中记录的 expected durable blocker；
 *   <li>create ModelInvocation-and-release 偿还既有 response debt；
 *   <li>harvest 一个 TURN_BOUNDARY；
 *   <li>atomic quiesce/recheck。
 * </ol>
 *
 * <p>消息边界产生的 ModelInvocation 必须在任何后续 queued 配置或 message 被 harvest 之前创建；这是步骤 4 排在步骤 5 之前的不变量。{@link
 * StepResult.Suspended} 仅由 suspend 与 ModelInvocation creation 两个成功路径返回，lease 已在事务内释放。{@link
 * SuspendOutcome#WORK_AVAILABLE} 与 {@link QuiesceOutcome#WORK_AVAILABLE} 在不释放 lease 的前提下继续循环。Typed
 * failure 仅 ModelInvocation creation 保留，其余事务的意外错误经 best-effort release 后重新抛出。
 */
public final class ThreadReconciler {

  /**
   * 单次 reconcile 最多执行 16 轮“续租、读取 snapshot、推进一个状态转换”。
   *
   * <p>一次 Tool sibling 批次无论包含多少个 ToolInvocation，都由一个状态转换整体 apply，只计一轮。达到上限只会结束本次 reconcile 并
   * best-effort 释放 lease，不等于 Stop Thread；该限制用于阻止状态机异常时长期占用 worker。
   */
  public static final int DEFAULT_MAX_STEPS = 16;

  private static final ReloadSnapshot RELOAD_SNAPSHOT = new ReloadSnapshot();

  private final ThreadReconcileTransactions transactions;
  private final Clock clock;
  private final int maxSteps;

  public ThreadReconciler(ThreadReconcileTransactions transactions) {
    this(transactions, Clock.systemUTC(), DEFAULT_MAX_STEPS);
  }

  public ThreadReconciler(ThreadReconcileTransactions transactions, Clock clock) {
    this(transactions, clock, DEFAULT_MAX_STEPS);
  }

  public ThreadReconciler(ThreadReconcileTransactions transactions, Clock clock, int maxSteps) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (maxSteps <= 0) {
      throw new IllegalArgumentException("maxSteps must be positive");
    }
    this.maxSteps = maxSteps;
  }

  /**
   * 执行一次 activation；不阻塞、不创建 Future、不回调外部线程。每次 claim / renew / load / mutation / release 都从注入的
   * {@link Clock} 读取最新 {@link Instant}，避免使用一个固定的 {@code now} 跨多次 mutation 导致 lease 不能续租。
   *
   * @param threadId 目标 Thread
   * @param processorToken 调用方随机生成的 fencing token；非 blank
   */
  public StepResult reconcile(long threadId, String processorToken) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (processorToken == null || processorToken.isBlank()) {
      throw new IllegalArgumentException("processorToken must not be blank");
    }

    Optional<ThreadOwnership> claimed =
        transactions.claim(threadId, processorToken, clock.instant());
    if (claimed.isEmpty()) {
      return new StepResult.LostOwnership();
    }
    ThreadOwnership ownership = claimed.get();
    try {
      requireClaimMatchesRequest(ownership, threadId, processorToken);
      return advanceOwnedThread(ownership);
    } catch (RuntimeException unexpected) {
      bestEffortRelease(ownership);
      throw unexpected;
    }
  }

  /**
   * 在已 claim 的 ownership 下，连续推进当前无需等待外部 I/O 的 durable 工作。
   *
   * <p>循环本身只负责续租、加载最新 snapshot，并根据一步执行结果决定“重新读取”还是“结束本次 reconcile”。具体状态转换及其正确性边界由 {@link
   * #advanceOneStep(ThreadOwnership, ThreadReconcileSnapshot)} 和各命名方法表达。
   *
   * <p>退出点只有四类：已稳定无工作（Quiescent）、需要等待外部 continuation（Suspended）、失去数据库所有权
   * （LostOwnership）、或检测到状态机无法收敛（Failed）。
   */
  private StepResult advanceOwnedThread(ThreadOwnership ownership) {
    for (int step = 1; step <= maxSteps; step++) {
      Optional<ThreadReconcileSnapshot> snapshot = renewAndLoadSnapshot(ownership);
      if (snapshot.isEmpty()) {
        return new StepResult.LostOwnership();
      }

      IterationOutcome outcome = advanceOneStep(ownership, snapshot.get());
      switch (outcome) {
        case ReloadSnapshot ignored -> {
          // 当前事务已推进状态，或退出前 recheck 发现新工作；旧 snapshot 已失效。
          continue;
        }
        case FinishReconcile finished -> {
          return finished.result();
        }
      }
    }

    // 达到上限代表每轮都“有进展”却始终无法稳定，通常是状态机或 transaction 实现错误。
    // 主动释放 lease，让 recovery 或后续 reconcile 处理，而不是无限占住这条 Thread。
    bestEffortRelease(ownership);
    return new StepResult.Failed(
        new Failure(
            "RECONCILE_STEP_LIMIT",
            "reconcile step limit exceeded for thread " + ownership.threadId()));
  }

  /**
   * 续租并读取下一步决策所需的最新 snapshot。
   *
   * <p>返回 empty 表示 ownership 已失效，调用方必须立即结束。snapshot 是乐观读取：它只用于选择候选动作；真正的 mutation 仍会在独立事务中重新锁定
   * Thread，并校验 epoch、processor token、lease 和动作前置条件。
   */
  private Optional<ThreadReconcileSnapshot> renewAndLoadSnapshot(ThreadOwnership ownership) {
    if (!transactions.renew(ownership, clock.instant())) {
      return Optional.empty();
    }
    Optional<ThreadReconcileSnapshot> snapshot =
        transactions.loadOwnedSnapshot(ownership, clock.instant());
    snapshot.ifPresent(current -> requireSnapshotMatchesOwnership(current, ownership));
    return snapshot;
  }

  /**
   * 按固定优先级选择并执行一个状态转换。
   *
   * <p>优先级必须集中保留在本方法中，便于审计因果顺序；各分支只把具体事务及 outcome 映射委托给命名方法：
   *
   * <ol>
   *   <li>materialize terminal Model；
   *   <li>materialize terminal Tool sibling batch；
   *   <li>suspend/recheck 未完成的 Model/Tool blocker；
   *   <li>为既有 response debt 创建 ModelInvocation；
   *   <li>harvest 一个 TURN_BOUNDARY；
   *   <li>quiesce/recheck。
   * </ol>
   */
  private IterationOutcome advanceOneStep(
      ThreadOwnership ownership, ThreadReconcileSnapshot snapshot) {
    if (snapshot.terminalModelInvocationId().isPresent()) {
      return applyTerminalModel(ownership, snapshot.terminalModelInvocationId().get());
    }
    if (snapshot.readyToolAssistantEntryId().isPresent()) {
      return applyTerminalToolResults(ownership, snapshot.readyToolAssistantEntryId().get());
    }
    if (snapshot.blockerContinuation().isPresent()) {
      return suspendForBlocker(ownership, snapshot.blockerContinuation().get());
    }
    if (snapshot.modelInvocationPlan().isPresent()) {
      return createModelInvocation(ownership, snapshot.modelInvocationPlan().get());
    }

    Optional<TurnBoundary> boundary =
        TurnBoundarySelector.select(ownership, snapshot.queuedInputs());
    if (boundary.isPresent()) {
      return harvestBoundary(ownership, boundary.get());
    }
    return quiesce(ownership);
  }

  /**
   * 将已结束的 ModelInvocation 原子写成 Assistant/AssistantError Entry，并按需创建 ToolInvocation。
   *
   * <p>snapshot 与本事务之间发生变化是允许的：transaction 会重新验证 ownership、当前 head 与目标 ModelInvocation。成功后 head 等
   * durable state 已变化，必须重新读取 snapshot；验证失败则结束为 LostOwnership。
   */
  private IterationOutcome applyTerminalModel(ThreadOwnership ownership, long modelInvocationId) {
    ApplyOutcome outcome =
        transactions.applyTerminalModel(ownership, modelInvocationId, clock.instant());
    return reloadOrLostOwnership(outcome);
  }

  /**
   * 将当前 Assistant 下全部已结束的 Tool sibling 按 ordinal 原子写成连续 Tool Result Entry。
   *
   * <p>整个 sibling 批次是一个状态转换，因此无论包含多少次 Tool 调用，都只消耗一轮 reconcile step。成功后重新读取 snapshot，以新 head
   * 判断是否产生下一次 response debt。
   */
  private IterationOutcome applyTerminalToolResults(
      ThreadOwnership ownership, long assistantEntryId) {
    ApplyOutcome outcome =
        transactions.applyTerminalToolResults(ownership, assistantEntryId, clock.instant());
    return reloadOrLostOwnership(outcome);
  }

  /**
   * 尝试因未完成的 Model/Tool blocker 暂停 Thread。
   *
   * <p>事务会在释放 lease 前重新检查 expected blocker：仍未完成时返回 Suspended；若 blocker 已变化或有新工作，则保留 lease 并要求重新读取
   * snapshot；ownership 失效时立即结束。
   */
  private IterationOutcome suspendForBlocker(
      ThreadOwnership ownership, ContinuationRef expectedBlocker) {
    SuspendOutcome outcome =
        transactions.suspendAndRecheck(ownership, expectedBlocker, clock.instant());
    return switch (outcome) {
      case SUSPENDED -> new FinishReconcile(new StepResult.Suspended(expectedBlocker));
      case WORK_AVAILABLE -> RELOAD_SNAPSHOT;
      case LOST_OWNERSHIP -> new FinishReconcile(new StepResult.LostOwnership());
    };
  }

  /**
   * 为当前 Entry Tree 已存在的 response debt 创建冻结的 ModelInvocation。
   *
   * <p>创建 Invocation 与释放 Thread lease 在一个事务内完成，因此成功后本次 reconcile 必须结束并等待 ModelWorker。该步骤排在 Input
   * harvest 前，避免后续消息污染当前消息对应的 ProviderRequest。
   */
  private IterationOutcome createModelInvocation(
      ThreadOwnership ownership, ModelInvocationPlan plan) {
    ModelCreationOutcome outcome =
        transactions.createModelInvocationAndRelease(ownership, plan, clock.instant());
    return switch (outcome) {
      case ModelCreationOutcome.Created created -> new FinishReconcile(
          new StepResult.Suspended(
              new ContinuationRef(ownership.threadTarget(), created.target())));
      case ModelCreationOutcome.LostOwnership ignored -> new FinishReconcile(
          new StepResult.LostOwnership());
      case ModelCreationOutcome.Failed failed -> {
        bestEffortRelease(ownership);
        yield new FinishReconcile(new StepResult.Failed(failed.failure()));
      }
    };
  }

  /**
   * 将一个 TURN_BOUNDARY 从 mailbox 原子写入 Entry Tree。
   *
   * <p>一个 boundary 包含连续配置和至多一条 message。成功后必须重新读取 snapshot，使该 message 产生的 response debt 优先于后续 queued
   * Input。
   */
  private IterationOutcome harvestBoundary(ThreadOwnership ownership, TurnBoundary boundary) {
    ApplyOutcome outcome = transactions.harvestBoundary(ownership, boundary, clock.instant());
    return reloadOrLostOwnership(outcome);
  }

  /**
   * 尝试确认 Thread 已无可推进工作并释放 lease。
   *
   * <p>事务会在释放前重新检查并发到达的事实：真正无工作时结束为 Quiescent；发现新工作时保留 lease 并重新读取 snapshot；ownership 失效时立即结束。
   */
  private IterationOutcome quiesce(ThreadOwnership ownership) {
    QuiesceOutcome outcome = transactions.quiesceAndRecheck(ownership, clock.instant());
    return switch (outcome) {
      case QUIESCENT -> new FinishReconcile(new StepResult.Quiescent());
      case WORK_AVAILABLE -> RELOAD_SNAPSHOT;
      case LOST_OWNERSHIP -> new FinishReconcile(new StepResult.LostOwnership());
    };
  }

  /** 映射 apply/harvest 的共同结果：成功 mutation 会使旧 snapshot 失效；fencing 失败则结束本次 reconcile。 */
  private static IterationOutcome reloadOrLostOwnership(ApplyOutcome outcome) {
    return switch (outcome) {
      case PROGRESSED -> RELOAD_SNAPSHOT;
      case LOST_OWNERSHIP -> new FinishReconcile(new StepResult.LostOwnership());
    };
  }

  private static void requireSnapshotMatchesOwnership(
      ThreadReconcileSnapshot snapshot, ThreadOwnership ownership) {
    if (!snapshot.ownership().equals(ownership)) {
      throw new IllegalStateException("snapshot ownership does not match claimed ownership");
    }
  }

  /** 单步执行后必须丢弃旧 snapshot，并在持有 ownership 的前提下开始下一轮。 */
  private record ReloadSnapshot() implements IterationOutcome {}

  /** 单步执行已经决定本次 reconcile 的最终结果，不得继续使用当前 ownership。 */
  private record FinishReconcile(StepResult result) implements IterationOutcome {
    private FinishReconcile {
      Objects.requireNonNull(result, "result");
    }
  }

  private sealed interface IterationOutcome permits ReloadSnapshot, FinishReconcile {}

  private static void requireClaimMatchesRequest(
      ThreadOwnership ownership, long threadId, String processorToken) {
    if (ownership.threadId() != threadId || !ownership.processorToken().equals(processorToken)) {
      throw new IllegalStateException("claim returned ownership for a different thread or token");
    }
  }

  private void bestEffortRelease(ThreadOwnership ownership) {
    try {
      transactions.bestEffortRelease(ownership, clock.instant());
    } catch (RuntimeException ignored) {
      // Cleanup must not mask the original failure.
    }
  }
}
