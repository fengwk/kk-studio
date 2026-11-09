package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.time.Instant;
import java.util.UUID;

class ExecutionExitJournalRecordTest {

  private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
  private static final EnvironmentId ENV = EnvironmentId.of(new UUID(0L, 501L));

  @Test
  void preparedRecordMustBeALocalExecution() {
    // 远端执行不占用本地物理生命周期；静态工厂已限制，直接构造与解码路径也必须拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionExitJournalRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                ExecutionExitKind.REMOTE,
                UUID.randomUUID(),
                ENV,
                ExecutionExitJournalStage.PREPARED,
                null,
                null));
  }

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
                UUID.randomUUID(),
                ExecutionExitKind.TOOL,
                null,
                ExecutionExitState.STUDIO_EXITED,
                T0));
    assertThrows(
        NullPointerException.class,
        () ->
            record(
                executionId,
                threadId,
                invocationId,
                1,
                null,
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
                UUID.randomUUID(),
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
                UUID.randomUUID(),
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
                UUID.randomUUID(),
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
                UUID.randomUUID(),
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
      UUID ownerLeaseToken,
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
        kind == ExecutionExitKind.REMOTE ? ENV : null,
        ExecutionExitJournalStage.EXIT_PROOF,
        targetState,
        confirmedAt);
  }
}
