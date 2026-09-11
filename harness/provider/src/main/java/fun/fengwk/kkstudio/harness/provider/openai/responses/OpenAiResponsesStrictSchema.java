package fun.fengwk.kkstudio.harness.provider.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 函数工具参数 schema 的 OpenAI Responses strict 归一化。
 *
 * <p>strict 工具要求每个 object 节点显式声明 {@code required} 并给出 {@code additionalProperties: false}；同时 strict
 * 工具 必须把 {@code properties} 的每个 key 都列入 {@code required}。因此“可选属性”只能靠类型允许 null 来表达：仅在源 schema
 * 确实把某属性排除在 {@code required} 之外（即该属性本身允许缺省）、且它当前不允许 null 时，才把它改写为 {@code anyOf: [原 schema, {"type":
 * "null"}]}；本来就是必填、或本来就允许 null 的属性保持原 schema 不变，不引入任何额外语义。
 *
 * <p>归一化只作用于新建的深拷贝节点树，调用方共享的 schema 原样不变。
 */
final class OpenAiResponsesStrictSchema {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private OpenAiResponsesStrictSchema() {}

  /** 返回 strict 形态的深拷贝；输入节点绝不被修改。 */
  static ObjectNode normalize(ObjectNode schema) {
    ObjectNode copy = schema.deepCopy();
    normalizeNode(copy);
    return copy;
  }

  private static void normalizeNode(JsonNode node) {
    if (!node.isObject()) {
      return;
    }
    ObjectNode object = (ObjectNode) node;
    if (isObjectSchema(object)) {
      normalizeObjectSchema(object);
    }
    JsonNode items = object.get("items");
    if (items != null && !items.isNull()) {
      normalizeNode(items);
    }
  }

  private static boolean isObjectSchema(ObjectNode node) {
    return "object".equals(node.path("type").asText()) || node.has("properties");
  }

  private static void normalizeObjectSchema(ObjectNode object) {
    JsonNode propertiesNode = object.get("properties");
    ObjectNode properties =
        propertiesNode != null && propertiesNode.isObject() ? (ObjectNode) propertiesNode : null;
    Set<String> declaredRequired = declaredRequired(object);
    List<String> propertyNames = new ArrayList<>();
    if (properties != null) {
      properties.fieldNames().forEachRemaining(propertyNames::add);
    }
    for (String name : propertyNames) {
      JsonNode property = properties.get(name);
      normalizeNode(property);
      if (!declaredRequired.contains(name) && !allowsNull(property)) {
        ObjectNode nullable = NODES.objectNode();
        ArrayNode anyOf = nullable.putArray("anyOf");
        anyOf.add(property);
        anyOf.addObject().put("type", "null");
        properties.set(name, nullable);
      }
    }
    ArrayNode required = object.putArray("required");
    propertyNames.forEach(required::add);
    object.put("additionalProperties", false);
  }

  /** 读取源 schema 显式声明的 required；非字符串元素忽略，缺省即视为没有任何必填属性。 */
  private static Set<String> declaredRequired(ObjectNode object) {
    JsonNode requiredNode = object.get("required");
    Set<String> required = new HashSet<>();
    if (requiredNode != null && requiredNode.isArray()) {
      for (JsonNode entry : requiredNode) {
        if (entry.isTextual()) {
          required.add(entry.textValue());
        }
      }
    }
    return required;
  }

  /** 属性是否已经允许 null：{@code type: "null"} 或类型数组包含 {@code "null"}。 */
  private static boolean allowsNull(JsonNode property) {
    if (property == null || !property.isObject()) {
      return false;
    }
    JsonNode type = property.get("type");
    if (type == null) {
      return false;
    }
    if (type.isTextual()) {
      return "null".equals(type.textValue());
    }
    if (type.isArray()) {
      for (JsonNode variant : type) {
        if (variant.isTextual() && "null".equals(variant.textValue())) {
          return true;
        }
      }
    }
    return false;
  }
}
