package fun.fengwk.kkstudio.harness.runtime.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Canonical millisecond time boundary shared by HarnessStore callers and implementations. */
public final class HarnessStoreTime {

  private static final Duration PRECISION = Duration.ofMillis(1);

  private HarnessStoreTime() {}

  /** Wraps a source clock so every sampled instant uses the durable millisecond precision. */
  public static Clock millisecondClock(Clock source) {
    return Clock.tick(Objects.requireNonNull(source, "source"), PRECISION);
  }

  /**
   * Returns a nullable canonical instant or rejects a value containing sub-millisecond precision.
   */
  public static Instant requireMillisecondPrecision(Instant value) {
    if (value != null && value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException("durable timestamps must use millisecond precision");
    }
    return value;
  }

  /** Returns a positive whole-millisecond duration suitable for durable time arithmetic. */
  public static Duration requireWholeMillisecondDuration(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
      throw new IllegalArgumentException(name + " must be at least one millisecond");
    }
    if (value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException(name + " must use whole milliseconds");
    }
    return value;
  }
}
