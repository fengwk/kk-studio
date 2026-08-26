package fun.fengwk.kkstudio.harness.daemon.coding;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 在 Java 原生搜索的遍历、读取与匹配循环中统一检查 deadline 和 cancellation。 */
final class SearchControl {

  private final BooleanSupplier cancelled;
  private final long startedNanos;
  private final long timeoutNanos;
  private final long timeoutMillis;
  private final String capabilityName;
  private final LongSupplier nanoTime;

  SearchControl(
      Duration timeout, BooleanSupplier cancelled, String capabilityName, LongSupplier nanoTime) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
    this.capabilityName = Objects.requireNonNull(capabilityName, "capabilityName");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    startedNanos = nanoTime.getAsLong();
    timeoutNanos = timeout.toNanos();
    timeoutMillis = timeout.toMillis();
  }

  static SearchControl start(
      Duration timeout, AbstractCodingCapability.Execution execution, String capabilityName) {
    Objects.requireNonNull(execution, "execution");
    return new SearchControl(timeout, execution::isCancelled, capabilityName, System::nanoTime);
  }

  void check() throws InterruptedException {
    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
      throw new InterruptedException();
    }
    if (nanoTime.getAsLong() - startedNanos >= timeoutNanos) {
      throw new IllegalArgumentException(
          capabilityName + " timed out after " + timeoutMillis + " milliseconds");
    }
  }
}
