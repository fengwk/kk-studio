package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.model.ModelPricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 计价 wire 值与领域值之间的共享解析：时间/日期/日期窗、TTL 秒数、数值边界与统一错误构造。
 *
 * <p>校验（{@link ModelPricingValidator}）与转换（{@link ModelPricingMapper}）必须对同一份 wire
 * 文本给出同一结论，因此这些规则只在这里定义一次；两边各自复制会漂移出 “校验通过但转换失败”的旁路。
 */
final class ModelPricingWireValues {

  private ModelPricingWireValues() {}

  static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("invalid agent model: " + message);
  }

  static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException("invalid agent model: " + message, cause);
  }

  static void requireNonBlank(String value, String path) {
    if (value == null || value.isBlank()) {
      throw invalid(path + " must be a non-blank string");
    }
  }

  static LocalDate parseOptionalDate(String value, String path) {
    return value == null ? null : parseDate(value, path);
  }

  private static LocalDate parseDate(String value, String path) {
    requireNonBlank(value, path);
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException error) {
      throw invalid(path + " must be YYYY-MM-DD");
    }
  }

  private static LocalTime parseTime(String value, String path) {
    requireNonBlank(value, path);
    try {
      return LocalTime.parse(value);
    } catch (DateTimeParseException error) {
      throw invalid(path + " must be HH:mm");
    }
  }

  /**
   * Wire 时间窗左闭右开；全天必须显式写成 00:00-24:00，并在内部规范为 00:00-00:00。end=24:00 可配任意合法 start 表示当日 24:00 右边界；不接受
   * start=24:00，也不接受 start=end（含 00:00-00:00）被误读为全天。
   */
  static TimeBounds parseTimeBounds(String startValue, String endValue, String path) {
    if ("24:00".equals(startValue)) {
      throw invalid(path + ".start must be before 24:00");
    }
    LocalTime start = parseTime(startValue, path + ".start");
    if ("24:00".equals(endValue)) {
      return new TimeBounds(start, LocalTime.MIDNIGHT);
    }
    LocalTime end = parseTime(endValue, path + ".end");
    if (start.equals(end)) {
      throw invalid(path + ".start and end must differ; use 00:00-24:00 for all day");
    }
    return new TimeBounds(start, end);
  }

  /** TTL 键是正整数字符串的秒数；小数、负数、溢出与非数字共用同一条错误。 */
  static long parseTtl(String value, String path) {
    try {
      long ttl = Long.parseLong(value);
      if (ttl > 0) {
        return ttl;
      }
    } catch (NumberFormatException error) {
      // fall through to the single public error below
    }
    throw invalid(path + " keys must be positive integer seconds");
  }

  static void requireOptionalNonNegative(BigDecimal value, String path) {
    if (value == null) {
      return;
    }
    if (value.signum() < 0) {
      throw invalid(path + " must not be negative");
    }
    if (value.precision() > ModelPricing.MAX_DECIMAL_PRECISION) {
      throw invalid(path + " precision must not exceed " + ModelPricing.MAX_DECIMAL_PRECISION);
    }
    if (value.scale() > ModelPricing.MAX_DECIMAL_SCALE) {
      throw invalid(path + " scale must not exceed " + ModelPricing.MAX_DECIMAL_SCALE);
    }
  }

  static void requireOptionalNonNegativeInt(Integer value, String path) {
    if (value != null && value < 0) {
      throw invalid(path + " must not be negative");
    }
  }

  /** 用领域构造函数做边界校验，并把其 {@link IllegalArgumentException} 翻成带路径的统一错误。 */
  static void ensureCondition(Runnable constructor, String path) {
    try {
      constructor.run();
    } catch (IllegalArgumentException error) {
      throw invalid(path + " is invalid: " + error.getMessage(), error);
    }
  }

  record TimeBounds(LocalTime start, LocalTime end) {}
}
