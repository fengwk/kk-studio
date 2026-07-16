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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 *   <li>duplicate required property names。
 * </ul>
 *
 * <p>空 capabilities ({@code {"tools":[]}}) 是合法 payload：新注册的 Environment 与尚未暴露工具的 daemon 都可以 表示这种状态。
 */
public final class DaemonToolCapabilitiesCodec {

  /** 顶层 JSON 容器：{@code {"tools":[...]}}。 */
  public record DaemonToolCapabilities(List<ToolDescriptor> tools) {
    public DaemonToolCapabilities {
      tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
      Map<String, ToolDescriptor> seen = new LinkedHashMap<>();
      for (ToolDescriptor descriptor : tools) {
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

  /** 将 {@link DaemonToolCapabilities} 编码为 canonical CAPABILITIES JSON 文本。 */
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
    ObjectNode root = (ObjectNode) readObject(json, "CAPABILITIES");
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
    target.set("inputSchema", writeSchema(descriptor.inputSchema()));
  }

  private static ObjectNode writeSchema(ToolParamsSchema schema) {
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
    properties.forEach((name, schema) -> wireProperties.set(name, writeSchema(schema)));
    ArrayNode wireRequired = target.putArray("required");
    required.forEach(wireRequired::add);
    target.put("additionalProperties", additionalProperties);
    return target;
  }

  private static ToolDescriptor readDescriptor(JsonNode node, int index) {
    String context = "CAPABILITIES descriptor[" + index + "]";
    if (node == null || !node.isObject()) {
      throw new DaemonProtocolException(context + " must be an object");
    }
    ObjectNode obj = (ObjectNode) node;
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
    if (inputSchemaNode == null || !inputSchemaNode.isObject()) {
      throw new DaemonProtocolException(
          context + " 'inputSchema' must be an object: " + name + "@" + version);
    }
    ToolParamsSchema inputSchema = readParamsSchema((ObjectNode) inputSchemaNode, context);
    return new ToolDescriptor(
        name, version, description, rendererKey, inputSchema, executionMode, sideEffect, timeout);
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

  private static ToolParamsSchema readParamsSchema(ObjectNode node, String context) {
    readObjectSchemaShape(node, context);
    Set<String> allowed =
        Set.of("type", "description", "properties", "required", "additionalProperties");
    rejectUnknownFields(node, allowed, context);
    String description = optionalText(node, "description", context);
    ObjectNode propertiesNode = (ObjectNode) requiredField(node, "properties", context);
    Set<String> requiredNames = readRequiredArray(node, context);
    boolean additionalProperties = readAdditionalProperties(node, context);
    Map<String, ToolSchemaElement> properties = new LinkedHashMap<>();
    propertiesNode
        .fieldNames()
        .forEachRemaining(
            name -> {
              JsonNode child = propertiesNode.get(name);
              if (child == null || !child.isObject()) {
                throw new DaemonProtocolException(
                    context + " property '" + name + "' must be an object");
              }
              properties.put(
                  name,
                  readSchemaElement((ObjectNode) child, context + " property '" + name + "'"));
            });
    return new ToolParamsSchema(description, properties, requiredNames, additionalProperties);
  }

  private static ToolSchemaElement readSchemaElement(ObjectNode node, String context) {
    if (node == null || !node.isObject()) {
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
          return new ToolStringSchema(description);
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
        return new ToolEnumSchema(description, List.copyOf(values));
      }
      case "integer" -> {
        Set<String> allowed = Set.of("type", "description");
        rejectUnknownFields(node, allowed, context);
        return new ToolIntegerSchema(description);
      }
      case "number" -> {
        Set<String> allowed = Set.of("type", "description");
        rejectUnknownFields(node, allowed, context);
        return new ToolNumberSchema(description);
      }
      case "boolean" -> {
        Set<String> allowed = Set.of("type", "description");
        rejectUnknownFields(node, allowed, context);
        return new ToolBooleanSchema(description);
      }
      case "array" -> {
        Set<String> allowed = Set.of("type", "description", "items");
        rejectUnknownFields(node, allowed, context);
        JsonNode items = requiredField(node, "items", context);
        if (!items.isObject()) {
          throw new DaemonProtocolException(context + " 'items' must be an object");
        }
        return new ToolArraySchema(
            description, readSchemaElement((ObjectNode) items, context + " items"));
      }
      case "object" -> {
        Set<String> allowed =
            Set.of("type", "description", "properties", "required", "additionalProperties");
        rejectUnknownFields(node, allowed, context);
        readObjectSchemaShape(node, context);
        ObjectNode propertiesNode = (ObjectNode) requiredField(node, "properties", context);
        Set<String> requiredNames = readRequiredArray(node, context);
        boolean additionalProperties = readAdditionalProperties(node, context);
        Map<String, ToolSchemaElement> properties = new LinkedHashMap<>();
        propertiesNode
            .fieldNames()
            .forEachRemaining(
                name -> {
                  JsonNode child = propertiesNode.get(name);
                  if (child == null || !child.isObject()) {
                    throw new DaemonProtocolException(
                        context + " property '" + name + "' must be an object");
                  }
                  properties.put(
                      name,
                      readSchemaElement((ObjectNode) child, context + " property '" + name + "'"));
                });
        return new ToolObjectSchema(description, properties, requiredNames, additionalProperties);
      }
      default -> throw new DaemonProtocolException(context + " unknown schema type: " + type);
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
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) {
                throw new DaemonProtocolException(context + " unknown field: '" + name + "'");
              }
            });
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

  private static JsonNode readObject(String json, String context) {
    try {
      JsonNode value = OBJECT_MAPPER.readTree(json);
      if (value == null || !value.isObject()) {
        throw new DaemonProtocolException(context + " payload must be a JSON object");
      }
      return value;
    } catch (JsonProcessingException error) {
      throw new DaemonProtocolException(context + " payload must be valid JSON", error);
    }
  }
}
