package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 退出确认的 write-ahead 记录：只保存精确执行身份、目标真实证据与确认时刻。
 *
 * <p>不包含模型输出、用户输入或任何业务内容。记录在提交数据库确认事务之前落盘，确认与结算提交后才删除。
 */
public record ExecutionExitJournalRecord(
    UUID executionId,
    UUID threadId,
    UUID invocationId,
    int attempt,
    String ownerLeaseToken,
    ExecutionExitKind kind,
    UUID parentExecutionId,
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
    if (ownerLeaseToken.isBlank()
        || !ownerLeaseToken.equals(ownerLeaseToken.strip())
        || ownerLeaseToken.length() > 128) {
      throw new IllegalArgumentException("ownerLeaseToken must be a canonical lease token");
    }
    kind = Objects.requireNonNull(kind, "kind");
    if (kind == ExecutionExitKind.REMOTE) {
      parentExecutionId = Objects.requireNonNull(parentExecutionId, "parentExecutionId");
      if (parentExecutionId.equals(executionId)) {
        throw new IllegalArgumentException("remote execution cannot parent itself");
      }
    } else if (parentExecutionId != null) {
      throw new IllegalArgumentException("only a remote execution has a parent execution");
    }
    targetState = Objects.requireNonNull(targetState, "targetState");
    if (!targetState.confirmed()) {
      throw new IllegalArgumentException("journal target must be real exit evidence");
    }
    if (targetState == ExecutionExitState.REMOTE_ACKNOWLEDGED && kind != ExecutionExitKind.REMOTE) {
      throw new IllegalArgumentException("only a remote execution can be remote-acknowledged");
    }
    if (targetState == ExecutionExitState.STUDIO_EXITED && kind == ExecutionExitKind.REMOTE) {
      throw new IllegalArgumentException("a remote execution cannot claim studio exit");
    }
    confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
    HarnessStoreTime.requireMillisecondPrecision(confirmedAt);
  }

  /** 用记录中的身份构造待确认事实；登记时间不参与身份，仅需满足事实的毫秒边界。 */
  public ExecutionExitFact expected(Instant registeredAt) {
    return new ExecutionExitFact(
        executionId,
        threadId,
        invocationId,
        attempt,
        ownerLeaseToken,
        kind,
        parentExecutionId,
        ExecutionExitState.UNCONFIRMED,
        registeredAt,
        null,
        null);
  }

  /** 执行身份相同；目标证据或确认时刻不同仍属于同一身份。 */
  public boolean sameIdentity(ExecutionExitJournalRecord other) {
    return other != null
        && executionId.equals(other.executionId)
        && threadId.equals(other.threadId)
        && invocationId.equals(other.invocationId)
        && attempt == other.attempt
        && ownerLeaseToken.equals(other.ownerLeaseToken)
        && kind == other.kind
        && Objects.equals(parentExecutionId, other.parentExecutionId);
  }
}
