package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingContextConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDateConditionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingTimeConditionDTO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * models.dev 定价条件（{@code when}）的解析：context、service_tier、date、utc_hours/utc_days 与 all/any/not 组合。
 *
 * <p>地区倍率不属于 token 价格，遇到时只做形状校验并置 {@code hasRegion}，由调用方整条忽略该倍率；其余未知条件一律拒绝导入该候选价格。
 */
final class ModelsDevPricingConditionParser {

  private static final Set<String> CONTEXT_KEYS = Set.of("gt", "gte", "lt", "lte");
  private static final Set<String> STRING_FILTER_KEYS = Set.of("equals", "in");
  private static final Set<String> DATE_KEYS = Set.of("gte", "lt");
  private static final Set<String> UTC_HOURS_KEYS = Set.of("gte", "lt");
  private static final Set<String> UTC_DAYS_KEYS = Set.of("in", "equals");
  private static final int MAX_DEPTH = 8;

  private ModelsDevPricingConditionParser() {}

  /**
   * 解析条件；{@code importContext} 非空时允许 {@code region} 并记录其出现，为空时拒绝。
   *
   * <p>嵌套深度上限独立于 {@code ModelPricingCondition.MAX_DEPTH}：外部输入在归一化之前就必须有界，避免栈耗尽。
   */
  static ModelPricingConditionDTO parse(JsonNode node, RegionImportContext importContext) {
    return parse(node, 0, importContext);
  }

  private static ModelPricingConditionDTO parse(
      JsonNode node, int depth, RegionImportContext importContext) {
    if (depth > MAX_DEPTH) {
      throw new CatalogPriceException("pricing condition nesting is too deep");
    }
    if (node == null || node.isNull()) {
      return new ModelPricingConditionDTO();
    }
    JsonNode when = CatalogJson.requireObject(node, "pricing condition");
    ModelPricingConditionDTO condition = new ModelPricingConditionDTO();
    for (String key : when.propertyNames()) {
      applyKey(condition, key, when, depth, importContext);
    }
    if (when.has("utc_hours") || when.has("utc_days")) {
      condition.setTime(time(when.get("utc_hours"), when.get("utc_days")));
    }
    return condition;
  }

  private static void applyKey(
      ModelPricingConditionDTO condition,
      String key,
      JsonNode when,
      int depth,
      RegionImportContext importContext) {
    switch (key) {
      case "region" -> {
        if (importContext == null) {
          throw new CatalogPriceException("unsupported pricing condition: region");
        }
        stringFilter(when.get("region"), "condition.region");
        importContext.hasRegion = true;
      }
      case "context" -> condition.setContext(context(when.get("context")));
      case "service_tier" -> condition.setServiceTier(
          stringFilter(when.get("service_tier"), "condition.service_tier"));
      case "date" -> condition.setDate(date(when.get("date")));
      case "utc_hours" -> {
        // utc_days 与 utc_hours 共同构成同一个时间窗，因此在循环之后合并处理。
      }
      case "utc_days" -> {
        // 与 utc_hours 一起在循环之后合并处理。
      }
      case "all" -> condition.setAll(conditions(when.get("all"), depth + 1, importContext));
      case "any" -> condition.setAny(conditions(when.get("any"), depth + 1, importContext));
      case "not" -> {
        JsonNode child = when.get("not");
        if (child == null || child.isNull()) {
          throw new CatalogPriceException("condition.not must be an object");
        }
        condition.setNot(parse(child, depth + 1, importContext));
      }
      default -> throw new CatalogPriceException("unsupported pricing condition: " + key);
    }
  }

  private static ModelPricingContextConditionDTO context(JsonNode node) {
    JsonNode context = CatalogJson.requireObject(node, "condition.context");
    validateKeys(context, CONTEXT_KEYS, "condition.context");
    ModelPricingContextConditionDTO dto = new ModelPricingContextConditionDTO();
    dto.setGt(CatalogJson.nonNegativeInt(context.get("gt"), "condition.context.gt"));
    dto.setGte(CatalogJson.nonNegativeInt(context.get("gte"), "condition.context.gte"));
    dto.setLt(CatalogJson.nonNegativeInt(context.get("lt"), "condition.context.lt"));
    dto.setLte(CatalogJson.nonNegativeInt(context.get("lte"), "condition.context.lte"));
    return dto;
  }

  private static List<String> stringFilter(JsonNode node, String name) {
    JsonNode filter = CatalogJson.requireObject(node, name);
    validateKeys(filter, STRING_FILTER_KEYS, name);
    List<String> values = new ArrayList<>();
    JsonNode equals = filter.get("equals");
    if (equals != null && !equals.isNull()) {
      addUnique(values, requiredText(equals, name + ".equals"));
    }
    JsonNode in = filter.get("in");
    if (in != null && !in.isNull()) {
      if (!in.isArray()) {
        throw new CatalogPriceException(name + ".in must be an array");
      }
      for (JsonNode value : in) {
        addUnique(values, requiredText(value, name + ".in value"));
      }
    }
    if (values.isEmpty()) {
      throw new CatalogPriceException(name + " requires equals or in");
    }
    return values;
  }

  private static ModelPricingDateConditionDTO date(JsonNode node) {
    JsonNode date = CatalogJson.requireObject(node, "condition.date");
    validateKeys(date, DATE_KEYS, "condition.date");
    ModelPricingDateConditionDTO dto = new ModelPricingDateConditionDTO();
    dto.setGte(dateText(date.get("gte"), "condition.date.gte"));
    dto.setLt(dateText(date.get("lt"), "condition.date.lt"));
    if (dto.getGte() != null && dto.getLt() != null) {
      LocalDate gte = LocalDate.parse(dto.getGte());
      LocalDate lt = LocalDate.parse(dto.getLt());
      if (!gte.isBefore(lt)) {
        throw new CatalogPriceException("condition.date.gte must be before condition.date.lt");
      }
    }
    return dto;
  }

  private static String dateText(JsonNode node, String name) {
    String value = textOrNull(node);
    if (value == null) {
      return null;
    }
    try {
      LocalDate.parse(value);
      return value;
    } catch (DateTimeParseException error) {
      throw new CatalogPriceException(name + " must be YYYY-MM-DD");
    }
  }

  private static ModelPricingTimeConditionDTO time(JsonNode hours, JsonNode days) {
    ModelPricingTimeConditionDTO dto = new ModelPricingTimeConditionDTO();
    Window hoursWindow = hoursWindow(hours);
    dto.setStart(hoursWindow.start());
    dto.setEnd(hoursWindow.end());
    dto.setTimezone("UTC");
    if (days != null && !days.isNull()) {
      dto.setWeekdays(weekdays(days));
    }
    return dto;
  }

  private static Window hoursWindow(JsonNode hours) {
    if (hours == null || hours.isNull()) {
      return new Window("00:00", "24:00");
    }
    JsonNode window = CatalogJson.requireObject(hours, "condition.utc_hours");
    validateKeys(window, UTC_HOURS_KEYS, "condition.utc_hours");
    String gte = timeText(window.get("gte"), "condition.utc_hours.gte", false);
    String lt = timeText(window.get("lt"), "condition.utc_hours.lt", true);
    if (gte == null && lt == null) {
      throw new CatalogPriceException("condition.utc_hours requires gte or lt");
    }
    return new Window(gte == null ? "00:00" : gte, lt == null ? "24:00" : lt);
  }

  private static List<Integer> weekdays(JsonNode days) {
    JsonNode dayNode = CatalogJson.requireObject(days, "condition.utc_days");
    validateKeys(dayNode, UTC_DAYS_KEYS, "condition.utc_days");
    List<Integer> weekdays = new ArrayList<>();
    JsonNode in = dayNode.get("in");
    if (in != null && !in.isNull()) {
      if (!in.isArray()) {
        throw new CatalogPriceException("condition.utc_days.in must be an array");
      }
      for (JsonNode value : in) {
        addUnique(weekdays, weekday(requiredText(value, "condition.utc_days value")));
      }
    }
    JsonNode equals = dayNode.get("equals");
    if (equals != null && !equals.isNull()) {
      addUnique(weekdays, weekday(requiredText(equals, "condition.utc_days.equals")));
    }
    if (weekdays.isEmpty()) {
      throw new CatalogPriceException("condition.utc_days requires in or equals");
    }
    return weekdays;
  }

  private static String timeText(JsonNode node, String name, boolean allowEndOfDay) {
    String value = textOrNull(node);
    if (value == null) {
      return null;
    }
    if ("24:00".equals(value)) {
      if (!allowEndOfDay) {
        throw new CatalogPriceException(name + " must be a time before 24:00");
      }
      return value;
    }
    try {
      LocalTime.parse(value);
      return value;
    } catch (DateTimeParseException error) {
      throw new CatalogPriceException(name + " must be HH:mm");
    }
  }

  private static int weekday(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "mon", "monday" -> 1;
      case "tue", "tuesday" -> 2;
      case "wed", "wednesday" -> 3;
      case "thu", "thursday" -> 4;
      case "fri", "friday" -> 5;
      case "sat", "saturday" -> 6;
      case "sun", "sunday" -> 7;
      default -> throw new CatalogPriceException("unsupported weekday: " + value);
    };
  }

  private static List<ModelPricingConditionDTO> conditions(
      JsonNode node, int depth, RegionImportContext importContext) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isArray()) {
      throw new CatalogPriceException("condition all/any must be an array");
    }
    List<ModelPricingConditionDTO> conditions = new ArrayList<>();
    for (JsonNode item : node) {
      conditions.add(parse(item, depth, importContext));
    }
    return conditions;
  }

  private static void validateKeys(JsonNode node, Set<String> allowed, String name) {
    for (String key : node.propertyNames()) {
      if (!allowed.contains(key)) {
        throw new CatalogPriceException("unsupported v2 " + name + " field: " + key);
      }
    }
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return requiredText(node, "condition value");
  }

  private static String requiredText(JsonNode node, String name) {
    if (node == null || !node.isString() || node.stringValue().isBlank()) {
      throw new CatalogPriceException(name + " must be non-blank text");
    }
    return node.stringValue();
  }

  private static <T> void addUnique(List<T> values, T value) {
    if (!values.contains(value)) {
      values.add(value);
    }
  }

  private record Window(String start, String end) {}

  /** 倍率导入上下文：记录该倍率是否带地区条件，带地区条件的倍率整条忽略。 */
  static final class RegionImportContext {
    private boolean hasRegion;

    boolean hasRegion() {
      return hasRegion;
    }
  }
}
