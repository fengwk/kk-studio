package fun.fengwk.kkstudio.harness.common.schema;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/** 输入参数 JSON 的受限 schema 校验器。 */
public final class InputValidator {

  private InputValidator() {}

  /** 验证顶层 JSON object 符合输入参数 schema。 */
  public static void validate(String argumentsJson, InputSchema schema) {
    Objects.requireNonNull(schema, "schema");
    validateObject(
        "$",
        JsonValues.readTree(JsonValues.requireJsonObject(argumentsJson, "argumentsJson")),
        schema.properties(),
        schema.required(),
        schema.additionalProperties());
  }

  private static void validateObject(
      String path,
      JsonNode node,
      Map<String, SchemaElement> properties,
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
    for (Map.Entry<String, SchemaElement> entry : properties.entrySet()) {
      if (node.has(entry.getKey())) {
        validateElement(path + "." + entry.getKey(), node.get(entry.getKey()), entry.getValue());
      }
    }
  }

  private static void validateElement(String path, JsonNode node, SchemaElement schema) {
    if (schema instanceof StringSchema) {
      requireType(path, node.isTextual(), "string");
    } else if (schema instanceof IntegerSchema) {
      requireType(path, node.isIntegralNumber(), "integer");
    } else if (schema instanceof NumberSchema) {
      requireType(path, node.isNumber(), "number");
    } else if (schema instanceof BooleanSchema) {
      requireType(path, node.isBoolean(), "boolean");
    } else if (schema instanceof EnumSchema enumSchema) {
      requireType(path, node.isTextual(), "string enum");
      if (!enumSchema.values().contains(node.asText())) {
        throw mismatch(path, "must be one of " + enumSchema.values());
      }
    } else if (schema instanceof ArraySchema arraySchema) {
      requireType(path, node.isArray(), "array");
      for (int index = 0; index < node.size(); index++) {
        validateElement(path + "[" + index + "]", node.get(index), arraySchema.items());
      }
    } else if (schema instanceof ObjectSchema objectSchema) {
      validateObject(
          path,
          node,
          objectSchema.properties(),
          objectSchema.required(),
          objectSchema.additionalProperties());
    } else {
      throw new IllegalArgumentException("unsupported schema element: " + schema.getClass());
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
}
