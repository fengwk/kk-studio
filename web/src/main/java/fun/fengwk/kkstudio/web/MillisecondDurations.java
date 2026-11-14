package fun.fengwk.kkstudio.web;

import java.time.Duration;
import java.util.Objects;

/**
 * Web 调度参数的正整毫秒边界：notification poll/backoff 与目录对账周期共用同一份判定。
 *
 * <p>这里只接受能精确表示为整毫秒的正 Duration：零、负值、亚毫秒精度与超出 {@code long} 纳秒范围的取值都在配置边界拒绝，绝不向下取整成 0
 * 或静默截断。失败消息只包含调用方给出的参数名，不回显取值。
 */
public final class MillisecondDurations {

  private static final long NANOS_PER_MILLI = 1_000_000L;

  private MillisecondDurations() {}

  /**
   * 返回该 duration 的整毫秒数，或在它不能被精确表示时抛出 {@link IllegalArgumentException}。
   *
   * @throws IllegalArgumentException 值为 null、零、负数、含亚毫秒精度或超出纳秒范围
   */
  public static long requirePositiveWholeMillis(Duration value, String name) {
    Objects.requireNonNull(value, name);
    long nanos;
    try {
      nanos = value.toNanos();
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException(name + " is too large", error);
    }
    if (nanos <= 0 || nanos % NANOS_PER_MILLI != 0) {
      throw new IllegalArgumentException(name + " must be a positive whole-millisecond duration");
    }
    return nanos / NANOS_PER_MILLI;
  }
}
