package fun.fengwk.kkstudio.harness.runtime.reconcile;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次激活内由 transaction 端口读取的 reconcile snapshot。
 *
 * <p>不可变；构造时强制以下不变量：
 *
 * <ul>
 *   <li>{@code ownership.threadId / executionEpoch / processorToken} 与 {@code thread.id /
 *       executionEpoch / processorLease.token} 完全一致；
 *   <li>terminal-unapplied ModelInvocation id 与 ready-to-apply Tool Assistant Entry id（如存在）必须为正；
 *   <li>durable blocker 引用（如存在）的 owner 必须等于当前 ownership 的 THREAD target，blocker kind 必须为
 *       MODEL_INVOCATION 或 TOOL_INVOCATION，不允许 THREAD；
 *   <li>{@link ModelInvocationPlan#sourceHeadEntryId()} 必须等于 {@code thread.headEntryId()}；
 *   <li>上述四项 primary debt/action 中至多一项存在；
 *   <li>queued Inputs 全部属于同 Thread、状态 QUEUED、sequence 严格递增。
 * </ul>
 */
public record ThreadReconcileSnapshot(
    ThreadOwnership ownership,
    HarnessThread thread,
    Optional<Long> terminalModelInvocationId,
    Optional<Long> readyToolAssistantEntryId,
    Optional<ContinuationRef> blockerContinuation,
    List<ThreadInput> queuedInputs,
    Optional<ModelInvocationPlan> modelInvocationPlan) {

  public ThreadReconcileSnapshot {
    ownership = Objects.requireNonNull(ownership, "ownership");
    thread = Objects.requireNonNull(thread, "thread");
    terminalModelInvocationId =
        Objects.requireNonNull(terminalModelInvocationId, "terminalModelInvocationId");
    readyToolAssistantEntryId =
        Objects.requireNonNull(readyToolAssistantEntryId, "readyToolAssistantEntryId");
    blockerContinuation = Objects.requireNonNull(blockerContinuation, "blockerContinuation");
    queuedInputs = List.copyOf(Objects.requireNonNull(queuedInputs, "queuedInputs"));
    modelInvocationPlan = Objects.requireNonNull(modelInvocationPlan, "modelInvocationPlan");

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

    requirePositiveOptional(terminalModelInvocationId, "terminalModelInvocationId");
    requirePositiveOptional(readyToolAssistantEntryId, "readyToolAssistantEntryId");
    requireValidBlockerOptional(blockerContinuation, ownership);
    requirePlanMatchesHeadOptional(modelInvocationPlan, thread);
    requireAtMostOnePrimary(
        terminalModelInvocationId,
        readyToolAssistantEntryId,
        blockerContinuation,
        modelInvocationPlan);
    requireQueuedInputsMonotonic(queuedInputs, ownership);
  }

  private static void requirePositiveOptional(Optional<Long> value, String name) {
    value.ifPresent(
        id -> {
          if (id <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
          }
        });
  }

  private static void requireValidBlockerOptional(
      Optional<ContinuationRef> value, ThreadOwnership ownership) {
    value.ifPresent(
        ref -> {
          if (!ref.owner().equals(ownership.threadTarget())) {
            throw new IllegalArgumentException("blocker owner must equal ownership THREAD target");
          }
          if (ref.blocker().kind() != ExecutionTargetKind.MODEL_INVOCATION
              && ref.blocker().kind() != ExecutionTargetKind.TOOL_INVOCATION) {
            throw new IllegalArgumentException(
                "blocker kind must be MODEL_INVOCATION or TOOL_INVOCATION, got "
                    + ref.blocker().kind());
          }
        });
  }

  private static void requirePlanMatchesHeadOptional(
      Optional<ModelInvocationPlan> value, HarnessThread thread) {
    value.ifPresent(
        plan -> {
          if (plan.sourceHeadEntryId() != thread.requireHeadEntryId()) {
            throw new IllegalArgumentException(
                "modelInvocationPlan.sourceHeadEntryId must equal thread.headEntryId");
          }
        });
  }

  private static void requireAtMostOnePrimary(
      Optional<Long> terminalModelId,
      Optional<Long> readyToolAssistantId,
      Optional<ContinuationRef> blocker,
      Optional<ModelInvocationPlan> plan) {
    int primaryCount = 0;
    if (terminalModelId.isPresent()) primaryCount++;
    if (readyToolAssistantId.isPresent()) primaryCount++;
    if (blocker.isPresent()) primaryCount++;
    if (plan.isPresent()) primaryCount++;
    if (primaryCount > 1) {
      throw new IllegalArgumentException(
          "at most one of terminalModelInvocationId, readyToolAssistantEntryId,"
              + " blockerContinuation, modelInvocationPlan may be present");
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
}
