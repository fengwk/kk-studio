package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.platform.daemon.server.ExecutionExitReceiptLookup;

import java.util.Objects;
import java.util.UUID;

/** Reads durable remote exit receipts without retaining per-invocation platform memory. */
public final class HarnessStoreExecutionExitReceiptLookup implements ExecutionExitReceiptLookup {

  private final HarnessStore store;

  public HarnessStoreExecutionExitReceiptLookup(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public boolean isAcknowledged(EnvironmentId environmentId, UUID remoteExecutionId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(remoteExecutionId, "remoteExecutionId");
    return store.transaction(
        tx ->
            tx.exits()
                .find(remoteExecutionId)
                .filter(fact -> fact.kind() == ExecutionExitKind.REMOTE)
                .filter(fact -> environmentId.equals(fact.environmentId()))
                .filter(fact -> fact.state() == ExecutionExitState.REMOTE_ACKNOWLEDGED)
                .isPresent());
  }
}
