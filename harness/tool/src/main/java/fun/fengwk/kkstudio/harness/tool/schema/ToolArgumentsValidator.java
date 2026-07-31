package fun.fengwk.kkstudio.harness.tool.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.Map;

/** Tool 参数 JSON 的受限 schema 校验器。 */
public final class ToolArgumentsValidator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ToolArgumentsValidator() {}

  /** 确保值是有效 JSON，并返回原始文本。 */
  public static String requireValidJson(String json) {
    try {
      OBJECT_MAPPER.readTree(requireNonBlank(json, "json"));
      return json;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("json must be valid", error);
    }
  }

  /** 确保值是有效的顶层 JSON object；空参数规范化为 {@code {}}。 */
  public static String requireJsonObject(String json) {
    String normalized = json == null || json.isBlank() ? "{}" : json;
    if (!read(normalized).isObject()) {
      throw new IllegalArgumentException("argumentsJson must be a JSON object");
    }
    return normalized;
  }

  /** 验证顶层 JSON object 符合工具参数 schema。 */
  public static void validate(String argumentsJson, ToolParamsSchema schema) {
    validateObject(
        "$",
        read(requireJsonObject(argumentsJson)),
        schema.properties(),
        schema.required(),
        schema.additionalProperties());
  }

  private static JsonNode read(String json) {
    try {
      JsonNode node = OBJECT_MAPPER.readTree(json);
      if (node == null) {
        throw new IllegalArgumentException("json must not be null");
      }
      return node;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("json must be valid", error);
    }
  }

  private static void validateObject(
      String path,
      JsonNode node,
      Map<String, ToolSchemaElement> properties,
      Iterable<String> required,
      boolean additionalProperties) {
    requireType(path, node.isObject(), "object");
    for (String requiredProperty : required) {
      if (!node.has(requiredProperty)) {
        throw mismatch(path + "." + requiredProperty, "is required");
      }
    }
    if (!additionalProperties) {
      Iterator<String> names = node.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (!properties.containsKey(name)) {
          throw mismatch(path + "." + name, "is not allowed");
        }
      }
    }
    for (Map.Entry<String, ToolSchemaElement> entry : properties.entrySet()) {
      if (node.has(entry.getKey())) {
        validateElement(path + "." + entry.getKey(), node.get(entry.getKey()), entry.getValue());
      }
    }
  }

  private static void validateElement(String path, JsonNode node, ToolSchemaElement schema) {
    if (schema instanceof ToolStringSchema) {
      requireType(path, node.isTextual(), "string");
    } else if (schema instanceof ToolIntegerSchema) {
      requireType(path, node.isIntegralNumber(), "integer");
    } else if (schema instanceof ToolNumberSchema) {
      requireType(path, node.isNumber(), "number");
    } else if (schema instanceof ToolBooleanSchema) {
      requireType(path, node.isBoolean(), "boolean");
    } else if (schema instanceof ToolEnumSchema enumSchema) {
      requireType(path, node.isTextual(), "string enum");
      if (!enumSchema.values().contains(node.asText())) {
        throw mismatch(path, "must be one of " + enumSchema.values());
      }
    } else if (schema instanceof ToolArraySchema arraySchema) {
      requireType(path, node.isArray(), "array");
      for (int index = 0; index < node.size(); index++) {
        validateElement(path + "[" + index + "]", node.get(index), arraySchema.items());
      }
    } else if (schema instanceof ToolObjectSchema objectSchema) {
      validateObject(
          path,
          node,
          objectSchema.properties(),
          objectSchema.required(),
          objectSchema.additionalProperties());
    } else {
      throw new IllegalArgumentException("unsupported tool schema element: " + schema.getClass());
    }
  }

  private static void requireType(String path, boolean valid, String expected) {
    if (!valid) {
      throw mismatch(path, "must be " + expected);
    }
  }

  private static IllegalArgumentException mismatch(String path, String message) {
    return new IllegalArgumentException(
        "argumentsJson does not match inputSchema: " + path + " " + message);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
