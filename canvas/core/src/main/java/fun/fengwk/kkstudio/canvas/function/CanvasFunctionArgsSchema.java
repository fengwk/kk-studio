package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonArray;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonBool;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonNumber;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Canvas Function args 的严格 JSON Schema 子集。
 *
 * <p>Schema 根必须是 object；每个 schema 节点只允许 {@code
 * type/description/properties/required/additionalProperties/enum/default/minimum/maximum/items/minItems/maxItems}，任何其它关键字都会在注册期
 * fail closed，插件无法声明核心不校验的约束。object 必须显式声明 {@code additionalProperties: false} 与 {@code
 * properties}，因此每一层 object 的未知字段都被拒绝。
 *
 * <p>object 与 array 可以任意递归嵌套（{@code MAX_DEPTH} 内），使 args 可以表达嵌套业务参数；嵌套深度与数组长度都有界，加上 Core 对 args 的
 * JSON 深度/长度上限，冻结计划不会无界增长。
 *
 * <p>核心保留的引用值用 {@code "type":"resourceReference"} 声明，它必须恰好是 {@code {type,nodeId,index}} 形状，与 {@link
 * fun.fengwk.kkstudio.canvas.CanvasFunction} 的引用投影一致，并且可以出现在任意嵌套位置。{@code number} 允许整数与小数，{@code
 * integer} 只允许整数值。
 */
public final class CanvasFunctionArgsSchema {

  /** 单个数组参数的成员数量上限。 */
  public static final int MAX_ITEMS = 32;

  /** 嵌套 object/array 的最大深度。 */
  public static final int MAX_DEPTH = 8;

  private static final Set<String> NODE_KEYWORDS =
      Set.of(
          "type",
          "description",
          "properties",
          "required",
          "additionalProperties",
          "enum",
          "default",
          "minimum",
          "maximum",
          "items",
          "minItems",
          "maxItems");
  private static final Set<String> SCALAR_TYPES = Set.of("string", "integer", "number", "boolean");
  private static final Set<String> ARRAY_KEYWORDS = Set.of("items", "minItems", "maxItems");
  private static final Set<String> OBJECT_KEYWORDS =
      Set.of("properties", "required", "additionalProperties");
  private static final String OBJECT = "object";
  private static final String ARRAY = "array";
  private static final String REFERENCE_TYPE = "resourceReference";
  private static final Set<String> REFERENCE_FIELDS = Set.of("type", "nodeId", "index");

  private CanvasFunctionArgsSchema() {}

  /** 校验 schema 本身；关键字、类型、必填与默认值不一致时抛 {@link IllegalArgumentException}。 */
  public static void validate(JsonObject schema) {
    Objects.requireNonNull(schema, "argsSchema");
    if (!OBJECT.equals(type(schema, "argsSchema"))) {
      throw new IllegalArgumentException("argsSchema.type must be object");
    }
    validateNode(schema, "argsSchema", 0);
  }

  /**
   * 按 schema 严格校验 args，补齐 {@code default} 后返回 canonical object。
   *
   * <p>返回对象各层字段顺序与 schema 声明顺序一致，使冻结文本稳定可比；未知字段、类型错误或越界一律拒绝。
   */
  public static JsonObject normalize(JsonObject args, JsonObject schema, String field) {
    Objects.requireNonNull(args, "args");
    validate(schema);
    return (JsonObject) coerce(args, schema, field, 0);
  }

  private static void validateNode(JsonObject node, String path, int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException(path + " must not nest deeper than " + MAX_DEPTH);
    }
    requireKeywords(node, NODE_KEYWORDS, path);
    String type = type(node, path);
    optionalText(node, "description", path + ".description");
    switch (type) {
      case REFERENCE_TYPE -> requireKeywords(node, Set.of("type", "description"), path);
      case ARRAY -> validateArray(node, path, depth);
      case OBJECT -> validateObject(node, path, depth);
      default -> {
        if (SCALAR_TYPES.contains(type)) {
          validateScalar(node, path);
        } else {
          throw new IllegalArgumentException(path + ".type is unsupported: " + type);
        }
      }
    }
    if (node.values().containsKey("default")) {
      coerce(node.values().get("default"), node, path + ".default", depth);
    }
  }

  private static void validateObject(JsonObject node, String path, int depth) {
    for (String keyword : ARRAY_KEYWORDS) {
      if (node.values().containsKey(keyword)) {
        throw new IllegalArgumentException(path + " object must not declare " + keyword);
      }
    }
    requireAdditionalPropertiesFalse(node, path);
    JsonObject properties = requireObject(node, "properties", path + ".properties");
    Set<String> required = requiredNames(node, path);
    for (Map.Entry<String, CanvasJson> entry : properties.values().entrySet()) {
      String name = entry.getKey();
      if (name == null || name.isEmpty()) {
        throw new IllegalArgumentException(path + ".properties names must not be empty");
      }
      validateNode(
          requireObject(entry.getValue(), path + ".properties." + name),
          path + ".properties." + name,
          depth + 1);
    }
    for (String name : required) {
      if (!properties.values().containsKey(name)) {
        throw new IllegalArgumentException(path + ".required must name declared properties");
      }
    }
  }

  private static void validateArray(JsonObject node, String path, int depth) {
    for (String keyword : OBJECT_KEYWORDS) {
      if (node.values().containsKey(keyword)) {
        throw new IllegalArgumentException(path + " array must not declare " + keyword);
      }
    }
    if (node.values().containsKey("enum")) {
      throw new IllegalArgumentException(path + " array must not declare enum");
    }
    JsonObject items = requireObject(node, "items", path + ".items");
    validateNode(items, path + ".items", depth + 1);
    int minItems = boundedInt(node, "minItems", 0, path);
    int maxItems = boundedInt(node, "maxItems", MAX_ITEMS, path);
    if (maxItems > MAX_ITEMS) {
      throw new IllegalArgumentException(path + ".maxItems must not exceed " + MAX_ITEMS);
    }
    if (minItems > maxItems) {
      throw new IllegalArgumentException(path + ".minItems must not exceed maxItems");
    }
  }

  private static void validateScalar(JsonObject property, String path) {
    for (String keyword : ARRAY_KEYWORDS) {
      if (property.values().containsKey(keyword)) {
        throw new IllegalArgumentException(path + " scalar must not declare " + keyword);
      }
    }
    for (String keyword : OBJECT_KEYWORDS) {
      if (property.values().containsKey(keyword)) {
        throw new IllegalArgumentException(path + " scalar must not declare " + keyword);
      }
    }
    for (String keyword : List.of("minimum", "maximum")) {
      if (!property.values().containsKey(keyword)) {
        continue;
      }
      if (!"integer".equals(type(property, path)) && !"number".equals(type(property, path))) {
        throw new IllegalArgumentException(path + " minimum/maximum require a number type");
      }
      number(property.values().get(keyword), path + "." + keyword);
    }
    if (!property.values().containsKey("enum")) {
      return;
    }
    List<CanvasJson> options = requireArray(property, "enum", path + ".enum");
    if (options.isEmpty()) {
      throw new IllegalArgumentException(path + ".enum must not be empty");
    }
    Set<CanvasJson> distinct = new LinkedHashSet<>();
    for (CanvasJson option : options) {
      CanvasJson canonical =
          coerce(option, withoutKeyword(property, "enum"), path + ".enum item", 0);
      if (!distinct.add(canonical)) {
        throw new IllegalArgumentException(path + ".enum must not contain duplicates");
      }
    }
  }

  private static CanvasJson coerce(CanvasJson value, JsonObject schema, String path, int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException(path + " must not nest deeper than " + MAX_DEPTH);
    }
    String type = type(schema, path);
    return switch (type) {
      case "string" -> requireEnum(schema, requireText(value, path), path);
      case "integer" -> integer(value, schema, path);
      case "number" -> decimal(value, schema, path);
      case "boolean" -> requireBool(value, path);
      case ARRAY -> coerceArray(value, schema, path, depth);
      case OBJECT -> coerceObject(value, schema, path, depth);
      case REFERENCE_TYPE -> coerceReference(value, path);
      default -> throw new IllegalArgumentException(
          path + " has an unsupported schema type: " + type);
    };
  }

  private static JsonObject coerceObject(
      CanvasJson value, JsonObject schema, String path, int depth) {
    JsonObject object = requireObject(value, path);
    JsonObject properties = requireObject(schema, "properties", path + ".properties");
    Set<String> required = requiredNames(schema, path);
    Set<String> unknown = new LinkedHashSet<>(object.values().keySet());
    unknown.removeAll(properties.values().keySet());
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(path + " contains unknown fields: " + unknown);
    }
    Map<String, CanvasJson> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, CanvasJson> entry : properties.values().entrySet()) {
      String name = entry.getKey();
      JsonObject property = requireObject(entry.getValue(), path + ".properties." + name);
      CanvasJson child = object.values().get(name);
      if (child == null) {
        if (property.values().containsKey("default")) {
          normalized.put(name, property.values().get("default"));
        } else if (required.contains(name)) {
          throw new IllegalArgumentException(path + "." + name + " is required");
        }
        continue;
      }
      normalized.put(name, coerce(child, property, path + "." + name, depth + 1));
    }
    return new JsonObject(normalized);
  }

  private static JsonNumber integer(CanvasJson value, JsonObject property, String path) {
    BigDecimal decimal = number(value, path);
    BigDecimal integral;
    try {
      integral = BigDecimal.valueOf(decimal.intValueExact());
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException(path + " must be an integer");
    }
    enforceBounds(integral, property, path);
    return new JsonNumber(integral);
  }

  private static JsonNumber decimal(CanvasJson value, JsonObject property, String path) {
    BigDecimal decimal = number(value, path);
    enforceBounds(decimal, property, path);
    return new JsonNumber(decimal);
  }

  private static void enforceBounds(BigDecimal value, JsonObject property, String path) {
    JsonObject bounds = withoutKeyword(property, "type");
    for (String keyword : List.of("minimum", "maximum")) {
      if (!bounds.values().containsKey(keyword)) {
        continue;
      }
      BigDecimal bound = number(bounds.values().get(keyword), path + "." + keyword);
      int comparison = value.compareTo(bound);
      if ("minimum".equals(keyword) && comparison < 0) {
        throw new IllegalArgumentException(path + " must be >= " + bound.toPlainString());
      }
      if ("maximum".equals(keyword) && comparison > 0) {
        throw new IllegalArgumentException(path + " must be <= " + bound.toPlainString());
      }
    }
  }

  private static CanvasJson coerceArray(
      CanvasJson value, JsonObject schema, String path, int depth) {
    if (!(value instanceof JsonArray array)) {
      throw new IllegalArgumentException(path + " must be an array");
    }
    int minItems = boundedInt(schema, "minItems", 0, path);
    int maxItems = boundedInt(schema, "maxItems", MAX_ITEMS, path);
    if (array.values().size() < minItems || array.values().size() > maxItems) {
      throw new IllegalArgumentException(
          path + " must declare between " + minItems + " and " + maxItems + " items");
    }
    JsonObject items = requireObject(schema, "items", path + ".items");
    List<CanvasJson> normalized = new ArrayList<>(array.values().size());
    for (int index = 0; index < array.values().size(); index++) {
      normalized.add(coerce(array.values().get(index), items, path + "[" + index + "]", depth + 1));
    }
    return new JsonArray(normalized);
  }

  private static CanvasJson coerceReference(CanvasJson value, String path) {
    JsonObject reference = requireObject(value, path);
    if (!reference.values().keySet().equals(REFERENCE_FIELDS)) {
      throw new IllegalArgumentException(path + " must declare exactly type/nodeId/index");
    }
    CanvasJson type = reference.values().get("type");
    if (!(type instanceof JsonText text) || !"resource".equals(text.value())) {
      throw new IllegalArgumentException(path + ".type must be resource");
    }
    CanvasJson nodeId = reference.values().get("nodeId");
    if (!(nodeId instanceof JsonText nodeIdText)) {
      throw new IllegalArgumentException(path + ".nodeId must be a UUID string");
    }
    try {
      UUID.fromString(nodeIdText.value());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(path + ".nodeId must be a UUID string");
    }
    BigDecimal index = number(reference.values().get("index"), path + ".index");
    try {
      if (index.intValueExact() < 0) {
        throw new IllegalArgumentException(path + ".index must be nonnegative");
      }
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException(path + ".index must be an integer");
    }
    return reference;
  }

  private static CanvasJson requireEnum(JsonObject property, JsonText text, String path) {
    if (!property.values().containsKey("enum")) {
      return text;
    }
    for (CanvasJson option : requireArray(property, "enum", path + ".enum")) {
      if (option.equals(text)) {
        return text;
      }
    }
    throw new IllegalArgumentException(path + " must be one of the declared enum values");
  }

  private static JsonText requireText(CanvasJson value, String path) {
    if (!(value instanceof JsonText text)) {
      throw new IllegalArgumentException(path + " must be a string");
    }
    return text;
  }

  private static JsonBool requireBool(CanvasJson value, String path) {
    if (!(value instanceof JsonBool bool)) {
      throw new IllegalArgumentException(path + " must be a boolean");
    }
    return bool;
  }

  private static BigDecimal number(CanvasJson value, String path) {
    if (!(value instanceof JsonNumber number)) {
      throw new IllegalArgumentException(path + " must be a number");
    }
    return number.value();
  }

  private static void requireAdditionalPropertiesFalse(JsonObject node, String path) {
    CanvasJson value = node.values().get("additionalProperties");
    if (!(value instanceof JsonBool bool) || bool.value()) {
      throw new IllegalArgumentException(path + ".additionalProperties must be false");
    }
  }

  private static Set<String> requiredNames(JsonObject node, String path) {
    if (!node.values().containsKey("required")) {
      return Set.of();
    }
    Set<String> names = new LinkedHashSet<>();
    for (CanvasJson item : requireArray(node, "required", path + ".required")) {
      if (!(item instanceof JsonText text) || text.value().isBlank()) {
        throw new IllegalArgumentException(path + ".required must contain non-blank names");
      }
      if (!names.add(text.value())) {
        throw new IllegalArgumentException(path + ".required must not contain duplicates");
      }
    }
    return names;
  }

  private static String type(JsonObject schema, String path) {
    CanvasJson value = schema.values().get("type");
    if (!(value instanceof JsonText text) || text.value().isBlank()) {
      throw new IllegalArgumentException(path + ".type must be non-blank text");
    }
    return text.value();
  }

  private static void optionalText(JsonObject schema, String keyword, String path) {
    CanvasJson value = schema.values().get(keyword);
    if (value == null) {
      return;
    }
    if (!(value instanceof JsonText text) || text.value().isBlank()) {
      throw new IllegalArgumentException(path + " must be non-blank text");
    }
  }

  private static int boundedInt(JsonObject schema, String keyword, int fallback, String path) {
    CanvasJson value = schema.values().get(keyword);
    if (value == null) {
      return fallback;
    }
    BigDecimal decimal = number(value, path + "." + keyword);
    try {
      int parsed = decimal.intValueExact();
      if (parsed < 0) {
        throw new IllegalArgumentException(path + "." + keyword + " must be nonnegative");
      }
      return parsed;
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException(path + "." + keyword + " must be an integer");
    }
  }

  private static void requireKeywords(JsonObject node, Set<String> allowed, String path) {
    for (String keyword : node.values().keySet()) {
      if (!allowed.contains(keyword)) {
        throw new IllegalArgumentException(path + " contains unsupported keyword: " + keyword);
      }
    }
  }

  private static JsonObject requireObject(JsonObject node, String keyword, String path) {
    return requireObject(node.values().get(keyword), path);
  }

  private static JsonObject requireObject(CanvasJson value, String path) {
    if (!(value instanceof JsonObject object)) {
      throw new IllegalArgumentException(path + " must be an object");
    }
    return object;
  }

  private static List<CanvasJson> requireArray(JsonObject node, String keyword, String path) {
    CanvasJson value = node.values().get(keyword);
    if (!(value instanceof JsonArray array)) {
      throw new IllegalArgumentException(path + " must be an array");
    }
    return array.values();
  }

  private static JsonObject withoutKeyword(JsonObject schema, String keyword) {
    Map<String, CanvasJson> values = new LinkedHashMap<>(schema.values());
    values.remove(keyword);
    return new JsonObject(values);
  }
}
