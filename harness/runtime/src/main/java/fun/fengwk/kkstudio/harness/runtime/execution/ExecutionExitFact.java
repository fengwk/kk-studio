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
    Instant confirmedAt,
    Instant abandonedAt) {

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
    abandonedAt = HarnessStoreTime.requireMillisecondPrecision(abandonedAt);
    Objects.requireNonNull(registeredAt, "registeredAt");
    if (state == ExecutionExitState.UNCONFIRMED) {
      if (confirmedAt != null) {
        throw new IllegalArgumentException("an unconfirmed execution has no confirmation time");
      }
      if (abandonedAt != null) {
        throw new IllegalArgumentException("an unconfirmed execution has no abandonment time");
      }
    } else if (state == ExecutionExitState.ABANDONED) {
      if (confirmedAt != null) {
        throw new IllegalArgumentException("an abandoned execution has no confirmation time");
      }
      abandonedAt = Objects.requireNonNull(abandonedAt, "abandonedAt");
      if (abandonedAt.isBefore(registeredAt)) {
        throw new IllegalArgumentException("abandonment cannot precede registration");
      }
    } else {
      confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
      if (confirmedAt.isBefore(registeredAt)) {
        throw new IllegalArgumentException("confirmation cannot precede registration");
      }
      if (abandonedAt != null && abandonedAt.isBefore(registeredAt)) {
        throw new IllegalArgumentException("abandonment cannot precede registration");
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

  /** 幂等放弃：相同放弃返回自身；真实确认后的放弃是冲突，不能把退出改写成放弃。 */
  public ExecutionExitFact abandon(Instant at) {
    Instant abandoned = HarnessStoreTime.requireMillisecondPrecision(at);
    Objects.requireNonNull(abandoned, "abandonedAt");
    if (state == ExecutionExitState.ABANDONED) {
      return this;
    }
    if (state.confirmed()) {
      throw new IllegalArgumentException(
          "execution " + executionId + " is already confirmed as " + state);
    }
    if (state != ExecutionExitState.UNCONFIRMED) {
      throw new IllegalArgumentException(
          "execution " + executionId + " cannot be abandoned from " + state);
    }
    return new ExecutionExitFact(
        executionId,
        threadId,
        invocationId,
        attempt,
        ownerLeaseToken,
        kind,
        parentExecutionId,
        ExecutionExitState.ABANDONED,
        registeredAt,
        null,
        abandoned);
  }

  /**
   * 幂等确认：相同真实证据返回自身；已确认后的另一种真实证据是冲突，不能把一种退出改写成另一种。
   *
   * <p>ABANDONED 不是真实退出，可以被真实证据取代；取代后保留原放弃时刻。
   */
  public ExecutionExitFact confirm(ExecutionExitState next, Instant at) {
    Objects.requireNonNull(next, "next");
    if (next == ExecutionExitState.UNCONFIRMED || next == ExecutionExitState.ABANDONED) {
      throw new IllegalArgumentException("confirmation requires real exit evidence");
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
        confirmed,
        abandonedAt);
  }

  private static String requireLeaseToken(String token) {
    Objects.requireNonNull(token, "ownerLeaseToken");
    if (token.isBlank() || !token.equals(token.strip()) || token.length() > 128) {
      throw new IllegalArgumentException("ownerLeaseToken must be a canonical lease token");
    }
    return token;
  }
}
