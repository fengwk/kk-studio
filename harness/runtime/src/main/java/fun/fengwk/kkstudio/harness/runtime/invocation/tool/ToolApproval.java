package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable Tool approval state persisted on the ToolInvocation.
 *
 * <p>Exact invariants:
 *
 * <ul>
 *   <li>{@code required=false}: all other fields are null.
 *   <li>{@code required=true} and undecided: {@code requestedAt} present, {@code reason} optional,
 *       decision facts absent.
 *   <li>{@code required=true} and decided: decision, {@code decisionId}, {@code actor}, {@code
 *       requestedAt} and {@code decidedAt} all present, {@code decidedAt >= requestedAt}, {@code
 *       reason} optional.
 * </ul>
 */
public record ToolApproval(
    boolean required,
    ToolApprovalDecision decision,
    String decisionId,
    String actor,
    String reason,
    Instant requestedAt,
    Instant decidedAt) {

  public ToolApproval {
    if (!required) {
      if (decision != null
          || decisionId != null
          || actor != null
          || reason != null
          || requestedAt != null
          || decidedAt != null) {
        throw new IllegalArgumentException("non-required approval must not carry decision facts");
      }
    } else if (decision == null) {
      if (decisionId != null || actor != null || decidedAt != null) {
        throw new IllegalArgumentException(
            "undecided approval must not carry decisionId, actor or decidedAt");
      }
      requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
    } else {
      decisionId = requireCanonical(decisionId, "decisionId");
      actor = requireCanonical(actor, "actor");
      requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
      decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
      if (decidedAt.isBefore(requestedAt)) {
        throw new IllegalArgumentException("decidedAt must not precede requestedAt");
      }
    }
    if (reason != null) {
      reason = requireCanonical(reason, "reason", 1024);
    }
  }

  /** Whether this approval is required but has not been decided yet. */
  public boolean isUndecided() {
    return required && decision == null;
  }

  private static String requireCanonical(String value, String field) {
    return requireCanonical(value, field, 128);
  }

  private static String requireCanonical(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
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
