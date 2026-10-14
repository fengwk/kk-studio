package fun.fengwk.kkstudio.platform.storage;

import java.time.Duration;
import java.util.Objects;

/**
 * 受管资源读取的绝对截止时间。
 *
 * <p>截止时间在读取请求入口冻结一次，并向下传递到同一个读取的所有阶段（S3 请求超时、响应流看门狗、文本窗口扫描），因此 "30 秒" 是包括 S3 getObject
 * 握手与响应体消费在内的总预算，而不是每一阶段各自重置的预算。同步 getObject 的 {@code apiCallTimeout} 只覆盖到响应头返回、socket
 * 超时只在空闲时生效，所以读取边界必须持有这样一个冻结的绝对截止点。
 *
 * @author fengwk
 */
public final class ReadDeadline {

  private final long deadlineNanos;

  private ReadDeadline(long deadlineNanos) {
    this.deadlineNanos = deadlineNanos;
  }

  /**
   * 从现在起冻结一个读取预算。
   *
   * @param budget 预算，必须为正
   */
  public static ReadDeadline after(Duration budget) {
    Objects.requireNonNull(budget, "budget");
    if (budget.isNegative() || budget.isZero()) {
      throw new IllegalArgumentException("budget must be positive");
    }
    return new ReadDeadline(System.nanoTime() + budget.toNanos());
  }

  /** 剩余预算；已到期时返回零，不会为负数。 */
  public Duration remaining() {
    long remainingNanos = remainingNanos();
    return remainingNanos > 0L ? Duration.ofNanos(remainingNanos) : Duration.ZERO;
  }

  public long remainingNanos() {
    return deadlineNanos - System.nanoTime();
  }

  public boolean isExpired() {
    return remainingNanos() <= 0L;
  }
}
