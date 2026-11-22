package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.kkstudio.harness.model.ModelPricing;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

/**
 * 计价 wire 值与领域值之间的共享解析：日期、时间点、时区、TTL 秒数、数值边界与统一错误构造。
 *
 * <p>校验（{@link ModelPricingValidator}）与转换（{@link ModelPricingMapper}）必须对同一份 wire 文本给出同一结论，
 * 因此这些规则只在这里定义一次；两边各自复制会漂移出 “校验通过但转换失败” 的旁路。
 */
final class ModelPricingWireValues {

  /** Wire 时间固定 24 小时制 HH:mm（无秒、无小数）；右边界额外接受 24:00。 */
  private static final Pattern TIME_OF_DAY = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d");

  private static final String END_OF_DAY = "24:00";

  private static final int MINUTES_PER_DAY = 1440;

  private ModelPricingWireValues() {}

  static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("invalid agent model: " + message);
  }

  static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException("invalid agent model: " + message, cause);
  }

  static LocalDate requireDate(String value, String path) {
    try {
      return LocalDate.parse(value);
    } catch (DateTimeException | NullPointerException error) {
      throw invalid(path + " must be YYYY-MM-DD", error);
    }
  }

  /**
   * 时间窗的一端规范为当日分钟数：固定 HH:mm；{@code end} 额外接受 24:00（当日右边界，规范为 1440），{@code start} 不接受 24:00。全天必须显式写成
   * 00:00-24:00，不把 00:00-00:00 误读为全天。
   */
  static int requireMinute(String value, boolean end, String path) {
    if (end && END_OF_DAY.equals(value)) {
      return MINUTES_PER_DAY;
    }
    if (value == null || !TIME_OF_DAY.matcher(value).matches()) {
      throw invalid(path + " must be HH:mm" + (end ? " or 24:00" : ""));
    }
    LocalTime time = LocalTime.parse(value);
    return time.getHour() * 60 + time.getMinute();
  }

  static ZoneId requireZone(String value, String path) {
    try {
      return ZoneId.of(value);
    } catch (DateTimeException | NullPointerException error) {
      throw invalid(path + " must be a valid IANA zone", error);
    }
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
}
