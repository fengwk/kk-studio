package fun.fengwk.kkstudio.harness.api.tool;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;

/**
 * {@link ToolInvocation} 的 durable 不变量与合法转换校验。
 *
 * <p>本类是 {@code ToolInvocation} 的唯一校验入口：构造器校验字段与状态的组合，{@code validateTransition} 校验已存行到下一行的
 * 合法移动。分离出来只为让 record 本身只表达状态与转换，判断条件、顺序与文案保持原样。
 */
final class ToolInvocationValidation {

  private ToolInvocationValidation() {}

  /** 校验字段组合与给定 status 相容。 */
  static void validateFields(
      ToolInvocationStatus status,
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      ToolCall call,
      ToolInputReceipt inputReceipt) {
    switch (status) {
      case WAITING_APPROVAL -> requireWaitingApproval(
          attempt, approval, result, error, inputReceipt);
      case WAITING_INPUT -> requireWaitingInput(attempt, approval, result, error, inputReceipt);
      case READY -> {
        requireNoTerminalFacts(status, result, error);
        requireNoInputReceipt(status, inputReceipt);
        if (approval != null
            && (approval.isUndecided() || approval.decision() == ToolApprovalDecision.DENIED)) {
          throw new IllegalArgumentException(
              "READY must not carry an undecided or denied approval");
        }
      }
      case RUNNING -> {
        if (attempt <= 0) {
          throw new IllegalArgumentException("RUNNING requires a positive attempt");
        }
        requireNoTerminalFacts(status, result, error);
        requireNoInputReceipt(status, inputReceipt);
        requireCompletedPreflightApproval(status, approval);
      }
      case SUCCEEDED -> requireSucceeded(attempt, approval, result, error, call, inputReceipt);
      case FAILED -> {
        if (approval != null && approval.isUndecided()) {
          throw new IllegalArgumentException("FAILED must not carry an undecided approval");
        }
        if (error == null) {
          throw new IllegalArgumentException("FAILED requires an error");
        }
        if (result != null) {
          throw new IllegalArgumentException("FAILED must not carry a result");
        }
        requireNoInputReceipt(status, inputReceipt);
      }
      case UNKNOWN -> requireUnknown(attempt, approval, result, error, inputReceipt);
      case CANCELLED -> {
        if (error == null) {
          throw new IllegalArgumentException(status + " requires an error");
        }
        if (result != null) {
          throw new IllegalArgumentException(status + " must not carry a result");
        }
        requireNoInputReceipt(status, inputReceipt);
      }
    }
  }

  private static void requireWaitingApproval(
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      ToolInputReceipt inputReceipt) {
    if (approval == null || !approval.required() || !approval.isUndecided()) {
      throw new IllegalArgumentException("WAITING_APPROVAL requires a required undecided approval");
    }
    if (attempt != 0) {
      throw new IllegalArgumentException("WAITING_APPROVAL requires attempt 0");
    }
    requireNoTerminalFacts(ToolInvocationStatus.WAITING_APPROVAL, result, error);
    requireNoInputReceipt(ToolInvocationStatus.WAITING_APPROVAL, inputReceipt);
  }

  private static void requireWaitingInput(
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      ToolInputReceipt inputReceipt) {
    if (attempt != 0) {
      throw new IllegalArgumentException("WAITING_INPUT requires attempt 0");
    }
    if (approval != null) {
      throw new IllegalArgumentException(
          "WAITING_INPUT must not carry an approval: a questionnaire never authorizes a tool");
    }
    requireNoTerminalFacts(ToolInvocationStatus.WAITING_INPUT, result, error);
    requireNoInputReceipt(ToolInvocationStatus.WAITING_INPUT, inputReceipt);
  }

  private static void requireSucceeded(
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      ToolCall call,
      ToolInputReceipt inputReceipt) {
    ToolInvocationStatus status = ToolInvocationStatus.SUCCEEDED;
    if (result == null) {
      throw new IllegalArgumentException("SUCCEEDED requires a result");
    }
    if (!result.toolCallId().equals(call.id())) {
      throw new IllegalArgumentException(
          "SUCCEEDED result toolCallId must match the invocation call");
    }
    requireStableSuccessResult(result);
    if (error != null) {
      throw new IllegalArgumentException("SUCCEEDED must not carry an error");
    }
    if (inputReceipt == null) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("SUCCEEDED requires a positive attempt");
      }
      requireCompletedPreflightApproval(status, approval);
    } else {
      // 人工作答不是执行：attempt 保持 0，且不经过任何 tool approval 门禁。
      if (attempt != 0) {
        throw new IllegalArgumentException("an answered invocation must keep attempt 0");
      }
      if (approval != null) {
        throw new IllegalArgumentException("an answered invocation must not carry an approval");
      }
    }
  }

  private static void requireUnknown(
      int attempt,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      ToolInputReceipt inputReceipt) {
    ToolInvocationStatus status = ToolInvocationStatus.UNKNOWN;
    if (attempt <= 0) {
      throw new IllegalArgumentException("UNKNOWN requires a positive attempt");
    }
    if (error == null) {
      throw new IllegalArgumentException("UNKNOWN requires an error");
    }
    if (result != null) {
      throw new IllegalArgumentException("UNKNOWN must not carry a result");
    }
    requireCompletedPreflightApproval(status, approval);
    requireNoInputReceipt(status, inputReceipt);
  }

  /** busy/binding 的 durable 约束：binding 只在 immediate FAILED（attempt 0）槽位可空。 */
  static void validateBindingConstraint(
      ToolInvocationStatus status, int attempt, ToolCall call, ToolBinding binding) {
    if (binding == null) {
      if (status != ToolInvocationStatus.FAILED || attempt != 0) {
        throw new IllegalArgumentException(
            "binding is only nullable on immediate FAILED invocations (attempt 0)");
      }
      return;
    }
    if (!binding.descriptor().name().equals(call.toolName())) {
      throw new IllegalArgumentException("binding descriptor name must match the call toolName");
    }
    if (status != ToolInvocationStatus.FAILED || attempt != 0) {
      try {
        call.validateFor(binding.descriptor());
      } catch (IllegalArgumentException schemaFailure) {
        throw new IllegalArgumentException(
            "call arguments must conform to the binding tool schema", schemaFailure);
      }
    }
  }

  /** SUCCEEDED 结果必须已稳定：不得含 inline binary，Resource 只能是当前 Session 持有的 resource 引用。 */
  private static void requireStableSuccessResult(ToolResult result) {
    for (ResultContent content : result.contents()) {
      if (content instanceof BinaryResultContent) {
        throw new IllegalArgumentException("SUCCEEDED result must not carry inline binary content");
      }
      if (content instanceof ResourceResultContent resource
          && resource.resource().sessionBlobId() == null) {
        throw new IllegalArgumentException(
            "SUCCEEDED result resources must be stable session resources");
      }
    }
  }

  private static void requireCompletedPreflightApproval(
      ToolInvocationStatus status, ToolApproval approval) {
    if (approval == null
        || (approval.required() && approval.decision() != ToolApprovalDecision.ALLOWED)) {
      throw new IllegalArgumentException(status + " requires a completed preflight approval");
    }
  }

  /** 输入回执是“回答已被接受”的 durable 事实，只允许与 SUCCEEDED + result 同时存在。 */
  private static void requireNoInputReceipt(
      ToolInvocationStatus status, ToolInputReceipt inputReceipt) {
    if (inputReceipt != null) {
      throw new IllegalArgumentException(
          status + " must not carry an input receipt: only an answered success records one");
    }
  }

  private static void requireNoTerminalFacts(
      ToolInvocationStatus status, ToolResult result, ToolInvocationError error) {
    if (result != null || error != null) {
      throw new IllegalArgumentException(status + " must not carry terminal facts");
    }
  }

  /** 校验 {@code next} 是已存储行 {@code stored} 的合法转换。判断顺序与拒绝条件与拆分前一致。 */
  static void validateTransition(ToolInvocation stored, ToolInvocation next) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(next, "next");
    if (stored.equals(next)) {
      return;
    }
    requireStableIdentity(stored, next);
    int attemptDelta = next.attempt() - stored.attempt();
    if (attemptDelta < 0) {
      throw new IllegalArgumentException("attempt must not regress");
    }
    requireLegalStatusMove(stored.status(), next.status());
    requireAttemptDelta(stored, next, attemptDelta);
    requireApprovalRules(stored, next);
    requireInputReceiptRules(stored, next);
    if (stored.status().isTerminal()) {
      requireTerminalImmutability(stored, next);
    }
  }

  private static void requireStableIdentity(ToolInvocation stored, ToolInvocation next) {
    if (!stored.id().equals(next.id())
        || !stored.threadId().equals(next.threadId())
        || !stored.turnStartEntryId().equals(next.turnStartEntryId())
        || !stored.assistantEntryId().equals(next.assistantEntryId())
        || stored.callIndex() != next.callIndex()
        || !stored.call().equals(next.call())
        || !Objects.equals(stored.binding(), next.binding())
        || !stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException(
          "tool invocation identity"
              + " (id/thread/turnStart/assistantEntry/callIndex/call/binding/createdAt) must not"
              + " change");
    }
  }

  private static void requireLegalStatusMove(
      ToolInvocationStatus stored, ToolInvocationStatus next) {
    boolean allowed =
        switch (stored) {
          case WAITING_APPROVAL -> next == ToolInvocationStatus.WAITING_APPROVAL
              || next == ToolInvocationStatus.READY
              || next == ToolInvocationStatus.FAILED
              || next == ToolInvocationStatus.CANCELLED;
          case WAITING_INPUT -> next == ToolInvocationStatus.WAITING_INPUT
              || next == ToolInvocationStatus.SUCCEEDED
              || next == ToolInvocationStatus.CANCELLED;
          case READY -> next == ToolInvocationStatus.READY
              || next == ToolInvocationStatus.WAITING_APPROVAL
              || next == ToolInvocationStatus.WAITING_INPUT
              || next == ToolInvocationStatus.RUNNING
              || next == ToolInvocationStatus.FAILED
              || next == ToolInvocationStatus.CANCELLED;
          case RUNNING -> next == ToolInvocationStatus.RUNNING
              || next == ToolInvocationStatus.READY
              || next == ToolInvocationStatus.SUCCEEDED
              || next == ToolInvocationStatus.FAILED
              || next == ToolInvocationStatus.CANCELLED
              || next == ToolInvocationStatus.UNKNOWN;
          case SUCCEEDED, FAILED, CANCELLED, UNKNOWN -> next == stored;
        };
    if (!allowed) {
      throw new IllegalArgumentException(
          "illegal tool invocation status transition " + stored + " -> " + next);
    }
  }

  private static void requireAttemptDelta(
      ToolInvocation stored, ToolInvocation next, int attemptDelta) {
    boolean start =
        stored.status() == ToolInvocationStatus.READY
            && next.status() == ToolInvocationStatus.RUNNING;
    if (start) {
      if (attemptDelta != 1) {
        throw new IllegalArgumentException("a start must advance attempt by exactly one");
      }
    } else if (attemptDelta != 0) {
      throw new IllegalArgumentException("attempt must not change on this transition");
    }
  }

  /**
   * approval 的可变范围：未决定 → 决定（保留 request time）或精确 replay；已决定 / non-required 不可变。
   *
   * <p>引入（stored 为 null）只允许 {@code not-required} 进入 READY-&gt;READY，或 required undecided request 进入
   * READY-&gt;WAITING_APPROVAL。决定后的目标状态必须匹配：ALLOWED 恢复 READY，DENIED 终止 FAILED。WAITING_APPROVAL
   * 行在保留完全相同的 undecided approval 时被 Stop 为 CANCELLED 保持合法。
   */
  private static void requireApprovalRules(ToolInvocation stored, ToolInvocation next) {
    ToolApproval storedApproval = stored.approval();
    ToolApproval nextApproval = next.approval();
    if (storedApproval == null) {
      requireIntroductionRules(stored, next, nextApproval);
      return;
    }
    if (storedApproval.isUndecided()) {
      if (requireDecisionRules(stored, next, storedApproval, nextApproval)) {
        return;
      }
      if (!storedApproval.equals(nextApproval)) {
        throw new IllegalArgumentException(
            "an undecided approval may only be decided or replayed exactly");
      }
      // 保留 exact undecided approval 只允许保持 WAITING_APPROVAL 或 Stop 为 CANCELLED；
      // 不能直接 FAILED（denied 必须走 decided decision 路径）。
      if (next.status() != ToolInvocationStatus.WAITING_APPROVAL
          && next.status() != ToolInvocationStatus.CANCELLED) {
        throw new IllegalArgumentException(
            "an undecided approval may only keep WAITING_APPROVAL or be stopped as CANCELLED");
      }
      return;
    }
    if (!storedApproval.equals(nextApproval)) {
      throw new IllegalArgumentException("a decided or non-required approval must not change");
    }
  }

  private static void requireIntroductionRules(
      ToolInvocation stored, ToolInvocation next, ToolApproval nextApproval) {
    if (nextApproval == null) {
      return;
    }
    boolean introAsNotRequired =
        stored.status() == ToolInvocationStatus.READY
            && next.status() == ToolInvocationStatus.READY
            && !nextApproval.required();
    boolean introAsRequest =
        stored.status() == ToolInvocationStatus.READY
            && next.status() == ToolInvocationStatus.WAITING_APPROVAL
            && nextApproval.required()
            && nextApproval.isUndecided();
    if (!introAsNotRequired && !introAsRequest) {
      throw new IllegalArgumentException(
          "approval may only be introduced as not-required on READY -> READY or as a required"
              + " undecided request on READY -> WAITING_APPROVAL");
    }
  }

  /**
   * 已决定 approval 的最小形状检查；返回 true 表示这条转换已由决策规则处理完毕。
   *
   * <p>携带新决策时要求 required、decision 非 null、request time 与 editReview 不变，且 target status 与决策匹配。
   */
  private static boolean requireDecisionRules(
      ToolInvocation stored,
      ToolInvocation next,
      ToolApproval storedApproval,
      ToolApproval nextApproval) {
    if (nextApproval == null
        || !nextApproval.required()
        || nextApproval.decision() == null
        || !nextApproval.requestedAt().equals(storedApproval.requestedAt())
        || !Objects.equals(nextApproval.editReview(), storedApproval.editReview())) {
      return false;
    }
    if (nextApproval.decision() == ToolApprovalDecision.ALLOWED
        && next.status() != ToolInvocationStatus.READY) {
      throw new IllegalArgumentException("an ALLOWED decision must resume the invocation as READY");
    }
    if (nextApproval.decision() == ToolApprovalDecision.DENIED
        && next.status() != ToolInvocationStatus.FAILED) {
      throw new IllegalArgumentException(
          "a DENIED decision must terminate the invocation as FAILED");
    }
    return true;
  }

  /**
   * input receipt 只能从“无回执的 WAITING_INPUT”引入到“已回答的 SUCCEEDED”，引入后不可变；terminal 行的回执由 {@link
   * #requireTerminalImmutability} 保证不变。
   */
  private static void requireInputReceiptRules(ToolInvocation stored, ToolInvocation next) {
    ToolInputReceipt storedReceipt = stored.inputReceipt();
    ToolInputReceipt nextReceipt = next.inputReceipt();
    if (storedReceipt != null) {
      if (!storedReceipt.equals(nextReceipt)) {
        throw new IllegalArgumentException("an accepted input receipt must not change");
      }
      return;
    }
    if (nextReceipt == null) {
      return;
    }
    if (stored.status() != ToolInvocationStatus.WAITING_INPUT
        || next.status() != ToolInvocationStatus.SUCCEEDED) {
      throw new IllegalArgumentException(
          "an input receipt may only be introduced by answering WAITING_INPUT as SUCCEEDED");
    }
  }

  private static void requireTerminalImmutability(ToolInvocation stored, ToolInvocation next) {
    if (stored.status() != next.status()
        || stored.attempt() != next.attempt()
        || !Objects.equals(stored.result(), next.result())
        || !Objects.equals(stored.error(), next.error())
        || !Objects.equals(stored.inputReceipt(), next.inputReceipt())) {
      throw new IllegalArgumentException("terminal tool invocation facts must not change");
    }
  }
}
