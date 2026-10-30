package fun.fengwk.kkstudio.harness.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pricing 规则条件。同一个条件对象的多个字段为 AND；{@code all}/{@code any}/{@code not} 提供有限的布尔组合。
 *
 * <p>条件只做纯判定，不携带三态结果。缺少判定所需的事实（例如请求时间或最终 service tier）由调用方把整个费用归零， 不得猜测事实后继续计算。
 *
 * <p>{@code all} 缺省或空均恒真；{@code any}/{@code serviceTier} 缺省不约束，显式空列表恒假。缺省与显式空列表的 差异必须保留，不能折叠为同一个值。
 */
public record ModelPricingCondition(
    Context context,
    List<String> serviceTier,
    DateRange date,
    TimeWindow time,
    RequestBody requestBody,
    List<ModelPricingCondition> all,
    List<ModelPricingCondition> any,
    ModelPricingCondition not) {

  public static final int MAX_LIST_SIZE = 64;
  public static final int MAX_DEPTH = 8;

  /** 不依赖任何事实的恒真条件。 */
  public static final ModelPricingCondition ALWAYS =
      new ModelPricingCondition(null, null, null, null, null, null, null, null);

  /** 兼容未使用 request-body 条件的调用方；语义等价于 requestBody=null。 */
  public ModelPricingCondition(
      Context context,
      List<String> serviceTier,
      DateRange date,
      TimeWindow time,
      List<ModelPricingCondition> all,
      List<ModelPricingCondition> any,
      ModelPricingCondition not) {
    this(context, serviceTier, date, time, null, all, any, not);
  }

  public ModelPricingCondition {
    serviceTier = copyServiceTiers(serviceTier);
    all = copyConditions(all, "all");
    any = copyConditions(any, "any");
    validateDepth(all, any, not, 0);
  }

  /** Context token 边界；上下界同时存在时必须保持可满足。 */
  public record Context(Integer gt, Integer gte, Integer lt, Integer lte) {

    public Context {
      requireNonNegative(gt, "context.gt");
      requireNonNegative(gte, "context.gte");
      requireNonNegative(lt, "context.lt");
      requireNonNegative(lte, "context.lte");
      if (gt != null && gte != null) {
        throw new IllegalArgumentException("context must not declare both gt and gte");
      }
      if (lt != null && lte != null) {
        throw new IllegalArgumentException("context must not declare both lt and lte");
      }
      if (gt != null && lt != null && gt >= lt) {
        throw new IllegalArgumentException("context.gt must be less than context.lt");
      }
      if (gt != null && lte != null && gt >= lte) {
        throw new IllegalArgumentException("context.gt must be less than context.lte");
      }
      if (gte != null && lt != null && gte >= lt) {
        throw new IllegalArgumentException("context.gte must be less than context.lt");
      }
      if (gte != null && lte != null && gte > lte) {
        throw new IllegalArgumentException("context.gte must not exceed context.lte");
      }
    }
  }

  /** 左闭右开的 UTC 日期区间；任一缺省表示该侧不设边界。 */
  public record DateRange(LocalDate gte, LocalDate lt) {

    public DateRange {
      if (gte != null && lt != null && !gte.isBefore(lt)) {
        throw new IllegalArgumentException("date.gte must be before date.lt");
      }
    }
  }

  /**
   * 每日左闭右开时间窗。{@code 00:00-24:00} 使用 {@code start=end=00:00} 表示全天；wire 只接受 {@code 00:00-24:00}，不接受
   * {@code 00:00-00:00}。跨午夜窗口按开始日归属星期。
   */
  public record TimeWindow(
      LocalTime start, LocalTime end, ZoneId timezone, List<Integer> weekdays) {

    public TimeWindow {
      Objects.requireNonNull(start, "time.start");
      Objects.requireNonNull(end, "time.end");
      Objects.requireNonNull(timezone, "time.timezone");
      if (start.equals(end) && !start.equals(LocalTime.MIDNIGHT)) {
        throw new IllegalArgumentException("time.start and time.end must differ unless all day");
      }
      weekdays = copyWeekdays(weekdays);
    }

    /** 内部用 00:00-00:00 表示显式全天；该形态不出现在 wire 中。 */
    public boolean allDay() {
      return start.equals(LocalTime.MIDNIGHT) && end.equals(LocalTime.MIDNIGHT);
    }
  }

  /**
   * 对最终编码后 Provider JSON 请求体的一个 typed scalar 相等条件。Path 是 RFC 6901 JSON Pointer，非空且以 {@code /}
   * 开头；只接受标量 string/number/boolean/null 的精确相等，不做大小写或类型转换。
   *
   * <p>{@code canonicalValue} 是条件指纹使用的规范值：string 原样，number 为无指数、去尾零的普通十进制文本，boolean 为 {@code
   * true}/{@code false}，null 为空文本。请求快照只保存该条件的指纹与布尔结果，不保存本对象。
   */
  public record RequestBody(String path, ScalarType type, String canonicalValue) {

    public static final int MAX_PATH_BYTES = 512;
    public static final int MAX_PATH_SEGMENTS = 16;
    public static final int MAX_STRING_BYTES = 1024;
    public static final int MAX_NUMBER_PRECISION = 64;
    public static final int MAX_NUMBER_SCALE = 18;

    private static final Pattern JSON_NUMBER =
        Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    public RequestBody {
      path = Objects.requireNonNull(path, "requestBody.path");
      type = Objects.requireNonNull(type, "requestBody.type");
      canonicalValue = Objects.requireNonNull(canonicalValue, "requestBody.value");
      validatePath(path);
      canonicalValue =
          switch (type) {
            case STRING -> {
              if (canonicalValue.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
                throw new IllegalArgumentException(
                    "requestBody.equals string must not exceed "
                        + MAX_STRING_BYTES
                        + " UTF-8 bytes");
              }
              yield canonicalValue;
            }
            case NUMBER -> canonicalNumber(canonicalValue);
            case BOOLEAN -> {
              if (!"true".equals(canonicalValue) && !"false".equals(canonicalValue)) {
                throw new IllegalArgumentException(
                    "requestBody.equals boolean must be true or false");
              }
              yield canonicalValue;
            }
            case NULL -> {
              if (!canonicalValue.isEmpty()) {
                throw new IllegalArgumentException(
                    "requestBody.equals null must use an empty value");
              }
              yield "";
            }
          };
    }

    /** 已解码的 pointer token 序列；空属性名保留为空 token。 */
    public List<String> pathSegments() {
      String[] encoded = path.substring(1).split("/", -1);
      List<String> decoded = new ArrayList<>(encoded.length);
      for (String segment : encoded) {
        decoded.add(segment.replace("~1", "/").replace("~0", "~"));
      }
      return List.copyOf(decoded);
    }

    public static RequestBody stringValue(String path, String value) {
      return new RequestBody(path, ScalarType.STRING, value);
    }

    public static RequestBody numberValue(String path, String jsonNumber) {
      return new RequestBody(path, ScalarType.NUMBER, jsonNumber);
    }

    public static RequestBody booleanValue(String path, boolean value) {
      return new RequestBody(path, ScalarType.BOOLEAN, Boolean.toString(value));
    }

    public static RequestBody nullValue(String path) {
      return new RequestBody(path, ScalarType.NULL, "");
    }

    private static void validatePath(String path) {
      if (path.isEmpty() || path.charAt(0) != '/') {
        throw new IllegalArgumentException("requestBody.path must be a non-empty JSON Pointer");
      }
      int segments = 0;
      for (int index = 0; index < path.length(); index++) {
        char current = path.charAt(index);
        if (current == '/') {
          segments++;
          continue;
        }
        if (current == '~') {
          if (index + 1 >= path.length()
              || (path.charAt(index + 1) != '0' && path.charAt(index + 1) != '1')) {
            throw new IllegalArgumentException("requestBody.path only allows ~0 and ~1 escapes");
          }
          index++;
        }
      }
      if (segments == 0 || segments > MAX_PATH_SEGMENTS) {
        throw new IllegalArgumentException(
            "requestBody.path must contain between 1 and " + MAX_PATH_SEGMENTS + " segments");
      }
      if (path.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
        throw new IllegalArgumentException(
            "requestBody.path must not exceed " + MAX_PATH_BYTES + " UTF-8 bytes");
      }
    }

    private static String canonicalNumber(String value) {
      if (!JSON_NUMBER.matcher(value).matches()) {
        throw new IllegalArgumentException("requestBody.equals number must be a JSON number");
      }
      BigDecimal number;
      try {
        number = new BigDecimal(value);
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("requestBody.equals number is out of range", error);
      }
      BigDecimal normalized = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
      if (normalized.precision() > MAX_NUMBER_PRECISION) {
        throw new IllegalArgumentException(
            "requestBody.equals number precision must not exceed " + MAX_NUMBER_PRECISION);
      }
      int scale = normalized.scale();
      if (scale < -MAX_NUMBER_SCALE || scale > MAX_NUMBER_SCALE) {
        throw new IllegalArgumentException(
            "requestBody.equals number scale must be between -"
                + MAX_NUMBER_SCALE
                + " and "
                + MAX_NUMBER_SCALE);
      }
      return normalized.toPlainString();
    }
  }

  /** request-body 期望值的 JSON 标量类型。 */
  public enum ScalarType {
    STRING("string"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    NULL("null");

    private final String wireName;

    ScalarType(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }
  }

  private static List<String> copyServiceTiers(List<String> serviceTier) {
    if (serviceTier == null) {
      return null;
    }
    if (serviceTier.isEmpty()) {
      return List.of();
    }
    if (serviceTier.size() > MAX_LIST_SIZE) {
      throw new IllegalArgumentException(
          "serviceTier must not exceed " + MAX_LIST_SIZE + " values");
    }
    Set<String> unique = new HashSet<>();
    List<String> copy = new ArrayList<>(serviceTier.size());
    for (String tier : serviceTier) {
      if (tier == null || tier.isBlank()) {
        throw new IllegalArgumentException("serviceTier values must not be blank");
      }
      if (!unique.add(tier)) {
        throw new IllegalArgumentException("serviceTier contains duplicate value: " + tier);
      }
      copy.add(tier);
    }
    return List.copyOf(copy);
  }

  private static List<ModelPricingCondition> copyConditions(
      List<ModelPricingCondition> conditions, String name) {
    if (conditions == null) {
      return null;
    }
    if (conditions.isEmpty()) {
      return List.of();
    }
    if (conditions.size() > MAX_LIST_SIZE) {
      throw new IllegalArgumentException(
          name + " must not exceed " + MAX_LIST_SIZE + " conditions");
    }
    List<ModelPricingCondition> copy = new ArrayList<>(conditions.size());
    for (ModelPricingCondition condition : conditions) {
      copy.add(Objects.requireNonNull(condition, name + " condition"));
    }
    return List.copyOf(copy);
  }

  private static List<Integer> copyWeekdays(List<Integer> weekdays) {
    if (weekdays == null || weekdays.isEmpty()) {
      return List.of();
    }
    if (weekdays.size() > 7) {
      throw new IllegalArgumentException("time.weekdays must not exceed 7 values");
    }
    Set<Integer> unique = new HashSet<>();
    List<Integer> copy = new ArrayList<>(weekdays.size());
    for (Integer weekday : weekdays) {
      if (weekday == null || weekday < 1 || weekday > 7) {
        throw new IllegalArgumentException("time.weekdays values must be between 1 and 7");
      }
      if (!unique.add(weekday)) {
        throw new IllegalArgumentException("time.weekdays contains duplicate value: " + weekday);
      }
      copy.add(weekday);
    }
    return List.copyOf(copy);
  }

  private void validateDepth(int depth) {
    validateDepth(all, any, not, depth);
  }

  private static void validateDepth(
      List<ModelPricingCondition> allConditions,
      List<ModelPricingCondition> anyConditions,
      ModelPricingCondition notCondition,
      int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("condition nesting must not exceed " + MAX_DEPTH);
    }
    if (allConditions != null) {
      for (ModelPricingCondition condition : allConditions) {
        condition.validateDepth(depth + 1);
      }
    }
    if (anyConditions != null) {
      for (ModelPricingCondition condition : anyConditions) {
        condition.validateDepth(depth + 1);
      }
    }
    if (notCondition != null) {
      notCondition.validateDepth(depth + 1);
    }
  }

  private static void requireNonNegative(Integer value, String name) {
    if (value != null && value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
