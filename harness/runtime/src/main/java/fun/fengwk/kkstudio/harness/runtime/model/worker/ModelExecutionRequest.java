package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;

import java.time.Instant;
import java.util.Objects;

/**
 * 交给 ModelExecutor 的一次已 durable claim 的执行请求。
 *
 * <p>{@code invocationId + attempt} 是外部 adapter 可选使用的一次实际 Provider 调用的稳定 idempotency key；它不替代数据库
 * worker lease 或 callback fencing。{@code deadlineAt} 是 Invocation 首次进入 RUNNING 时建立的总执行 deadline。
 */
public record ModelExecutionRequest(
    long invocationId, int attempt, ProviderRequest request, Instant deadlineAt) {

  public ModelExecutionRequest {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    request = Objects.requireNonNull(request, "request");
    deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
  }

  /** Provider transport 可作为幂等键使用的 stable attempt identity。 */
  public String idempotencyKey() {
    return invocationId + ":" + attempt;
  }
}
