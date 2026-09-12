package fun.fengwk.kkstudio.platform.environment.operation;

import java.time.Duration;
import java.util.Objects;

/**
 * 成功认领为 RUNNING 的内部环境操作及数据库语句时间计算出的剩余严格正执行超时（包内私有）。
 *
 * <p>{@code remainingTimeout} 是在 PostgreSQL 认领原子语句中通过 {@code deadline_at - statement_timestamp()}
 * 计算所得， 避免 JVM 与数据库时钟偏差。
 */
public record ClaimedOperation(EnvironmentOperation operation, Duration remainingTimeout) {

  public ClaimedOperation {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(remainingTimeout, "remainingTimeout");
    if (remainingTimeout.isNegative() || remainingTimeout.isZero()) {
      throw new IllegalArgumentException("remainingTimeout must be strictly positive");
    }
  }

  @Override
  public String toString() {
    return "ClaimedOperation[operation="
        + operation
        + ", remainingTimeout="
        + remainingTimeout
        + "]";
  }
}
