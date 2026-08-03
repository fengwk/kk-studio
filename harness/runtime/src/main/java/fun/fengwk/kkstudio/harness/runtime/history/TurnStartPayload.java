package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;

import java.util.Objects;

/** Immutable durable boundary for the beginning of one Model response turn. */
public record TurnStartPayload(TurnStartReason reason, BranchSettings settings)
    implements EntryPayload {

  public TurnStartPayload {
    reason = Objects.requireNonNull(reason, "reason");
    settings = Objects.requireNonNull(settings, "settings");
  }

  @Override
  public EntryType type() {
    return EntryType.TURN_START;
  }
}
