package fun.fengwk.kkstudio.harness.api.compaction;

import java.util.Objects;
import java.util.UUID;

/** A closed-history boundary and trigger for one compaction branch. */
public record CompactionPreparation(CompactionTrigger trigger, UUID cutEntryId) {

  public CompactionPreparation {
    trigger = Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
  }

  /** The pending durable metadata written on the owner's COMPACTION turn. */
  public CompactionStart pendingStart() {
    return CompactionStart.pending(trigger, cutEntryId);
  }
}
