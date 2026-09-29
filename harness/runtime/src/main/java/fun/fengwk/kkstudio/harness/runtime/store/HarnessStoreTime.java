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

  /** 返回适合 durable 时间运算的非负整毫秒 duration；零表示立即，合法。负值、亚毫秒精度与毫秒范围溢出都被拒绝。 */
  public static Duration requireNonNegativeWholeMillisecondDuration(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    if (value.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException(name + " must use whole milliseconds");
    }
    try {
      value.toMillis();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(name + " must fit the millisecond range", overflow);
    }
    return value;
  }

  /** 将候选时间抬升到一组 durable 时间下界中的最大值；所有参数都必须非 null。 */
  public static Instant notBefore(Instant candidate, Instant... floors) {
    Instant effective = Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(floors, "floors");
    for (Instant floor : floors) {
      Instant requiredFloor = Objects.requireNonNull(floor, "floor");
      if (requiredFloor.isAfter(effective)) {
        effective = requiredFloor;
      }
    }
    return effective;
  }
}
