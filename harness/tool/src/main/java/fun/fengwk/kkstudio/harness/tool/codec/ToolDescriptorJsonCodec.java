package fun.fengwk.kkstudio.harness.tool.codec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolSchemaElement;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
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
 * 严格、deterministic 的 {@link ToolDescriptor} 与 tool input schema JSON 编解码。
 *
 * <p>Descriptor 只描述模型可见的工具契约，不携带执行路由；路由由 catalog 的 {@code AgentToolBackend} 和 durable binding 的
 * route 字段负责。编解码复用同一个底层 {@link ObjectMapper}，后者启用了 {@link
 * DeserializationFeature#FAIL_ON_TRAILING_TOKENS} 与 {@link
 * JsonParser.Feature#STRICT_DUPLICATE_DETECTION}，从而在边界拒绝 trailing token 与 duplicate field。
 *
 * <p>字段访问为逐字段 JsonNode 读：每个对象都先取出允许字段集合，未知字段直接抛 {@link IllegalArgumentException}；类型不符（如 non-string
 * 的 enum 元素）同样抛出。
 *
 * <p>object schema 的 {@code properties} 按 key 字典序输出，{@code required} 数组按字典序输出； enum
 * 值保留输入顺序（语义上是枚举选项而非集合）。本 codec 不维护 descriptor 列表的外部顺序—— 调用方需自行 canonical 化。
 */
public final class ToolDescriptorJsonCodec {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  static {
    MAPPER.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    MAPPER.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  /** 把单个 descriptor 编码为 deterministic canonical JSON 文本。 */
  public String encode(ToolDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    return write(encodeNode(descriptor));
  }

  /** 把单个 descriptor 编码为 deterministic canonical {@link ObjectNode}；String API 委派此方法。 */
  public ObjectNode encodeNode(ToolDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    ObjectNode node = NODES.objectNode();
    writeDescriptor(node, descriptor);
    return node;
  }

  /** 解码单个 canonical descriptor JSON 文本；任何非法结构抛 {@link IllegalArgumentException}。 */
  public ToolDescriptor decode(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed tool descriptor JSON", error);
    }
    return decodeNode(root);
  }

  /** 从任意 {@link JsonNode} 解码 descriptor；非对象节点抛 {@link IllegalArgumentException}。 */
  public ToolDescriptor decodeNode(JsonNode value) {
    Objects.requireNonNull(value, "value");
    return readDescriptor(value);
  }

  /** 把 {@link ToolParamsSchema} 编码为 canonical input schema JSON 文本。 */
  public String encodeInputSchema(ToolParamsSchema schema) {
    Objects.requireNonNull(schema, "schema");
    return write(writeParamsSchema(schema));
  }

  /**
   * 从 canonical input schema JSON 文本解码 {@link ToolParamsSchema}；任何非法结构抛 {@link
   * IllegalArgumentException}。
   */
  public ToolParamsSchema decodeInputSchema(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("malformed tool input schema JSON", error);
    }
    return readParamsSchema(root, "inputSchema");
  }

  private static void writeDescriptor(ObjectNode target, ToolDescriptor descriptor) {
    target.put("name", descriptor.name());
    target.put("version", descriptor.version());
    target.put("description", descriptor.description());
    target.put("rendererKey", descriptor.rendererKey());
    target.put("sideEffect", descriptor.sideEffect().name());
    target.put("timeoutMillis", descriptor.timeout().toMillis());
    target.set("inputSchema", writeParamsSchema(descriptor.inputSchema()));
  }

  private static ObjectNode writeParamsSchema(ToolParamsSchema schema) {
    return writeObjectSchema(
        schema.description(),
        schema.properties(),
        schema.required(),
        schema.additionalProperties());
  }

  private static ObjectNode writeSchema(ToolSchemaElement schema) {
    ObjectNode target = NODES.objectNode();
    if (schema instanceof ToolStringSchema) {
      target.put("type", "string");
    } else if (schema instanceof ToolIntegerSchema) {
      target.put("type", "integer");
    } else if (schema instanceof ToolNumberSchema) {
      target.put("type", "number");
    } else if (schema instanceof ToolBooleanSchema) {
      target.put("type", "boolean");
    } else if (schema instanceof ToolEnumSchema enumSchema) {
      target.put("type", "string");
      ArrayNode values = target.putArray("enum");
      enumSchema.values().forEach(values::add);
    } else if (schema instanceof ToolArraySchema arraySchema) {
      target.put("type", "array");
      target.set("items", writeSchema(arraySchema.items()));
    } else if (schema instanceof ToolObjectSchema objectSchema) {
      return writeObjectSchema(
          objectSchema.description(),
          objectSchema.properties(),
          objectSchema.required(),
          objectSchema.additionalProperties());
    } else {
      throw new IllegalStateException("unsupported tool schema: " + schema.getClass());
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
      Map<String, ToolSchemaElement> properties,
      Set<String> required,
      boolean additionalProperties) {
    ObjectNode target = NODES.objectNode();
    target.put("type", "object");
    if (description != null) {
      target.put("description", description);
    }
    ObjectNode wireProperties = target.putObject("properties");
    TreeMap<String, ToolSchemaElement> sortedProps = new TreeMap<>(properties);
    for (Map.Entry<String, ToolSchemaElement> entry : sortedProps.entrySet()) {
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
      throw new IllegalStateException("cannot encode tool descriptor JSON", error);
    }
  }

  private static ToolDescriptor readDescriptor(JsonNode node) {
    ObjectNode obj = requiredObject(node, "descriptor");
    Set<String> allowed =
        Set.of(
            "name",
            "version",
            "description",
            "rendererKey",
            "sideEffect",
            "timeoutMillis",
            "inputSchema");
    rejectUnknownFields(obj, allowed, "descriptor");
    String name = requiredText(obj, "name", "descriptor");
    String version = requiredText(obj, "version", "descriptor");
    String description = requiredText(obj, "description", "descriptor");
    String rendererKey = requiredText(obj, "rendererKey", "descriptor");
    ToolSideEffect sideEffect = readSideEffect(obj);
    long timeoutMillis = requiredNonNegativeLong(obj, "timeoutMillis", "descriptor");
    JsonNode inputSchemaNode = requiredField(obj, "inputSchema", "descriptor");
    ToolParamsSchema inputSchema = readParamsSchema(inputSchemaNode, "descriptor inputSchema");
    return constructDescriptor(
        name,
        version,
        description,
        rendererKey,
        inputSchema,
        sideEffect,
        Duration.ofMillis(timeoutMillis));
  }

  private static ToolDescriptor constructDescriptor(
      String name,
      String version,
      String description,
      String rendererKey,
      ToolParamsSchema inputSchema,
      ToolSideEffect sideEffect,
      Duration timeout) {
    try {
      return new ToolDescriptor(
          name, version, description, rendererKey, inputSchema, sideEffect, timeout);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "descriptor validation failed for " + name + "@" + version + ": " + error.getMessage(),
          error);
    }
  }

  private static ToolSideEffect readSideEffect(ObjectNode obj) {
    String value = requiredText(obj, "sideEffect", "descriptor");
    try {
      return ToolSideEffect.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("descriptor unknown sideEffect: " + value, error);
    }
  }

  private static long requiredNonNegativeLong(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new IllegalArgumentException(
          context + " '" + field + "' must be a non-negative long integer");
    }
    return value.longValue();
  }

  private static ToolParamsSchema readParamsSchema(JsonNode node, String context) {
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
    Map<String, ToolSchemaElement> properties = new LinkedHashMap<>();
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
    return constructParamsSchema(description, properties, requiredNames, additionalProperties);
  }

  private static ToolParamsSchema constructParamsSchema(
      String description,
      Map<String, ToolSchemaElement> properties,
      Set<String> required,
      boolean additionalProperties) {
    try {
      return new ToolParamsSchema(description, properties, required, additionalProperties);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "inputSchema validation failed: " + error.getMessage(), error);
    }
  }

  private static ToolSchemaElement readSchemaElement(ObjectNode node, String context) {
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
        ToolSchemaElement itemsSchema = readSchemaElement(itemsObject, context + " items");
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
        Map<String, ToolSchemaElement> properties = new LinkedHashMap<>();
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

  private static ToolStringSchema constructStringSchema(String description) {
    try {
      return new ToolStringSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("string schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolEnumSchema constructEnumSchema(String description, List<String> values) {
    try {
      return new ToolEnumSchema(description, List.copyOf(values));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("enum schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolIntegerSchema constructIntegerSchema(String description) {
    try {
      return new ToolIntegerSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("integer schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolNumberSchema constructNumberSchema(String description) {
    try {
      return new ToolNumberSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("number schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolBooleanSchema constructBooleanSchema(String description) {
    try {
      return new ToolBooleanSchema(description);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("boolean schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolArraySchema constructArraySchema(String description, ToolSchemaElement items) {
    try {
      return new ToolArraySchema(description, items);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("array schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolObjectSchema constructObjectSchema(
      String description,
      Map<String, ToolSchemaElement> properties,
      Set<String> required,
      boolean additionalProperties) {
    try {
      return new ToolObjectSchema(description, properties, required, additionalProperties);
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

  private static JsonNode requiredField(JsonNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(context + " must declare '" + field + "'");
    }
    return value;
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

  /** 严格的对象类型守卫：null / 数组 / 标量都直接抛 {@link IllegalArgumentException}。 */
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
