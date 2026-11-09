package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.common.time.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 执行日志记录：冻结完整执行身份，并只携带当前阶段的证据。
 *
 * <p>PREPARED 只保存身份；EXIT_PROOF 额外保存目标真实证据与确认时刻。记录不包含模型输出、用户输入或任何业务内容。
 */
public record ExecutionExitJournalRecord(
    UUID executionId,
    UUID threadId,
    UUID invocationId,
    int attempt,
    UUID ownerLeaseToken,
    ExecutionExitKind kind,
    UUID parentExecutionId,
    EnvironmentId environmentId,
    ExecutionExitJournalStage stage,
    ExecutionExitState targetState,
    Instant confirmedAt) {

  public ExecutionExitJournalRecord {
    executionId = Objects.requireNonNull(executionId, "executionId");
    threadId = Objects.requireNonNull(threadId, "threadId");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    ownerLeaseToken = Objects.requireNonNull(ownerLeaseToken, "ownerLeaseToken");
    kind = Objects.requireNonNull(kind, "kind");
    if (kind == ExecutionExitKind.REMOTE) {
      parentExecutionId = Objects.requireNonNull(parentExecutionId, "parentExecutionId");
      if (parentExecutionId.equals(executionId)) {
        throw new IllegalArgumentException("remote execution cannot parent itself");
      }
      environmentId = Objects.requireNonNull(environmentId, "environmentId");
    } else {
      if (parentExecutionId != null) {
        throw new IllegalArgumentException("only a remote execution has a parent execution");
      }
      if (environmentId != null) {
        throw new IllegalArgumentException("only a remote execution has an environment id");
      }
    }
    stage = Objects.requireNonNull(stage, "stage");
    if (stage == ExecutionExitJournalStage.PREPARED) {
      if (targetState != null) {
        throw new IllegalArgumentException("a prepared execution has no exit evidence");
      }
      if (confirmedAt != null) {
        throw new IllegalArgumentException("a prepared execution has no confirmation time");
      }
    } else {
      targetState = Objects.requireNonNull(targetState, "targetState");
      if (!targetState.confirmed()) {
        throw new IllegalArgumentException("journal target must be real exit evidence");
      }
      if (targetState == ExecutionExitState.REMOTE_ACKNOWLEDGED
          && kind != ExecutionExitKind.REMOTE) {
        throw new IllegalArgumentException("only a remote execution can be remote-acknowledged");
      }
      if ((targetState == ExecutionExitState.STUDIO_EXITED
              || targetState == ExecutionExitState.OWNER_EXITED)
          && kind == ExecutionExitKind.REMOTE) {
        throw new IllegalArgumentException("a remote execution cannot claim a local exit");
      }
      confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
      HarnessStoreTime.requireMillisecondPrecision(confirmedAt);
    }
  }

  /** 允许本地 body 之前的持久声明：只冻结执行身份。 */
  public static ExecutionExitJournalRecord prepared(LocalExecutionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    return new ExecutionExitJournalRecord(
        identity.executionId(),
        identity.threadId(),
        identity.invocationId(),
        identity.attempt(),
        identity.ownerLeaseToken(),
        identity.kind(),
        null,
        null,
        ExecutionExitJournalStage.PREPARED,
        null,
        null);
  }

  /** 用精确事实与真实证据构造 EXIT_PROOF；未登记或身份不符的事实不能生成证明。 */
  public static ExecutionExitJournalRecord exitProof(
      ExecutionExitFact fact, ExecutionExitState state, Instant confirmedAt) {
    Objects.requireNonNull(fact, "fact");
    return new ExecutionExitJournalRecord(
        fact.executionId(),
        fact.threadId(),
        fact.invocationId(),
        fact.attempt(),
        fact.ownerLeaseToken(),
        fact.kind(),
        fact.parentExecutionId(),
        fact.environmentId(),
        ExecutionExitJournalStage.EXIT_PROOF,
        state,
        confirmedAt);
  }

  /** 用记录中的身份构造待确认事实；登记时间不参与身份，仅需满足事实的毫秒边界。 */
  public ExecutionExitFact expected(Instant registeredAt) {
    if (stage != ExecutionExitJournalStage.EXIT_PROOF) {
      throw new IllegalStateException("a prepared execution has no exit evidence");
    }
    return new ExecutionExitFact(
        executionId,
        threadId,
        invocationId,
        attempt,
        ownerLeaseToken,
        kind,
        parentExecutionId,
        environmentId,
        ExecutionExitState.UNCONFIRMED,
        registeredAt,
        null,
        null);
  }

  /** 执行身份相同；阶段、目标证据或确认时刻不同仍属于同一身份。 */
  public boolean sameIdentity(ExecutionExitJournalRecord other) {
    return other != null
        && executionId.equals(other.executionId)
        && threadId.equals(other.threadId)
        && invocationId.equals(other.invocationId)
        && attempt == other.attempt
        && ownerLeaseToken.equals(other.ownerLeaseToken)
        && kind == other.kind
        && Objects.equals(parentExecutionId, other.parentExecutionId)
        && Objects.equals(environmentId, other.environmentId);
  }

  /** 与数据库事实的身份字段一致；登记时间、证据与状态不参与比较。 */
  public boolean matchesFactIdentity(ExecutionExitFact fact) {
    return fact != null
        && executionId.equals(fact.executionId())
        && threadId.equals(fact.threadId())
        && invocationId.equals(fact.invocationId())
        && attempt == fact.attempt()
        && ownerLeaseToken.equals(fact.ownerLeaseToken())
        && kind == fact.kind()
        && Objects.equals(parentExecutionId, fact.parentExecutionId())
        && Objects.equals(environmentId, fact.environmentId());
  }
}
