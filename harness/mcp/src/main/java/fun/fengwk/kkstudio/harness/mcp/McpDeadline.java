package fun.fengwk.kkstudio.harness.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 封装 MCP 外层调用的总预算 deadline。
 *
 * <p>一个 operation deadline 覆盖 client 初始化以及后续 list/call；不重置单阶段预算。生产路径必须显式提供预算，因此没有 unlimited 形式。
 */
public final class McpDeadline {

  private final Instant deadline;

  private McpDeadline(Instant deadline) {
    this.deadline = Objects.requireNonNull(deadline, "deadline");
  }

  /** 基于持续时间创建截止时间。 */
  public static McpDeadline of(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    return new McpDeadline(Instant.now().plus(timeout));
  }

  /** 基于绝对时间创建截止时间。 */
  public static McpDeadline ofDeadline(Instant deadline) {
    return new McpDeadline(deadline);
  }

  /** 截止时间戳。 */
  public Instant deadline() {
    return deadline;
  }

  /** 返回当前剩余时长；若已超时则返回 {@link Duration#ZERO}。 */
  public Duration remaining() {
    Duration diff = Duration.between(Instant.now(), deadline);
    return diff.isNegative() ? Duration.ZERO : diff;
  }

  /** 返回当前剩余时长，若已超时则抛出 {@link McpTimeoutException}。 */
  public Duration requireRemaining() {
    checkNotExpired();
    return remaining();
  }

  /** 是否已超过截止时间。 */
  public boolean isExpired() {
    return !Instant.now().isBefore(deadline);
  }

  /** 若已超时则抛出 {@link McpTimeoutException}。 */
  public void checkNotExpired() {
    if (isExpired()) {
      throw new McpTimeoutException("MCP operation deadline exceeded");
    }
  }
}
