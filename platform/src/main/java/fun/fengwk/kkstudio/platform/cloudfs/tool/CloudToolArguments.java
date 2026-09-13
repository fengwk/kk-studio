package fun.fengwk.kkstudio.platform.cloudfs.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

/**
 * 负责 Cloud 工具 JSON 参数的严格类型校验与解析。
 *
 * <p>禁止静默类型强转（如字符串转数字、浮点数转整数或整数转布尔），畸形 JSON 与类型不符均转换为标准非法参数异常。
 */
final class CloudToolArguments {

  private CloudToolArguments() {}

  static JsonNode parse(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("arguments must not be null or blank");
    }
    JsonNode node;
    try {
      node = JsonValues.readTree(argumentsJson);
    } catch (Exception e) {
      throw new IllegalArgumentException("Malformed JSON arguments: unable to parse JSON");
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Arguments must be a JSON object");
    }
    return node;
  }

  static String requireString(JsonNode node, String fieldName) {
    if (!node.has(fieldName) || node.get(fieldName).isNull()) {
      throw new IllegalArgumentException(fieldName + " must be provided");
    }
    JsonNode val = node.get(fieldName);
    if (!val.isTextual()) {
      throw new IllegalArgumentException(fieldName + " must be a string");
    }
    return val.textValue();
  }

  static String optionalString(JsonNode node, String fieldName, String defaultValue) {
    if (!node.has(fieldName) || node.get(fieldName).isNull()) {
      return defaultValue;
    }
    JsonNode val = node.get(fieldName);
    if (!val.isTextual()) {
      throw new IllegalArgumentException(fieldName + " must be a string");
    }
    return val.textValue();
  }

  static long requirePositiveLong(JsonNode node, String fieldName) {
    if (!node.has(fieldName) || node.get(fieldName).isNull()) {
      throw new IllegalArgumentException(fieldName + " must be provided");
    }
    JsonNode val = node.get(fieldName);
    if (!val.isIntegralNumber() || !val.canConvertToLong()) {
      throw new IllegalArgumentException(fieldName + " must be an integer");
    }
    long v = val.longValue();
    if (v <= 0) {
      throw new IllegalArgumentException(fieldName + " must be positive (> 0)");
    }
    return v;
  }

  static long requireNonNegativeLong(JsonNode node, String fieldName) {
    if (!node.has(fieldName) || node.get(fieldName).isNull()) {
      throw new IllegalArgumentException(fieldName + " must be provided");
    }
    JsonNode val = node.get(fieldName);
    if (!val.isIntegralNumber() || !val.canConvertToLong()) {
      throw new IllegalArgumentException(fieldName + " must be an integer");
    }
    long v = val.longValue();
    if (v < 0) {
      throw new IllegalArgumentException(fieldName + " must be non-negative (>= 0)");
    }
    return v;
  }

  static int optionalBoundedInt(
      JsonNode node, String fieldName, int defaultValue, int min, int max) {
    if (!node.has(fieldName) || node.get(fieldName).isNull()) {
      return defaultValue;
    }
    JsonNode val = node.get(fieldName);
    if (!val.isIntegralNumber() || !val.canConvertToInt()) {
      throw new IllegalArgumentException(fieldName + " must be an integer");
    }
    int v = val.intValue();
    if (v < min || v > max) {
      throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
    }
    return v;
  }

  static boolean optionalBoolean(JsonNode node, String fieldName, boolean defaultValue) {
    if (!node.has(fieldName) || node.get(fieldName).isNull()) {
      return defaultValue;
    }
    JsonNode val = node.get(fieldName);
    if (!val.isBoolean()) {
      throw new IllegalArgumentException(fieldName + " must be a boolean");
    }
    return val.booleanValue();
  }
}
