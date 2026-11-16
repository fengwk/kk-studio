package fun.fengwk.kkstudio.harness.api;

import fun.fengwk.kkstudio.harness.api.entry.CanonicalNames;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;

import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的 Tool approval 请求：拥有该 invocation 的明确 Thread、目标 Tool invocation、决策、 用于幂等重试的客户端生成的稳定 {@code
 * decisionId}、操作用户以及可选 reason。服务端从其 Clock 推导 {@code decidedAt}，因此重试永不携带客户端时间戳。
 */
public record ToolApprovalCommand(
    UUID threadId,
    UUID toolInvocationId,
    ToolApprovalDecision decision,
    UUID decisionId,
    String actor,
    String reason) {

  /** 决策说明的字符上限。 */
  private static final int REASON_MAX_CHARS = 1024;

  public ToolApprovalCommand {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(toolInvocationId, "toolInvocationId");
    decision = Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(decisionId, "decisionId");
    actor = CanonicalNames.requireName(actor, "actor");
    // 空白 reason 与缺失同义：都不记录决策说明。
    reason =
        reason == null || reason.isBlank()
            ? null
            : CanonicalNames.requireName(reason, "reason", REASON_MAX_CHARS);
  }
}
