package fun.fengwk.kkstudio.harness.runtime.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** HarnessStore 调用方与实现共享的 canonical 毫秒时间边界。 */
public final class HarnessStoreTime {

  private static final Duration PRECISION = Duration.ofMillis(1);

  private HarnessStoreTime() {}

  /** 包装源 clock，使每个采样 instant 都使用 durable 毫秒精度。 */
  public static Clock millisecondClock(Clock source) {
    return Clock.tick(Objects.requireNonNull(source, "source"), PRECISION);
  }

  /** 返回一个可为 null 的 canonical instant，或拒绝任何包含 sub-millisecond 精度的值。 */
  public static Instant requireMillisecondPrecision(Instant value) {
    if (value != null && value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException("durable timestamps must use millisecond precision");
    }
    return value;
  }

  /** 返回适合 durable 时间运算的正整毫秒 duration。 */
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
