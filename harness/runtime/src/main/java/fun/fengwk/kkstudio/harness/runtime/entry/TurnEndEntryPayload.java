package fun.fengwk.kkstudio.harness.runtime.entry;

import java.util.Objects;

/** Immutable durable boundary for the end of one Model response turn. */
public record TurnEndEntryPayload(
    long turnStartEntryId,
    TurnEndOutcome outcome,
    boolean continueModel,
    String reason,
    String closeRequestId)
    implements RuntimeEntryPayload {

  public TurnEndEntryPayload {
    if (turnStartEntryId <= 0) {
      throw new IllegalArgumentException("turnStartEntryId must be positive");
    }
    outcome = Objects.requireNonNull(outcome, "outcome");
    if (outcome != TurnEndOutcome.COMPLETED && continueModel) {
      throw new IllegalArgumentException(
          "continueModel must be false for non-completed turn outcomes");
    }
    reason = nullableCanonicalName(reason, "reason");
    closeRequestId = nullableCanonicalName(closeRequestId, "closeRequestId");
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_END;
  }

  private static String nullableCanonicalName(String value, String field) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(field + " must be <= 128 characters");
    }
    return value;
  }
}
