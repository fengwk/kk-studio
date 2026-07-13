package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.Objects;

public record AgentSnapshotEntryPayload(AgentSnapshot snapshot) implements SessionEntryPayload {
  public AgentSnapshotEntryPayload {
    snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.AGENT_SNAPSHOT;
  }
}
