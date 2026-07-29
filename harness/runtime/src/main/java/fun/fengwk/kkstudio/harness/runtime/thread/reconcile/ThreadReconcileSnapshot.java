package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 activation（推进一条对话的一次处理任务）读取的 reconcile 决策快照。
 *
 * <p>它是一次性决策投影（只为本轮选出下一步所需的持久化事实），结构为 {@code ownership + thread + primaryWork +
 * queuedInputs}。它不是长期有效的锁，也不是完整数据库快照；实际 mutation 会在独立事务中重新锁定 Thread，并复查 epoch、 processor
 * token、lease 和动作前置条件。
 *
 * <p>其中 {@code primaryWork} 是零或一个主要动作（当前回合必须先完成的工作），{@code queuedInputs} 是尚未接收的后续 mailbox
 * 输入。事务适配器按固定顺序选择主要动作：
 *
 * <ol>
 *   <li>{@link PrimaryWork.ApplyTerminalModel}；
 *   <li>{@link PrimaryWork.ApplyTerminalToolBatch}；
 *   <li>{@link PrimaryWork.SuspendForBlocker}；
 *   <li>{@link PrimaryWork.CreateModelInvocation}。
 * </ol>
 *
 * <p>只有 {@code primaryWork} 为空时，Reconciler 才会从 {@code queuedInputs} 中 harvest 下一个 TURN_BOUNDARY 或执行
 * quiesce。
 *
 * <p>不可变；构造时强制以下不变量：
 *
 * <ul>
 *   <li>{@code ownership.threadId / executionEpoch / processorToken} 与 {@code thread.id /
 *       executionEpoch / processorLease.token} 完全一致；
 *   <li>{@code Optional<PrimaryWork>} 的结构保证主要动作至多一个，且其数据与 ownership/thread 的相对关系一致；
 *   <li>queued Inputs 全部属于同 Thread、状态 QUEUED、sequence 严格递增。
 * </ul>
 */
public record ThreadReconcileSnapshot(
    ThreadOwnership ownership,
    HarnessThread thread,
    Optional<PrimaryWork> primaryWork,
    List<ThreadInput> queuedInputs) {

  public ThreadReconcileSnapshot {
    ownership = Objects.requireNonNull(ownership, "ownership");
    thread = Objects.requireNonNull(thread, "thread");
    primaryWork = Objects.requireNonNull(primaryWork, "primaryWork");
    queuedInputs = List.copyOf(Objects.requireNonNull(queuedInputs, "queuedInputs"));

    if (thread.id() != ownership.threadId()) {
      throw new IllegalArgumentException("thread.id must equal ownership.threadId");
    }
    if (!thread.isBound()) {
      throw new IllegalArgumentException("reconcile snapshot requires a bound thread");
    }
    thread.requireHeadEntryId();
    if (thread.executionEpoch() != ownership.executionEpoch()) {
      throw new IllegalArgumentException(
          "thread.executionEpoch must equal ownership.executionEpoch");
    }
    if (thread.processorLease() == null
        || !ownership.processorToken().equals(thread.processorLease().token())) {
      throw new IllegalArgumentException(
          "thread.processorLease.token must equal ownership.processorToken");
    }

    if (primaryWork.isPresent()) {
      validatePrimaryWork(primaryWork.get(), ownership, thread);
    }
    requireQueuedInputsMonotonic(queuedInputs, ownership);
  }

  /**
   * 校验主要动作与 ownership/thread 的相对关系。
   *
   * <p>各变体的基础值在自己的 compact 构造器中校验。这里仅校验跨对象约束：blocker 必须属于当前 Thread，plan 必须基于当前 head。
   */
  private static void validatePrimaryWork(
      PrimaryWork work, ThreadOwnership ownership, HarnessThread thread) {
    switch (work) {
      case PrimaryWork.ApplyTerminalModel ignored -> {}
      case PrimaryWork.ApplyTerminalToolBatch ignored -> {}
      case PrimaryWork.SuspendForBlocker suspension -> {
        ContinuationRef blocker = suspension.blocker();
        if (!blocker.owner().equals(ownership.threadTarget())) {
          throw new IllegalArgumentException("blocker owner must equal ownership THREAD target");
        }
        if (blocker.blocker().kind() != ExecutionTargetKind.MODEL_INVOCATION
            && blocker.blocker().kind() != ExecutionTargetKind.TOOL_INVOCATION) {
          throw new IllegalArgumentException(
              "blocker kind must be MODEL_INVOCATION or TOOL_INVOCATION, got "
                  + blocker.blocker().kind());
        }
      }
      case PrimaryWork.CreateModelInvocation creation -> {
        ModelInvocationPlan plan = creation.plan();
        if (plan.sourceHeadEntryId() != thread.requireHeadEntryId()) {
          throw new IllegalArgumentException(
              "modelInvocationPlan.sourceHeadEntryId must equal thread.headEntryId");
        }
      }
    }
  }

  private static void requireQueuedInputsMonotonic(
      List<ThreadInput> inputs, ThreadOwnership ownership) {
    long previousSequence = 0L;
    for (ThreadInput input : inputs) {
      if (input.threadId() != ownership.threadId()) {
        throw new IllegalArgumentException("queued input threadId does not match ownership");
      }
      if (input.status() != InputStatus.QUEUED) {
        throw new IllegalArgumentException("queuedInputs must all be QUEUED");
      }
      if (input.sequence() <= previousSequence) {
        throw new IllegalArgumentException("queuedInputs must be strictly increasing by sequence");
      }
      previousSequence = input.sequence();
    }
  }

  /**
   * Snapshot 中的主要动作（本轮优先执行的唯一工作）。
   *
   * <p>密封类型让四种互斥动作的有效载荷一目了然，避免以多个 Optional 表达可能混杂的状态。基础值在各变体的 compact 构造器中校验；依赖当前 ownership 或 head
   * 的约束由 {@link ThreadReconcileSnapshot} 校验。
   */
  public sealed interface PrimaryWork {

    /**
     * 应用已结束（terminal）的 ModelInvocation：将终态写入 Assistant/AssistantError Entry，按需创建 ToolInvocation，推进
     * head。
     *
     * @param modelInvocationId 已结束的 ModelInvocation 主键，必须为正
     */
    record ApplyTerminalModel(long modelInvocationId) implements PrimaryWork {
      public ApplyTerminalModel {
        if (modelInvocationId <= 0) {
          throw new IllegalArgumentException("modelInvocationId must be positive");
        }
      }
    }

    /**
     * 应用当前 Assistant 下全部已结束的 Tool sibling batch：按 ordinal 写入连续 Tool Result Entry，推进 head。
     *
     * @param assistantEntryId 当前 Assistant Entry 的 id，必须为正
     */
    record ApplyTerminalToolBatch(long assistantEntryId) implements PrimaryWork {
      public ApplyTerminalToolBatch {
        if (assistantEntryId <= 0) {
          throw new IllegalArgumentException("assistantEntryId must be positive");
        }
      }
    }

    /**
     * 因未完成的 Model/Tool blocker 暂停 Thread，释放 lease 等待 continuation。
     *
     * @param blocker 预期的 durable blocker 引用；owner 必须等于当前 ownership 的 THREAD target，blocker kind 必须为
     *     MODEL_INVOCATION 或 TOOL_INVOCATION
     */
    record SuspendForBlocker(ContinuationRef blocker) implements PrimaryWork {
      public SuspendForBlocker {
        Objects.requireNonNull(blocker, "blocker");
      }
    }

    /**
     * 为既有 response debt 创建冻结的 ModelInvocation，并在同事务内释放 Thread lease。
     *
     * @param plan 冻结的模型调用计划；{@link ModelInvocationPlan#sourceHeadEntryId()} 必须等于当前 thread head
     */
    record CreateModelInvocation(ModelInvocationPlan plan) implements PrimaryWork {
      public CreateModelInvocation {
        Objects.requireNonNull(plan, "plan");
      }
    }
  }
}
