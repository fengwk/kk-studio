package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

/** 模型目录适配器共享的只读 JSON 工具；错误消息只描述字段，不回显整份外部数据。 */
final class CatalogJson {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private CatalogJson() {}

  static JsonNode parse(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      if (root == null || !root.isObject()) {
        throw new CatalogPriceException("catalog root must be a JSON object");
      }
      return root;
    } catch (JacksonException error) {
      throw new CatalogPriceException("catalog response is not valid JSON");
    }
  }

  static JsonNode requireObject(JsonNode value, String name) {
    if (value == null || !value.isObject()) {
      throw new CatalogPriceException(name + " must be an object");
    }
    return value;
  }

  static String text(JsonNode value, String fallback) {
    return value != null && value.isString() && !value.stringValue().isBlank()
        ? value.stringValue()
        : fallback;
  }

  static BigDecimal decimal(JsonNode value, String name) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isNumber()) {
      throw new CatalogPriceException(name + " must be a decimal number");
    }
    BigDecimal decimal = value.decimalValue();
    if (decimal.signum() < 0) {
      throw new CatalogPriceException(name + " must not be negative");
    }
    return decimal;
  }

  static Integer nonNegativeInt(JsonNode value, String name) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
      throw new CatalogPriceException(name + " must be a non-negative integer");
    }
    return value.intValue();
  }

  static Boolean bool(JsonNode value, String name) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isBoolean()) {
      throw new CatalogPriceException(name + " must be a boolean");
    }
    return value.booleanValue();
  }
}
