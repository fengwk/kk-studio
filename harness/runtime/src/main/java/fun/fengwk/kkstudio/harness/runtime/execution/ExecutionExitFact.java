package fun.fengwk.kkstudio.harness.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次具体执行代次的不可变身份与退出证据。
 *
 * <p>身份在登记后不可更换。逻辑 Invocation 被删除、Work 被 Stop 清掉，都不改变这一行；旧代确认也不能确认新代。
 */
public record ExecutionExitFact(
    UUID executionId,
    UUID threadId,
    UUID invocationId,
    int attempt,
    String ownerLeaseToken,
    ExecutionExitKind kind,
    UUID parentExecutionId,
    ExecutionExitState state,
    Instant registeredAt,
    Instant confirmedAt) {

  public ExecutionExitFact {
    executionId = Objects.requireNonNull(executionId, "executionId");
    threadId = Objects.requireNonNull(threadId, "threadId");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    ownerLeaseToken = requireLeaseToken(ownerLeaseToken);
    kind = Objects.requireNonNull(kind, "kind");
    if (kind == ExecutionExitKind.REMOTE) {
      parentExecutionId = Objects.requireNonNull(parentExecutionId, "parentExecutionId");
      if (parentExecutionId.equals(executionId)) {
        throw new IllegalArgumentException("remote execution cannot parent itself");
      }
    } else if (parentExecutionId != null) {
      throw new IllegalArgumentException("only a remote execution has a parent execution");
    }
    state = Objects.requireNonNull(state, "state");
    if (state == ExecutionExitState.REMOTE_ACKNOWLEDGED && kind != ExecutionExitKind.REMOTE) {
      throw new IllegalArgumentException("only a remote execution can be remote-acknowledged");
    }
    if (state == ExecutionExitState.STUDIO_EXITED && kind == ExecutionExitKind.REMOTE) {
      throw new IllegalArgumentException("a remote execution cannot claim studio exit");
    }
    registeredAt = HarnessStoreTime.requireMillisecondPrecision(registeredAt);
    confirmedAt = HarnessStoreTime.requireMillisecondPrecision(confirmedAt);
    Objects.requireNonNull(registeredAt, "registeredAt");
    if (state == ExecutionExitState.UNCONFIRMED) {
      if (confirmedAt != null) {
        throw new IllegalArgumentException("an unconfirmed execution has no confirmation time");
      }
    } else {
      confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
      if (confirmedAt.isBefore(registeredAt)) {
        throw new IllegalArgumentException("confirmation cannot precede registration");
      }
    }
  }

  /** 身份字段与另一事实相同。登记时间与退出证据不参与身份。 */
  public boolean sameIdentity(ExecutionExitFact other) {
    return other != null
        && executionId.equals(other.executionId)
        && threadId.equals(other.threadId)
        && invocationId.equals(other.invocationId)
        && attempt == other.attempt
        && ownerLeaseToken.equals(other.ownerLeaseToken)
        && kind == other.kind
        && Objects.equals(parentExecutionId, other.parentExecutionId);
  }

  /** 远端事实只能挂在同 Thread / invocation / attempt / lease 的本地 TOOL 代次下。 */
  public void requireRemoteParent(ExecutionExitFact parent) {
    Objects.requireNonNull(parent, "parent");
    if (kind != ExecutionExitKind.REMOTE) {
      throw new IllegalArgumentException("only a remote execution has a parent");
    }
    if (parent.kind() != ExecutionExitKind.TOOL
        || !parent.executionId().equals(parentExecutionId)
        || !parent.threadId().equals(threadId)
        || !parent.invocationId().equals(invocationId)
        || parent.attempt() != attempt
        || !parent.ownerLeaseToken().equals(ownerLeaseToken)) {
      throw new IllegalArgumentException(
          "remote execution parent must be the same generation's TOOL execution");
    }
  }

  /** 幂等确认：相同证据返回自身；已确认后的另一种证据是冲突，不能把一种退出改写成另一种。 */
  public ExecutionExitFact confirm(ExecutionExitState next, Instant at) {
    Objects.requireNonNull(next, "next");
    if (next == ExecutionExitState.UNCONFIRMED) {
      throw new IllegalArgumentException("confirmation cannot return to unconfirmed");
    }
    Instant confirmed = HarnessStoreTime.requireMillisecondPrecision(at);
    Objects.requireNonNull(confirmed, "confirmedAt");
    if (state == next) {
      return this;
    }
    if (state.confirmed()) {
      throw new IllegalArgumentException(
          "execution " + executionId + " is already confirmed as " + state);
    }
    return new ExecutionExitFact(
        executionId,
        threadId,
        invocationId,
        attempt,
        ownerLeaseToken,
        kind,
        parentExecutionId,
        next,
        registeredAt,
        confirmed);
  }

  private static String requireLeaseToken(String token) {
    Objects.requireNonNull(token, "ownerLeaseToken");
    if (token.isBlank() || !token.equals(token.strip()) || token.length() > 128) {
      throw new IllegalArgumentException("ownerLeaseToken must be a canonical lease token");
    }
    return token;
  }
}
