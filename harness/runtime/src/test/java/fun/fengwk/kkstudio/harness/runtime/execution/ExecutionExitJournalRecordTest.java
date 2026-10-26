package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class ExecutionExitJournalRecordTest {

  private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

  @Test
  void rejectsIdentityTargetAndEvidenceCombinationsThatCannotBeReplayed() {
    UUID executionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                executionId,
                threadId,
                invocationId,
                0,
                "lease-1",
                ExecutionExitKind.TOOL,
                null,
                ExecutionExitState.STUDIO_EXITED,
                T0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                executionId,
                threadId,
                invocationId,
                1,
                " lease-1",
                ExecutionExitKind.TOOL,
                null,
                ExecutionExitState.STUDIO_EXITED,
                T0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                executionId,
                threadId,
                invocationId,
                1,
                "lease-1",
                ExecutionExitKind.REMOTE,
                executionId,
                ExecutionExitState.STUDIO_EXITED,
                T0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                executionId,
                threadId,
                invocationId,
                1,
                "lease-1",
                ExecutionExitKind.TOOL,
                UUID.randomUUID(),
                ExecutionExitState.STUDIO_EXITED,
                T0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                executionId,
                threadId,
                invocationId,
                1,
                "lease-1",
                ExecutionExitKind.TOOL,
                null,
                ExecutionExitState.UNCONFIRMED,
                T0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                executionId,
                threadId,
                invocationId,
                1,
                "lease-1",
                ExecutionExitKind.REMOTE,
                UUID.randomUUID(),
                ExecutionExitState.STUDIO_EXITED,
                T0));
  }

  private static ExecutionExitJournalRecord record(
      UUID executionId,
      UUID threadId,
      UUID invocationId,
      int attempt,
      String ownerLeaseToken,
      ExecutionExitKind kind,
      UUID parentExecutionId,
      ExecutionExitState targetState,
      Instant confirmedAt) {
    return new ExecutionExitJournalRecord(
        executionId,
        threadId,
        invocationId,
        attempt,
        ownerLeaseToken,
        kind,
        parentExecutionId,
        targetState,
        confirmedAt);
  }
}
