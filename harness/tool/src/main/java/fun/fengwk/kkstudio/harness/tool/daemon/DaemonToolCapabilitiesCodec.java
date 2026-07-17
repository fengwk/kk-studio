package fun.fengwk.kkstudio.harness.tool.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Daemon v1 {@code CAPABILITIES} payload 的共享严格 codec。
 *
 * <p>Cloud 与 Daemon 必须使用同一个 codec 把 {@link ToolDescriptor} 编码为 canonical wire JSON，并在收到该 JSON
 * 时还原为相同的 descriptor，避免 Cloud 侧的 capability 解析与 Daemon 侧的发序列化在结构上产生漂移。
 *
 * <p>codec 在边界拒绝：
 *
 * <ul>
 *   <li>未知顶层字段、未知 descriptor / schema 字段、未知 schema {@code type}；
 *   <li>{@link ToolExecutionMode} 不是 {@code ENVIRONMENT} 的 descriptor（cloud 只能寻址 Environment）；
 *   <li>重复的 {@code name@version} capability；
 *   <li>错误的 JSON 类型（如非 string 的 enum / required 元素，非 array 的 enum / required，非 object 的 properties
 *       / items）；
 *   <li>object schema 缺失 {@code type=object} / {@code properties} / {@code required} / {@code
 *       additionalProperties}；
 *   <li>duplicate required property names；
 *   <li>required 属性不在 {@code properties} 中声明；
 *   <li>{@link ToolDescriptor} / {@link ToolParamsSchema} / {@link ToolObjectSchema} / {@link
 *       ToolEnumSchema} 的构造器校验失败（如 blank tool name）会被包装为 {@link DaemonProtocolException}。
 * </ul>
 *
 * <p>空 capabilities ({@code {"tools":[]}}) 是合法 payload：新注册的 Environment 与尚未暴露工具的 daemon 都可以 表示这种状态。
 *
 * <p>{@link #encode(DaemonToolCapabilities)} 是 deterministic 的：object 属性按字典序排序，{@code required}
 * 数组按字典序排序；descriptor 列表保留输入顺序（即 daemon 声明的 capability 顺序）。
 */
public final class DaemonToolCapabilitiesCodec {

  /** 顶层 JSON 容器：{@code {"tools":[...]}}。 */
  public record DaemonToolCapabilities(List<ToolDescriptor> tools) {
    public DaemonToolCapabilities {
      tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
      Map<String, ToolDescriptor> seen = new LinkedHashMap<>();
      for (ToolDescriptor descriptor : tools) {
        if (descriptor == null) {
          throw new DaemonProtocolException("CAPABILITIES descriptor must not be null");
        }
        if (descriptor.executionMode() != ToolExecutionMode.ENVIRONMENT) {
          throw new DaemonProtocolException(
              "CAPABILITIES descriptor executionMode must be ENVIRONMENT: "
                  + descriptor.name()
                  + "@"
                  + descriptor.version());
        }
        String key = descriptor.name() + "@" + descriptor.version();
        if (seen.putIfAbsent(key, descriptor) != null) {
          throw new DaemonProtocolException("duplicate CAPABILITIES descriptor: " + key);
        }
      }
    }
  }

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** 将 {@link DaemonToolCapabilities} 编码为 deterministic canonical CAPABILITIES JSON 文本。 */
  public String encode(DaemonToolCapabilities capabilities) {
    Objects.requireNonNull(capabilities, "capabilities");
    ObjectNode root = OBJECT_MAPPER.createObjectNode();
    ArrayNode tools = root.putArray("tools");
    for (ToolDescriptor descriptor : capabilities.tools()) {
      writeDescriptor(tools.addObject(), descriptor);
    }
    try {
      return OBJECT_MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("cannot encode daemon CAPABILITIES payload", error);
    }
  }

  /** 解码单个 canonical CAPABILITIES JSON 文本。 */
  public DaemonToolCapabilities decode(String json) {
    ObjectNode root = requiredObject(readRoot(json), "CAPABILITIES");
    Set<String> allowedTop = Set.of("tools");
    rejectUnknownFields(root, allowedTop, "CAPABILITIES");
    JsonNode toolsNode = root.get("tools");
    if (toolsNode == null) {
      throw new DaemonProtocolException("CAPABILITIES payload must declare 'tools'");
    }
    if (!toolsNode.isArray()) {
      throw new DaemonProtocolException("CAPABILITIES payload 'tools' must be an array");
    }
    Map<String, ToolDescriptor> seen = new LinkedHashMap<>();
    List<ToolDescriptor> result = new ArrayList<>();
    int index = 0;
    for (JsonNode element : toolsNode) {
      ToolDescriptor descriptor = readDescriptor(element, index++);
      String key = descriptor.name() + "@" + descriptor.version();
      if (seen.putIfAbsent(key, descriptor) != null) {
        throw new DaemonProtocolException("duplicate CAPABILITIES descriptor: " + key);
      }
      result.add(descriptor);
    }
    return new DaemonToolCapabilities(List.copyOf(result));
  }

  private static void writeDescriptor(ObjectNode target, ToolDescriptor descriptor) {
    target.put("name", descriptor.name());
    target.put("version", descriptor.version());
    target.put("description", descriptor.description());
    target.put("rendererKey", descriptor.rendererKey());
    target.put("executionMode", descriptor.executionMode().name());
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
    ObjectNode target = OBJECT_MAPPER.createObjectNode();
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
      throw new DaemonProtocolException("unsupported tool schema: " + schema.getClass());
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
    ObjectNode target = OBJECT_MAPPER.createObjectNode();
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
    Set<String> sortedRequired = new TreeSet<>(required);
    sortedRequired.forEach(wireRequired::add);
    target.put("additionalProperties", additionalProperties);
    return target;
  }

  private static ToolDescriptor readDescriptor(JsonNode node, int index) {
    String context = "CAPABILITIES descriptor[" + index + "]";
    ObjectNode obj = requiredObject(node, context);
    Set<String> allowed =
        Set.of(
            "name",
            "version",
            "description",
            "rendererKey",
            "executionMode",
            "sideEffect",
            "timeoutMillis",
            "inputSchema");
    rejectUnknownFields(obj, allowed, context);
    String name = requiredText(obj, "name", context);
    String version = requiredText(obj, "version", context);
    String description = requiredText(obj, "description", context);
    String rendererKey = requiredText(obj, "rendererKey", context);
    ToolExecutionMode executionMode = readExecutionMode(obj, context);
    if (executionMode != ToolExecutionMode.ENVIRONMENT) {
      throw new DaemonProtocolException(
          context + " executionMode must be ENVIRONMENT: " + name + "@" + version);
    }
    ToolSideEffect sideEffect = readSideEffect(obj, context);
    Duration timeout = Duration.ofMillis(requiredNonNegativeLong(obj, "timeoutMillis", context));
    JsonNode inputSchemaNode = obj.get("inputSchema");
    if (inputSchemaNode == null) {
      throw new DaemonProtocolException(
          context + " must declare 'inputSchema': " + name + "@" + version);
    }
    ToolParamsSchema inputSchema = readParamsSchema((JsonNode) inputSchemaNode, context);
    return constructDescriptor(
        name, version, description, rendererKey, inputSchema, executionMode, sideEffect, timeout);
  }

  /**
   * 通过校验器构造 {@link ToolDescriptor}，把任何 {@link IllegalArgumentException} 包装为带上下文的 {@link
   * DaemonProtocolException}，避免暴露 raw 校验消息。
   */
  private static ToolDescriptor constructDescriptor(
      String name,
      String version,
      String description,
      String rendererKey,
      ToolParamsSchema inputSchema,
      ToolExecutionMode executionMode,
      ToolSideEffect sideEffect,
      Duration timeout) {
    try {
      return new ToolDescriptor(
          name, version, description, rendererKey, inputSchema, executionMode, sideEffect, timeout);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(
          "CAPABILITIES descriptor validation failed for "
              + name
              + "@"
              + version
              + ": "
              + error.getMessage(),
          error);
    }
  }

  private static ToolExecutionMode readExecutionMode(ObjectNode obj, String context) {
    String value = requiredText(obj, "executionMode", context);
    try {
      return ToolExecutionMode.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(context + " unknown executionMode: " + value, error);
    }
  }

  private static ToolSideEffect readSideEffect(ObjectNode obj, String context) {
    String value = requiredText(obj, "sideEffect", context);
    try {
      return ToolSideEffect.valueOf(value);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException(context + " unknown sideEffect: " + value, error);
    }
  }

  private static long requiredNonNegativeLong(ObjectNode obj, String field, String context) {
    JsonNode value = obj.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToLong()
        || value.longValue() < 0) {
      throw new DaemonProtocolException(
          context + " '" + field + "' must be a non-negative long integer");
    }
    return value.longValue();
  }

  private static ToolParamsSchema readParamsSchema(JsonNode node, String context) {
    ObjectNode obj = requiredObject(node, context + " inputSchema");
    Set<String> allowed =
        Set.of("type", "description", "properties", "required", "additionalProperties");
    rejectUnknownFields(obj, allowed, context + " inputSchema");
    readObjectSchemaShape(obj, context + " inputSchema");
    String description = optionalText(obj, "description", context + " inputSchema");
    JsonNode propertiesNode = obj.get("properties");
    ObjectNode propertiesObject =
        requiredObject(propertiesNode, context + " inputSchema.properties");
    Set<String> requiredNames = readRequiredArray(obj, context + " inputSchema");
    boolean additionalProperties = readAdditionalProperties(obj, context + " inputSchema");
    Map<String, ToolSchemaElement> properties = new LinkedHashMap<>();
    Iterator<String> declaredNames = propertiesObject.fieldNames();
    if (declaredNames != null) {
      while (declaredNames.hasNext()) {
        String name = declaredNames.next();
        JsonNode child = propertiesObject.get(name);
        if (child == null) {
          throw new DaemonProtocolException(
              context + " inputSchema property '" + name + "' must be an object");
        }
        if (!child.isObject()) {
          throw new DaemonProtocolException(
              context + " inputSchema property '" + name + "' must be an object");
        }
        properties.put(
            name,
            readSchemaElement(
                (ObjectNode) child, context + " inputSchema property '" + name + "'"));
      }
    }
    for (String requiredName : requiredNames) {
      if (!properties.containsKey(requiredName)) {
        throw new DaemonProtocolException(
            context
                + " inputSchema 'required' entry '"
                + requiredName
                + "' is not declared in 'properties'");
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
      throw new DaemonProtocolException(
          "inputSchema validation failed: " + error.getMessage(), error);
    }
  }

  private static ToolSchemaElement readSchemaElement(ObjectNode node, String context) {
    if (node == null) {
      throw new DaemonProtocolException(context + " must be an object");
    }
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
          throw new DaemonProtocolException(context + " 'enum' must be an array");
        }
        List<String> values = new ArrayList<>();
        int enumIndex = 0;
        for (JsonNode entry : enumNode) {
          if (!entry.isTextual()) {
            throw new DaemonProtocolException(
                context + " 'enum'[" + enumIndex + "] must be a string");
          }
          String text = entry.textValue();
          if (text == null || text.isBlank()) {
            throw new DaemonProtocolException(
                context + " 'enum'[" + enumIndex + "] must be non-blank");
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
            String name = declaredNames.next();
            JsonNode child = propertiesObject.get(name);
            if (child == null || !child.isObject()) {
              throw new DaemonProtocolException(
                  context + " property '" + name + "' must be an object");
            }
            properties.put(
                name, readSchemaElement((ObjectNode) child, context + " property '" + name + "'"));
          }
        }
        for (String requiredName : requiredNames) {
          if (!properties.containsKey(requiredName)) {
            throw new DaemonProtocolException(
                context
                    + " 'required' entry '"
                    + requiredName
                    + "' is not declared in 'properties'");
          }
        }
        return constructObjectSchema(description, properties, requiredNames, additionalProperties);
      }
      default -> throw new DaemonProtocolException(context + " unknown schema type: " + type);
    }
  }

  private static ToolStringSchema constructStringSchema(String description) {
    try {
      return new ToolStringSchema(description);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("string schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolEnumSchema constructEnumSchema(String description, List<String> values) {
    try {
      return new ToolEnumSchema(description, List.copyOf(values));
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("enum schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolIntegerSchema constructIntegerSchema(String description) {
    try {
      return new ToolIntegerSchema(description);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("integer schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolNumberSchema constructNumberSchema(String description) {
    try {
      return new ToolNumberSchema(description);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("number schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolBooleanSchema constructBooleanSchema(String description) {
    try {
      return new ToolBooleanSchema(description);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("boolean schema invalid: " + error.getMessage(), error);
    }
  }

  private static ToolArraySchema constructArraySchema(String description, ToolSchemaElement items) {
    try {
      return new ToolArraySchema(description, items);
    } catch (IllegalArgumentException error) {
      throw new DaemonProtocolException("array schema invalid: " + error.getMessage(), error);
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
      throw new DaemonProtocolException("object schema invalid: " + error.getMessage(), error);
    }
  }

  private static void readObjectSchemaShape(ObjectNode node, String context) {
    JsonNode typeNode = node.get("type");
    if (typeNode == null || !typeNode.isTextual() || !"object".equals(typeNode.textValue())) {
      throw new DaemonProtocolException(context + " schema type must be 'object'");
    }
    if (node.get("properties") == null) {
      throw new DaemonProtocolException(context + " must declare 'properties'");
    }
    if (node.get("required") == null) {
      throw new DaemonProtocolException(context + " must declare 'required'");
    }
    if (node.get("additionalProperties") == null) {
      throw new DaemonProtocolException(context + " must declare 'additionalProperties'");
    }
  }

  private static Set<String> readRequiredArray(ObjectNode node, String context) {
    JsonNode required = node.get("required");
    if (required == null || required.isNull()) {
      return Set.of();
    }
    if (!required.isArray()) {
      throw new DaemonProtocolException(context + " 'required' must be an array");
    }
    Set<String> names = new HashSet<>();
    int index = 0;
    for (JsonNode entry : required) {
      if (!entry.isTextual()) {
        throw new DaemonProtocolException(context + " 'required'[" + index + "] must be a string");
      }
      String text = entry.textValue();
      if (text == null || text.isBlank()) {
        throw new DaemonProtocolException(context + " 'required'[" + index + "] must be non-blank");
      }
      if (!names.add(text)) {
        throw new DaemonProtocolException(
            context + " 'required' contains duplicate entry: " + text);
      }
      index++;
    }
    return names;
  }

  private static boolean readAdditionalProperties(ObjectNode node, String context) {
    JsonNode additional = node.get("additionalProperties");
    if (additional == null || additional.isNull()) {
      throw new DaemonProtocolException(context + " 'additionalProperties' must be present");
    }
    if (!additional.isBoolean()) {
      throw new DaemonProtocolException(context + " 'additionalProperties' must be boolean");
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
        throw new DaemonProtocolException(context + " unknown field: '" + name + "'");
      }
    }
  }

  private static JsonNode requiredField(JsonNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new DaemonProtocolException(context + " must declare '" + field + "'");
    }
    return value;
  }

  private static String requiredText(JsonNode node, String field, String context) {
    String value = optionalText(node, field, context);
    if (value == null || value.isBlank()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a non-blank string");
    }
    return value;
  }

  private static String optionalText(JsonNode node, String field, String context) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw new DaemonProtocolException(context + " '" + field + "' must be a string");
    }
    String text = value.textValue();
    if (text == null) {
      return null;
    }
    return text;
  }

  /**
   * 读取并验证 envelope payload 是合法 JSON object；任何 malformed payload 抛 {@link DaemonProtocolException}。
   */
  private static JsonNode readRoot(String json) {
    if (json == null) {
      throw new DaemonProtocolException("CAPABILITIES payload must not be null");
    }
    try {
      JsonNode value = OBJECT_MAPPER.readTree(json);
      if (value == null || !value.isObject()) {
        throw new DaemonProtocolException("CAPABILITIES payload must be a JSON object");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException("CAPABILITIES payload must be valid JSON", error);
    }
  }

  /**
   * 严格的对象类型守卫：禁止把任意 JsonNode 强转为 ObjectNode 触发 {@link ClassCastException}。任何 null / 数组 /
   * 标量都直接转换为带上下文的 {@link DaemonProtocolException}。
   */
  private static ObjectNode requiredObject(JsonNode node, String context) {
    if (node == null || node.isNull()) {
      throw new DaemonProtocolException(context + " must be a JSON object");
    }
    if (!node.isObject()) {
      throw new DaemonProtocolException(
          context + " must be a JSON object but was " + node.getNodeType().name().toLowerCase());
    }
    return (ObjectNode) node;
  }
}
