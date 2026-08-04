package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;

import java.util.Objects;

/**
 * Immutable durable boundary for the end of one Model response turn.
 *
 * <p>精确规则：COMPLETED 的 continueModel 可 true/false，reason 与 closeRequestId 必须 null；FAILED 必须
 * continueModel=false、reason=TURN_FAILED、closeRequestId null；STOPPED 必须 reason=USER_STOP、
 * continueModel=false、closeRequestId canonical 非空且 ≤256；CANCELLED 必须 reason=HISTORY_CUT 或
 * CANCELLED、continueModel=false，closeRequestId 可为 null（若有则 canonical 且 ≤256）。
 */
public record TurnEndPayload(
    long turnStartEntryId,
    TurnEndOutcome outcome,
    boolean continueModel,
    TurnEndReason reason,
    String closeRequestId)
    implements EntryPayload {

  private static final int CLOSE_REQUEST_ID_MAX_LENGTH = 256;

  public TurnEndPayload {
    if (turnStartEntryId <= 0) {
      throw new IllegalArgumentException("turnStartEntryId must be positive");
    }
    outcome = Objects.requireNonNull(outcome, "outcome");
    switch (outcome) {
      case COMPLETED -> {
        if (reason != null || closeRequestId != null) {
          throw new IllegalArgumentException(
              "completed turns must not carry reason or closeRequestId");
        }
      }
      case FAILED -> {
        if (reason != TurnEndReason.TURN_FAILED) {
          throw new IllegalArgumentException("failed turns require reason TURN_FAILED");
        }
        if (continueModel) {
          throw new IllegalArgumentException("continueModel must be false for failed turns");
        }
        if (closeRequestId != null) {
          throw new IllegalArgumentException("failed turns must not carry closeRequestId");
        }
      }
      case STOPPED -> {
        if (reason != TurnEndReason.USER_STOP) {
          throw new IllegalArgumentException("stopped turns require reason USER_STOP");
        }
        if (continueModel) {
          throw new IllegalArgumentException("continueModel must be false for stopped turns");
        }
        closeRequestId = requireCanonicalName(closeRequestId, "closeRequestId");
      }
      case CANCELLED -> {
        if (reason != TurnEndReason.HISTORY_CUT && reason != TurnEndReason.CANCELLED) {
          throw new IllegalArgumentException(
              "cancelled turns require reason HISTORY_CUT or CANCELLED");
        }
        if (continueModel) {
          throw new IllegalArgumentException("continueModel must be false for cancelled turns");
        }
        closeRequestId = nullableCanonicalName(closeRequestId, "closeRequestId");
      }
    }
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_END;
  }

  private static String requireCanonicalName(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > CLOSE_REQUEST_ID_MAX_LENGTH) {
      throw new IllegalArgumentException(
          field + " must be <= " + CLOSE_REQUEST_ID_MAX_LENGTH + " characters");
    }
    return value;
  }

  private static String nullableCanonicalName(String value, String field) {
    return value == null ? null : requireCanonicalName(value, field);
  }
}
