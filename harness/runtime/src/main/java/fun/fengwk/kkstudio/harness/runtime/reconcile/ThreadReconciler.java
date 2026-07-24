package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.kernel.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.kernel.execution.Failure;
import fun.fengwk.kkstudio.harness.kernel.execution.StepResult;

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

  /** 单次 activation 的最大循环次数；超出时视为编程错误并返回 Failed。 */
  public static final int DEFAULT_MAX_STEPS = 16;

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
      return runActivation(ownership);
    } catch (RuntimeException unexpected) {
      bestEffortRelease(ownership);
      throw unexpected;
    }
  }

  private StepResult runActivation(ThreadOwnership ownership) {
    int safety = 0;
    while (safety++ < maxSteps) {
      Instant renewInstant = clock.instant();
      if (!transactions.renew(ownership, renewInstant)) {
        return new StepResult.LostOwnership();
      }

      Optional<ThreadReconcileSnapshot> snapshotOpt =
          transactions.loadOwnedSnapshot(ownership, clock.instant());
      if (snapshotOpt.isEmpty()) {
        return new StepResult.LostOwnership();
      }
      ThreadReconcileSnapshot snapshot = snapshotOpt.get();
      if (!snapshot.ownership().equals(ownership)) {
        throw new IllegalStateException("snapshot ownership does not match claimed ownership");
      }

      if (snapshot.terminalModelInvocationId().isPresent()) {
        ApplyOutcome outcome =
            transactions.applyTerminalModel(
                ownership, snapshot.terminalModelInvocationId().get(), clock.instant());
        switch (outcome) {
          case PROGRESSED -> {}
          case LOST_OWNERSHIP -> {
            return new StepResult.LostOwnership();
          }
        }
        continue;
      }

      if (snapshot.readyToolAssistantEntryId().isPresent()) {
        ApplyOutcome outcome =
            transactions.applyTerminalToolResults(
                ownership, snapshot.readyToolAssistantEntryId().get(), clock.instant());
        switch (outcome) {
          case PROGRESSED -> {}
          case LOST_OWNERSHIP -> {
            return new StepResult.LostOwnership();
          }
        }
        continue;
      }

      if (snapshot.blockerContinuation().isPresent()) {
        ContinuationRef expected = snapshot.blockerContinuation().get();
        SuspendOutcome outcome =
            transactions.suspendAndRecheck(ownership, expected, clock.instant());
        switch (outcome) {
          case SUSPENDED -> {
            return new StepResult.Suspended(expected);
          }
          case WORK_AVAILABLE -> {
            continue;
          }
          case LOST_OWNERSHIP -> {
            return new StepResult.LostOwnership();
          }
        }
      }

      if (snapshot.modelInvocationPlan().isPresent()) {
        ModelCreationOutcome outcome =
            transactions.createModelInvocationAndRelease(
                ownership, snapshot.modelInvocationPlan().get(), clock.instant());
        return switch (outcome) {
          case ModelCreationOutcome.Created created -> new StepResult.Suspended(
              new ContinuationRef(ownership.threadTarget(), created.target()));
          case ModelCreationOutcome.LostOwnership ignored -> new StepResult.LostOwnership();
          case ModelCreationOutcome.Failed failed -> {
            bestEffortRelease(ownership);
            yield new StepResult.Failed(failed.failure());
          }
        };
      }

      Optional<TurnBoundary> boundary =
          TurnBoundarySelector.select(ownership, snapshot.queuedInputs());
      if (boundary.isPresent()) {
        ApplyOutcome outcome =
            transactions.harvestBoundary(ownership, boundary.get(), clock.instant());
        switch (outcome) {
          case PROGRESSED -> {}
          case LOST_OWNERSHIP -> {
            return new StepResult.LostOwnership();
          }
        }
        continue;
      }

      QuiesceOutcome outcome = transactions.quiesceAndRecheck(ownership, clock.instant());
      switch (outcome) {
        case QUIESCENT -> {
          return new StepResult.Quiescent();
        }
        case WORK_AVAILABLE -> {
          continue;
        }
        case LOST_OWNERSHIP -> {
          return new StepResult.LostOwnership();
        }
      }
    }

    bestEffortRelease(ownership);
    return new StepResult.Failed(
        new Failure(
            "RECONCILE_STEP_LIMIT",
            "reconcile step limit exceeded for thread " + ownership.threadId()));
  }

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
