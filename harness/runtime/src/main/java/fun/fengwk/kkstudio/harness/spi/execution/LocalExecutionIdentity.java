package fun.fengwk.kkstudio.harness.spi.execution;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次本地执行的完整身份：日志、数据库登记与退出证明共用同一份身份，登记后不可更换。
 *
 * <p>只有本地 DRIVER/TOOL 占用物理生命周期；REMOTE 只是挂在本地代次上的远端事实，不能通过本地端口登记。
 *
 * @param executionId 本代次的不可变标识
 * @param threadId 所属 Thread
 * @param invocationId 关联的逻辑 Driver 或 Tool invocation
 * @param attempt 该 invocation 的具体执行代次，从 1 开始
 * @param ownerLeaseToken 取得句柄时持有的 Work lease token
 * @param kind 本地执行种类，只能是 {@link ExecutionExitKind#DRIVER} 或 {@link ExecutionExitKind#TOOL}
 */
public record LocalExecutionIdentity(
    UUID executionId,
    UUID threadId,
    UUID invocationId,
    int attempt,
    UUID ownerLeaseToken,
    ExecutionExitKind kind) {

  public LocalExecutionIdentity {
    executionId = Objects.requireNonNull(executionId, "executionId");
    threadId = Objects.requireNonNull(threadId, "threadId");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    ownerLeaseToken = Objects.requireNonNull(ownerLeaseToken, "ownerLeaseToken");
    kind = Objects.requireNonNull(kind, "kind");
    if (kind == ExecutionExitKind.REMOTE) {
      throw new IllegalArgumentException("a remote execution has no local physical lifecycle");
    }
  }
}
