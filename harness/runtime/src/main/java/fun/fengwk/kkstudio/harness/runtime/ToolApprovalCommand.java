package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;

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

  public ToolApprovalCommand {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(toolInvocationId, "toolInvocationId");
    decision = Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(decisionId, "decisionId");
    actor = requireCanonical(actor, "actor", 128);
    reason = nullableCanonical(reason, "reason", 1024);
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    Objects.requireNonNull(value, field);
    return nullableCanonical(value, field, maxLength);
  }

  private static String nullableCanonical(String value, String field, int maxLength) {
    if (value == null) {
      return null;
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be <= " + maxLength + " characters");
    }
    return value;
  }
}
