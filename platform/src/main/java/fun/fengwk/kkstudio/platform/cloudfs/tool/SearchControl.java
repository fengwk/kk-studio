package fun.fengwk.kkstudio.platform.cloudfs.tool;

import java.time.Duration;
import java.util.Objects;

/** 搜索执行控制，统一检查超时、中断与取消。保持生产 API 最小。 */
public final class SearchControl {

  private final long deadlineNanos;
  private volatile boolean cancelled;
  private final Runnable checkProbe;

  private SearchControl(long deadlineNanos, Runnable checkProbe) {
    this.deadlineNanos = deadlineNanos;
    this.checkProbe = checkProbe;
  }

  public static SearchControl of(Duration timeout) {
    long deadline =
        timeout == null || timeout.isZero() || timeout.isNegative()
            ? Long.MAX_VALUE
            : saturatedAddNanos(System.nanoTime(), timeout.toNanos());
    return new SearchControl(deadline, null);
  }

  /**
   * 结合参数指定的超时与请求级超时，计算有效有限超时（取两者中较小者）。
   *
   * <p>若 requestTimeout 为空、ZERO（表示未指定）或负数，则完全采用 argumentTimeout。
   */
  public static SearchControl of(Duration argumentTimeout, Duration requestTimeout) {
    Duration effective = argumentTimeout;
    if (requestTimeout != null && !requestTimeout.isZero() && !requestTimeout.isNegative()) {
      if (effective == null
          || effective.isZero()
          || effective.isNegative()
          || requestTimeout.compareTo(effective) < 0) {
        effective = requestTimeout;
      }
    }
    return of(effective);
  }

  /** 包可见测试探针构造方法，用于在不暴露公开测试 API 的前提下验证 early stop。 */
  static SearchControl withProbe(Duration timeout, Runnable checkProbe) {
    long deadline =
        timeout == null || timeout.isZero() || timeout.isNegative()
            ? Long.MAX_VALUE
            : saturatedAddNanos(System.nanoTime(), timeout.toNanos());
    return new SearchControl(deadline, Objects.requireNonNull(checkProbe, "checkProbe"));
  }

  private static long saturatedAddNanos(long now, long nanos) {
    long r = now + nanos;
    if (((now ^ r) & (nanos ^ r)) < 0) {
      return Long.MAX_VALUE;
    }
    return r;
  }

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled || Thread.currentThread().isInterrupted();
  }

  public void check() throws InterruptedException {
    if (checkProbe != null) {
      checkProbe.run();
    }
    if (isCancelled()) {
      throw new InterruptedException("Operation was cancelled or interrupted");
    }
    if (System.nanoTime() > deadlineNanos) {
      throw new InterruptedException("Search operation timed out");
    }
  }
}
