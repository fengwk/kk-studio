package fun.fengwk.kkstudio.platform.catalog.mcp.discovery;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.NumberSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaElement;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 远端 MCP input schema JSON 到 Platform canonical input schema 的转换器。
 *
 * <p>MCP 工具的 {@code inputSchema} 是标准 JSON Schema（顶层 object）。Platform ToolDescriptor 只接受受限的
 * canonical 子集（type/description/properties/required/additionalProperties），因此转换时把完整 JSON Schema 规范化为
 * canonical 文本：未声明的叶子类型统一映射为宽松 string，无法表达的组合结构收敛为空 properties object—— 参数校验因此始终可执行，而远端自由 schema
 * 不会绕过 Platform 校验门禁。
 */
public final class McpInputSchemaConverter {

  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  private McpInputSchemaConverter() {}

  public static String toCanonicalJson(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw new AiValidationException("mcp_server", "input schema must be a JSON object");
    }
    return SCHEMA_CODEC.encode(convertObject(root, "mcp_server"));
  }

  /**
   * 把远端 input schema JSON 文本转换为 canonical input schema JSON；输入必须是 JSON object。
   *
   * @throws AiValidationException 输入非法（非 JSON object 或不可解析）
   */
  public static String toCanonicalJson(String inputSchemaJson, String context) {
    JsonNode root;
    try {
      root = JsonValues.readTree(inputSchemaJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(context, context + " input schema must be valid JSON", error);
    }
    if (root == null || !root.isObject()) {
      throw new AiValidationException(context, context + " input schema must be a JSON object");
    }
    return SCHEMA_CODEC.encode(convertObject(root, context));
  }

  /** 转换后的 canonical schema（供测试断言）。 */
  public static InputSchema toCanonicalSchema(String inputSchemaJson, String context) {
    JsonNode root = JsonValues.readTree(inputSchemaJson);
    return convertObject(root, context);
  }

  private static InputSchema convertObject(JsonNode node, String context) {
    String description = textOrNull(node.get("description"));
    Map<String, SchemaElement> properties = new LinkedHashMap<>();
    Set<String> required = new LinkedHashSet<>();
    JsonNode propertiesNode = node.get("properties");
    if (propertiesNode != null && propertiesNode.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = propertiesNode.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        properties.put(field.getKey(), convertElement(field.getValue()));
      }
    }
    JsonNode requiredNode = node.get("required");
    if (requiredNode != null && requiredNode.isArray()) {
      for (JsonNode entry : requiredNode) {
        if (entry.isTextual() && properties.containsKey(entry.asText())) {
          required.add(entry.asText());
        }
      }
    }
    boolean additionalProperties = true;
    JsonNode additional = node.get("additionalProperties");
    if (additional != null && additional.isBoolean()) {
      additionalProperties = additional.asBoolean();
    } else if (additional == null && propertiesNode != null && propertiesNode.isObject()) {
      // 标准 JSON Schema 默认允许 additional properties。
      additionalProperties = true;
    }
    try {
      return new InputSchema(description, properties, required, additionalProperties);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          context, context + " input schema is invalid: " + error.getMessage(), error);
    }
  }

  private static SchemaElement convertElement(JsonNode node) {
    String description = textOrNull(node.get("description"));
    if (node.has("enum") && node.get("enum").isArray()) {
      List<String> values = new ArrayList<>();
      for (JsonNode val : node.get("enum")) {
        if (val.isTextual() && !val.asText().isBlank()) {
          values.add(val.asText());
        }
      }
      if (!values.isEmpty()) {
        return new EnumSchema(description, values);
      }
    }
    String type =
        node.has("type") && node.get("type").isTextual() ? node.get("type").asText() : null;
    return switch (type == null ? "" : type) {
      case "string" -> new StringSchema(description);
      case "integer" -> new IntegerSchema(description);
      case "number" -> new NumberSchema(description);
      case "boolean" -> new BooleanSchema(description);
      case "array" -> {
        JsonNode items = node.get("items");
        yield new ArraySchema(
            description,
            items == null || items.isNull() ? new StringSchema(null) : convertElement(items));
      }
      case "object" -> convertNestedObject(node);
      default ->
      // 无 type / 未识别组合结构统一收敛为宽松 string 叶子。
      new StringSchema(description);
    };
  }

  /** 嵌套 object：Platform ObjectSchema 同为 SchemaElement，直接复用 object 转换。 */
  private static ObjectSchema convertNestedObject(JsonNode node) {
    InputSchema schema = convertObject(node, "nested object schema");
    return new ObjectSchema(
        schema.description(),
        schema.properties(),
        schema.required(),
        schema.additionalProperties());
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isNull() || !node.isTextual()) {
      return null;
    }
    String value = node.asText();
    return value == null || value.isBlank() ? null : value;
  }
}
