package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.UUID;

/** Queries durable platform evidence that an execution exit was already accepted. */
@FunctionalInterface
public interface ExecutionExitReceiptLookup {

  /**
   * The wire invocation UUID is the remote execution ID; only a durable acceptance returns true.
   */
  boolean isAcknowledged(EnvironmentId environmentId, UUID remoteExecutionId);
}
