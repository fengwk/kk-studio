package fun.fengwk.kkstudio.harness.api.compaction;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;

import java.util.Objects;
import java.util.UUID;

/** Frozen metadata for a COMPACTION owner turn. */
public record CompactionStart(
    CompactionTrigger trigger,
    ExecutionSelection execution,
    Long outputBudget,
    UUID cutEntryId,
    UUID childThreadId,
    UUID joinInvocationId) {

  public static CompactionStart pending(CompactionTrigger trigger, UUID cutEntryId) {
    return new CompactionStart(trigger, null, null, cutEntryId, null, null);
  }

  public CompactionStart {
    trigger = Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    boolean allRunGroupNull =
        execution == null
            && outputBudget == null
            && childThreadId == null
            && joinInvocationId == null;
    boolean allRunGroupNonNull =
        execution != null
            && outputBudget != null
            && childThreadId != null
            && joinInvocationId != null;
    if (!allRunGroupNull && !allRunGroupNonNull) {
      throw new IllegalArgumentException(
          "execution, outputBudget, childThreadId, and joinInvocationId must either all be null "
              + "or all be non-null");
    }
    if (allRunGroupNonNull && outputBudget <= 0L) {
      throw new IllegalArgumentException("outputBudget must be positive");
    }
  }
}
