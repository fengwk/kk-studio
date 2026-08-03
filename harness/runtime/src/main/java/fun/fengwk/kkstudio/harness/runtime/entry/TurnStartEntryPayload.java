package fun.fengwk.kkstudio.harness.runtime.entry;

import java.util.Objects;

/** Immutable durable boundary for the beginning of one Model response turn. */
public record TurnStartEntryPayload(TurnStartReason reason, BranchSettings settings)
    implements RuntimeEntryPayload {

  public TurnStartEntryPayload {
    reason = Objects.requireNonNull(reason, "reason");
    settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_START;
  }
}
