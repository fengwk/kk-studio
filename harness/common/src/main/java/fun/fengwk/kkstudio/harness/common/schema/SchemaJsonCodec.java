package fun.fengwk.kkstudio.harness.common.schema;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 严格、deterministic 的 {@link InputSchema} 与 {@link SchemaElement} JSON 编解码器。
 *
 * <p>编码产生 canonical 确定性格式（properties 与 required 按字典序排列）；解码执行严格语法与类型校验（拒绝尾随内容、未知字段、重复字段）。
 */
public final class SchemaJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  public SchemaJsonCodec() {}

  /** 把 {@link InputSchema} 编码为 canonical input schema JSON 文本。 */
  public String encode(InputSchema schema) {
    Objects.requireNonNull(schema, "schema");
    return write(encodeNode(schema));
  }

  /** 把 {@link InputSchema} 编码为 canonical {@link ObjectNode}。 */
  public ObjectNode encodeNode(InputSchema schema) {
    Objects.requireNonNull(schema, "schema");
    return writeObjectSchema(
        schema.description(),
        schema.properties(),
        schema.required(),
        schema.additionalProperties());
  }

  /** 解码 canonical input schema JSON 文本；任何非法结构抛 {@link IllegalArgumentException}。 */
  public InputSchema decode(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed input schema JSON", error);
    }
    return decodeNode(root, "inputSchema");
  }

  /** 从 {@link JsonNode} 解码 {@link InputSchema}。 */
  public InputSchema decodeNode(JsonNode value) {
    return decodeNode(value, "inputSchema");
  }

  /** 从 {@link JsonNode} 解码 {@link InputSchema}，附带上下文信息。 */
  public InputSchema decodeNode(JsonNode value, String context) {
    Objects.requireNonNull(value, "value");
    return readInputSchema(value, context == null ? "inputSchema" : context);
  }

  /** 编码单个 {@link SchemaElement} 节点为 canonical {@link ObjectNode}。 */
  public ObjectNode encodeElement(SchemaElement element) {
    Objects.requireNonNull(element, "element");
    return writeSchema(element);
  }

  /** 从 {@link JsonNode} 解码单个 {@link SchemaElement} 节点。 */
  public SchemaElement decodeElement(JsonNode node, String context) {
    Objects.requireNonNull(node, "node");
    ObjectNode obj = requiredObject(node, context == null ? "schemaElement" : context);
    return readSchemaElement(obj, context == null ? "schemaElement" : context);
  }

  private static ObjectNode writeSchema(SchemaElement schema) {
    ObjectNode target = NODES.objectNode();
    if (schema instanceof StringSchema) {
      target.put("type", "string");
    } else if (schema instanceof IntegerSchema) {
      target.put("type", "integer");
    } else if (schema instanceof NumberSchema) {
      target.put("type", "number");
    } else if (schema instanceof BooleanSchema) {
      target.put("type", "boolean");
    } else if (schema instanceof EnumSchema enumSchema) {
      target.put("type", "string");
      ArrayNode values = target.putArray("enum");
      enumSchema.values().forEach(values::add);
    } else if (schema instanceof ArraySchema arraySchema) {
      target.put("type", "array");
      target.set("items", writeSchema(arraySchema.items()));
    } else if (schema instanceof ObjectSchema objectSchema) {
      return writeObjectSchema(
          objectSchema.description(),
          objectSchema.properties(),
          objectSchema.required(),
          objectSchema.additionalProperties());
    } else {
      throw new IllegalStateException("unsupported schema: " + schema.getClass());
    }
    if (schema.description() != null) {
      target.put("description", schema.description());
    }
    return target;
  }

  /**
   * 写出 deterministic object schema：properties 按 key 字典序排序；required 数组按字典序排序；description 缺省时省略字段。
   */
  private static ObjectNode writeObjectSchema(
      String description,
      Map<String, SchemaElement> properties,
      Set<String> required,
      boolean additionalProperties) {
    ObjectNode target = NODES.objectNode();
    target.put("type", "object");
    if (description != null) {
      target.put("description", description);
    }
    ObjectNode wireProperties = target.putObject("properties");
    TreeMap<String, SchemaElement> sortedProps = new TreeMap<>(properties);
    for (Map.Entry<String, SchemaElement> entry : sortedProps.entrySet()) {
      wireProperties.set(entry.getKey(), writeSchema(entry.getValue()));
    }
    ArrayNode wireRequired = target.putArray("required");
    TreeSet<String> sortedRequired = new TreeSet<>(required);
    sortedRequired.forEach(wireRequired::add);
    target.put("additionalProperties", additionalProperties);
    return target;
  }

  private static String write(ObjectNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode schema JSON", error);
    }
  }

  private static InputSchema readInputSchema(JsonNode node, String context) {
    ObjectNode obj = requiredObject(node, context);
    Set<String> allowed =
        Set.of("type", "description", "properties", "required", "additionalProperties");
    rejectUnknownFields(obj, allowed, context);
    readObjectSchemaShape(obj, context);
    String description = optionalText(obj, "description", context);
    JsonNode propertiesNode = obj.get("properties");
    ObjectNode propertiesObject = requiredObject(propertiesNode, context + ".properties");
    Set<String> requiredNames = readRequiredArray(obj, context);
    boolean additionalProperties = readAdditionalProperties(obj, context);
    Map<String, SchemaElement> properties = new LinkedHashMap<>();
    Iterator<String> declaredNames = propertiesObject.fieldNames();
    if (declaredNames != null) {
      while (declaredNames.hasNext()) {
        String propertyName = declaredNames.next();
        JsonNode child = propertiesObject.get(propertyName);
        if (child == null || !child.isObject()) {
          throw new IllegalArgumentException(
              context + " property '" + propertyName + "' must be an object");
        }
        properties.put(
            propertyName,
            readSchemaElement((ObjectNode) child, context + " property '" + propertyName + "'"));
      }
    }
    for (String requiredName : requiredNames) {
      if (!properties.containsKey(requiredName)) {
        throw new IllegalArgumentException(
            context + " 'required' entry '" + requiredName + "' is not declared in 'properties'");
      }
    }
    return constructInputSchema(description, properties, requiredNames, additionalProperties);
  }

  private static InputSchema constructInputSchema(
      String description,
      Map<String, SchemaElement> properties,
      Set<String> required,
      boolean additionalProperties) {
    try {
      return new InputSchema(description, properties, required, additionalProperties);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "inputSchema validation failed: " + error.getMessage(), error);
    }
  }

  private static SchemaElement readSchemaElement(ObjectNode node, String context) {
    String type = requiredText(node, "type", context);
    String description = optionalText(node, "description", context);
    switch (type) {
      case "string" -> {
        Set<String> allowed = Set.of("type", "description", "enum");
        rejectUnknownFields(node, allowed, context);
        JsonNode enumNode = node.get("enum");
        if (enumNode == null) {
          return constructStringSchema(description);
        }
        if (!enumNode.isArray()) {
          throw new IllegalArgumentException(context + " 'enum' must be an array");
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int enumIndex = 0;
        for (JsonNode entry : enumNode) {
          if (!entry.isTextual()) {
            throw new IllegalArgumentException(
                context + " 'enum'[" + enumIndex + "] must be a string");
          }
          String text = entry.textValue();
          if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                context + " 'enum'[" + enumIndex + "] must be non-blank");
          }
          if (!seen.add(text)) {
            throw new IllegalArgumentException(
                context + " 'enum' contains duplicate entry: " + text);
          }
          values.add(text);
          enumIndex++;
        }
        return constructEnumSchema(description, values);
      }
      case "integer" -> {
        Set<String> allowed = Set.of("type", "description");
        rejectUnknownFields(node, allowed, context);
        return constructIntegerSchema(description);
      }
      case "number" -> {
        Set<String> allowed = Set.of("type", "description");
        rejectUnknownFields(node, allowed, context);
        return constructNumberSchema(description);
      }
      case "boolean" -> {
        Set<String> allowed = Set.of("type", "description");
        rejectUnknownFields(node, allowed, context);
        return constructBooleanSchema(description);
      }
      case "array" -> {
        Set<String> allowed = Set.of("type", "description", "items");
        rejectUnknownFields(node, allowed, context);
        JsonNode items = node.get("items");
        ObjectNode itemsObject = requiredObject(items, context + " items");
        SchemaElement itemsSchema = readSchemaElement(itemsObject, context + " items");
        return constructArraySchema(description, itemsSchema);
      }
      case "object" -> {
        Set<String> allowed =
            Set.of("type", "description", "properties", "required", "additionalProperties");
        rejectUnknownFields(node, allowed, context);
        readObjectSchemaShape(node, context);
        JsonNode propsNode = node.get("properties");
        ObjectNode propertiesObject = requiredObject(propsNode, context + " properties");
        Set<String> requiredNames = readRequiredArray(node, context);
        boolean additionalProperties = readAdditionalProperties(node, context);
        Map<String, SchemaElement> properties = new LinkedHashMap<>();
        Iterator<String> declaredNames = propertiesObject.fieldNames();
        if (declaredNames != null) {
          while (declaredNames.hasNext()) {
            String propertyName = declaredNames.next();
            JsonNode child = propertiesObject.get(propertyName);
            if (child == null || !child.isObject()) {
              throw new IllegalArgumentException(
                  context + " property '" + propertyName + "' must be an object");
            }
            properties.put(
                propertyName,
                readSchemaElement(
                    (ObjectNode) child, context + " property '" + propertyName + "'"));
          }
        }
        for (String requiredName : requiredNames) {
          if (!properties.containsKey(requiredName)) {
            throw new IllegalArgumentException(
                context
                    + " 'required' entry '"
                    + requiredName
                    + "' is not declared in 'properties'");
          }
        }
        return constructObjectSchema(description, properties, requiredNames, additionalProperties);
      }
      default -> throw new IllegalArgumentException(context + " unknown schema type: " + type);
    }
  }

  private static StringSchema constructStringSchema(String description) {
    try {
      return new StringSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("string schema invalid: " + error.getMessage(), error);
    }
  }

  private static EnumSchema constructEnumSchema(String description, List<String> values) {
    try {
      return new EnumSchema(description, List.copyOf(values));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("enum schema invalid: " + error.getMessage(), error);
    }
  }

  private static IntegerSchema constructIntegerSchema(String description) {
    try {
      return new IntegerSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("integer schema invalid: " + error.getMessage(), error);
    }
  }

  private static NumberSchema constructNumberSchema(String description) {
    try {
      return new NumberSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("number schema invalid: " + error.getMessage(), error);
    }
  }

  private static BooleanSchema constructBooleanSchema(String description) {
    try {
      return new BooleanSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("boolean schema invalid: " + error.getMessage(), error);
    }
  }

  private static ArraySchema constructArraySchema(String description, SchemaElement items) {
    try {
      return new ArraySchema(description, items);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("array schema invalid: " + error.getMessage(), error);
    }
  }

  private static ObjectSchema constructObjectSchema(
      String description,
      Map<String, SchemaElement> properties,
      Set<String> required,
      boolean additionalProperties) {
    try {
      return new ObjectSchema(description, properties, required, additionalProperties);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("object schema invalid: " + error.getMessage(), error);
    }
  }

  private static void readObjectSchemaShape(ObjectNode node, String context) {
    JsonNode typeNode = node.get("type");
    if (typeNode == null || !typeNode.isTextual() || !"object".equals(typeNode.textValue())) {
      throw new IllegalArgumentException(context + " schema type must be 'object'");
    }
    if (node.get("properties") == null || node.get("properties").isNull()) {
      throw new IllegalArgumentException(context + " must declare 'properties'");
    }
    if (node.get("required") == null || node.get("required").isNull()) {
      throw new IllegalArgumentException(context + " must declare 'required'");
    }
    if (node.get("additionalProperties") == null || node.get("additionalProperties").isNull()) {
      throw new IllegalArgumentException(context + " must declare 'additionalProperties'");
    }
  }

  private static Set<String> readRequiredArray(ObjectNode node, String context) {
    JsonNode required = node.get("required");
    if (required == null || required.isNull()) {
      throw new IllegalArgumentException(context + " 'required' must be a JSON array");
    }
    if (!required.isArray()) {
      throw new IllegalArgumentException(context + " 'required' must be an array");
    }
    Set<String> names = new HashSet<>();
    int index = 0;
    for (JsonNode entry : required) {
      if (!entry.isTextual()) {
        throw new IllegalArgumentException(context + " 'required'[" + index + "] must be a string");
      }
      String text = entry.textValue();
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException(
            context + " 'required'[" + index + "] must be non-blank");
      }
      if (!names.add(text)) {
        throw new IllegalArgumentException(
            context + " 'required' contains duplicate entry: " + text);
      }
      index++;
    }
    return names;
  }

  private static boolean readAdditionalProperties(ObjectNode node, String context) {
    JsonNode additional = node.get("additionalProperties");
    if (additional == null || additional.isNull()) {
      throw new IllegalArgumentException(context + " 'additionalProperties' must be present");
    }
    if (!additional.isBoolean()) {
      throw new IllegalArgumentException(context + " 'additionalProperties' must be boolean");
    }
    return additional.booleanValue();
  }

  private static void rejectUnknownFields(ObjectNode node, Set<String> allowed, String context) {
    Iterator<String> fields = node.fieldNames();
    if (fields == null) {
      return;
    }
    while (fields.hasNext()) {
      String name = fields.next();
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException(context + " unknown field: '" + name + "'");
      }
    }
  }

  private static String requiredText(JsonNode node, String field, String context) {
    String value = optionalText(node, field, context);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(context + " '" + field + "' must be a non-blank string");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(context + " '" + field + "' must be a string");
    }
    return value.textValue();
  }

  private static ObjectNode requiredObject(JsonNode node, String context) {
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException(context + " must be a JSON object");
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(
          context
              + " must be a JSON object but was "
              + node.getNodeType().name().toLowerCase(Locale.ROOT));
    }
    return (ObjectNode) node;
  }
}
