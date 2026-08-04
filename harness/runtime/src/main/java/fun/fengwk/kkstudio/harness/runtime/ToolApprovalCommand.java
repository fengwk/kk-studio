package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;

import java.util.Objects;

/**
 * Immutable Tool approval request: the explicit Thread owning the invocation, the target Tool
 * invocation, the decision, a stable client-generated {@code decisionId} for idempotent retries,
 * the acting user and an optional reason. The server derives {@code decidedAt} from its Clock, so
 * retries never carry a client timestamp.
 */
public record ToolApprovalCommand(
    long threadId,
    long toolInvocationId,
    ToolApprovalDecision decision,
    String decisionId,
    String actor,
    String reason) {

  public ToolApprovalCommand {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (toolInvocationId <= 0) {
      throw new IllegalArgumentException("toolInvocationId must be positive");
    }
    decision = Objects.requireNonNull(decision, "decision");
    decisionId = requireCanonical(decisionId, "decisionId", 128);
    actor = requireCanonical(actor, "actor", 128);
    reason = nullableCanonical(reason, "reason", 1024);
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
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
